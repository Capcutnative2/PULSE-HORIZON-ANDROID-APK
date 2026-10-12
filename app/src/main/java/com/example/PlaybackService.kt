package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent

/**
 * v69: background-music keep-alive. It plays nothing itself - the audio comes
 * from the decoder in MainActivity. Its job is to keep the process alive and
 * show the media notification.
 *
 *  - Only ever started by MainActivity, only when "Background music" is on.
 *  - START_NOT_STICKY: it never comes back on its own.
 *
 * v114: THE CHROME PATTERN. The notification is a MEDIA notification built
 * around the app's MediaSession. The buttons do not broadcast anything any
 * more - they send MEDIA BUTTON events, which the system delivers straight to
 * the session, and the session IS the player. That is why the old version did
 * nothing: it depended on a receiver living in the screen.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "pulse_horizon_playback"
        const val NOTIFICATION_ID = 4711
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_QUEUE = "has_queue"
        const val EXTRA_TOKEN = "session_token"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val hasQueue = intent?.getBooleanExtra(EXTRA_QUEUE, false) ?: false
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_TOKEN, MediaSession.Token::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_TOKEN)
        try {
            startForeground(NOTIFICATION_ID, buildNotification(playing, hasQueue, token))
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Background music", NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Shown only while audio keeps playing in the background"
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        } catch (_: Exception) {
        }
    }

    // v114: a MEDIA BUTTON, not a custom broadcast. The system routes these to
    // the active MediaSession, so the control reaches the player directly.
    private fun mediaAction(code: Int, keyCode: Int, icon: Int, title: String): Notification.Action {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val i = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        val pi = PendingIntent.getBroadcast(this, code, i, flags)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(
        playing: Boolean, hasQueue: Boolean, token: MediaSession.Token?
    ): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, open, flags)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        b.setContentTitle("Pulse Horizon")
            .setContentText(if (playing) "Playing" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        val compact = ArrayList<Int>()

        if (hasQueue) {
            b.addAction(
                mediaAction(1, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    android.R.drawable.ic_media_previous, "Previous")
            )
            compact.add(0)
        }
        b.addAction(
            mediaAction(2, KeyEvent.KEYCODE_MEDIA_REWIND,
                android.R.drawable.ic_media_rew, "Rewind")
        )
        compact.add(compact.size)
        if (playing) {
            b.addAction(
                mediaAction(3, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    android.R.drawable.ic_media_pause, "Pause")
            )
        } else {
            b.addAction(
                mediaAction(3, KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.R.drawable.ic_media_play, "Play")
            )
        }
        compact.add(compact.size)
        b.addAction(
            mediaAction(4, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.R.drawable.ic_media_ff, "Forward")
        )
        compact.add(compact.size)
        if (hasQueue) {
            b.addAction(
                mediaAction(5, KeyEvent.KEYCODE_MEDIA_NEXT,
                    android.R.drawable.ic_media_next, "Next")
            )
        }

        // v114: MediaStyle - this is what makes it a proper media notification,
        // the same shape Chrome uses. It carries the position bar and lets the
        // system's own media controls drive the session.
        if (token != null) {
            val style = Notification.MediaStyle().setMediaSession(token)
            if (compact.isNotEmpty()) {
                style.setShowActionsInCompactView(*compact.toIntArray())
            }
            b.setStyle(style)
        }
        return b.build()
    }
}
