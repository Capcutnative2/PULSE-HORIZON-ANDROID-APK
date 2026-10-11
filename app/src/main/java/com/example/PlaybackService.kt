package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * v69: background-music keep-alive. It plays nothing itself - the audio comes
 * from the decoder in MainActivity. Its job is to keep the process alive, show
 * the notification, and carry the CONTROLS.
 *
 *  - Only ever started by MainActivity, only when "Background music" is on.
 *  - START_NOT_STICKY: it never comes back on its own.
 *
 * v112: the notification has real controls. Each button broadcasts an action
 * that MainActivity receives. The service still touches no audio.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "pulse_horizon_playback"
        const val NOTIFICATION_ID = 4711
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_QUEUE = "has_queue"
        const val ACTION_TOGGLE = "com.example.pulsehorizon.TOGGLE"
        const val ACTION_REWIND = "com.example.pulsehorizon.REWIND"
        const val ACTION_FORWARD = "com.example.pulsehorizon.FORWARD"
        const val ACTION_PREV = "com.example.pulsehorizon.PREV"
        const val ACTION_NEXT = "com.example.pulsehorizon.NEXT"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val hasQueue = intent?.getBooleanExtra(EXTRA_QUEUE, false) ?: false
        try {
            startForeground(NOTIFICATION_ID, buildNotification(playing, hasQueue))
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

    private fun action(code: Int, act: String, icon: Int, title: String): Notification.Action {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val i = Intent(act).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, code, i, flags)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(playing: Boolean, hasQueue: Boolean): Notification {
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

        // Previous / next only when there is more than one song to move between.
        if (hasQueue) {
            b.addAction(action(1, ACTION_PREV, android.R.drawable.ic_media_previous, "Previous"))
        }
        b.addAction(action(2, ACTION_REWIND, android.R.drawable.ic_media_rew, "Rewind"))
        if (playing) {
            b.addAction(action(3, ACTION_TOGGLE, android.R.drawable.ic_media_pause, "Pause"))
        } else {
            b.addAction(action(3, ACTION_TOGGLE, android.R.drawable.ic_media_play, "Play"))
        }
        b.addAction(action(4, ACTION_FORWARD, android.R.drawable.ic_media_ff, "Forward"))
        if (hasQueue) {
            b.addAction(action(5, ACTION_NEXT, android.R.drawable.ic_media_next, "Next"))
        }
        return b.build()
    }
}
