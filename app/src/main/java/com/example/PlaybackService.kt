package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * v104: Background-music keep-alive and interactive MediaSession notification.
 *
 * Implements:
 *  - MediaSession with transport controls (Play, Pause, Skip, Seek) sent as media button events.
 *  - Standard Notification.MediaStyle with session token and live position scrubber bar.
 *  - A moving mini music visualizer (level meter / dynamic wave) rendered into a custom RemoteViews
 *    and album art using the EXACT same beat level that drives the edge glow.
 *  - Battery & rate-limit discipline: 250ms (4 FPS) update rate while playing, zero updates when paused.
 *  - Zero memory allocations per frame: single pre-allocated Bitmap, Canvas, and Paint.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "pulse_horizon_playback"
        const val NOTIFICATION_ID = 4711

        const val ACTION_PLAY_PAUSE = "com.pulsehorizon.browser.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.pulsehorizon.browser.ACTION_PREV"
        const val ACTION_NEXT = "com.pulsehorizon.browser.ACTION_NEXT"

        @Volatile var instance: PlaybackService? = null

        // In-process callback to MainActivity for transport controls — no broadcast receivers needed
        var mediaCallback: MediaControlCallback? = null
    }

    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onSkipNext()
        fun onSkipPrev()
        fun onSeekTo(posMs: Long)
    }

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning = false
    @Volatile private var isPlaying = true
    @Volatile private var currentBeatLevel = 0f
    @Volatile private var currentPositionMs = 0L
    @Volatile private var currentDurationMs = 0L
    @Volatile private var mediaTitle = "Pulse Horizon"
    @Volatile private var mediaArtist = "Audio is playing"

    // Zero-allocation drawing cache for the mini level meter / wave visualizer
    private val visualWidth = 128
    private val visualHeight = 64
    private val visualBitmap = Bitmap.createBitmap(visualWidth, visualHeight, Bitmap.Config.ARGB_8888)
    private val visualCanvas = Canvas(visualBitmap)
    private val visualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    // Vibrant neon palette matching Pulse Horizon edge glow
    private val barWeights = floatArrayOf(0.40f, 0.75f, 1.00f, 0.85f, 0.50f)
    private val barColors = intArrayOf(
        Color.parseColor("#00F0FF"), // Cyan
        Color.parseColor("#7000FF"), // Purple
        Color.parseColor("#FF00E5"), // Neon Magenta
        Color.parseColor("#7000FF"), // Purple
        Color.parseColor("#00FFA3")  // Mint
    )

    // Visualizer animation timer — runs strictly at 250ms (4 FPS) to prevent Android notification throttling
    private val visualUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning && isPlaying) {
                renderVisual()
                updateNotification()
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PLAY_PAUSE -> {
                // Route button clicks through MediaSession transport controls
                if (isPlaying) {
                    mediaSession?.controller?.transportControls?.pause()
                } else {
                    mediaSession?.controller?.transportControls?.play()
                }
            }
            ACTION_PREV -> {
                mediaSession?.controller?.transportControls?.skipToPrevious()
            }
            ACTION_NEXT -> {
                mediaSession?.controller?.transportControls?.skipToNext()
            }
            else -> {
                try {
                    renderVisual()
                    startForeground(NOTIFICATION_ID, buildNotification())
                    scheduleVisualUpdates()
                } catch (_: Exception) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun setupMediaSession() {
        try {
            val session = MediaSession(this, "PulseHorizonSession").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        isPlaying = true
                        mediaCallback?.onPlay()
                        syncPlaybackState()
                        scheduleVisualUpdates()
                        updateNotification()
                    }

                    override fun onPause() {
                        isPlaying = false
                        mediaCallback?.onPause()
                        syncPlaybackState()
                        mainHandler.removeCallbacks(visualUpdateRunnable)
                        renderVisual() // Render idle state once
                        updateNotification()
                    }

                    override fun onSkipToNext() {
                        mediaCallback?.onSkipNext()
                    }

                    override fun onSkipToPrevious() {
                        mediaCallback?.onSkipPrev()
                    }

                    override fun onSeekTo(pos: Long) {
                        currentPositionMs = pos
                        mediaCallback?.onSeekTo(pos)
                        syncPlaybackState()
                    }
                })
                isActive = true
            }
            mediaSession = session
            syncPlaybackState()
            syncMetadata()
        } catch (_: Exception) {}
    }

    private fun syncPlaybackState() {
        val session = mediaSession ?: return
        try {
            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val speed = if (isPlaying) 1.0f else 0.0f
            val actions = PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SEEK_TO

            val pbState = PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, speed, SystemClock.elapsedRealtime())
                .build()

            session.setPlaybackState(pbState)
        } catch (_: Exception) {}
    }

    private fun syncMetadata() {
        val session = mediaSession ?: return
        try {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mediaTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, mediaArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, visualBitmap)
                .build()
            session.setMetadata(meta)
        } catch (_: Exception) {}
    }

    // Called from MainActivity with the same beatLevel that drives the edge glow
    fun updateBeatLevel(beat: Float) {
        currentBeatLevel = beat.coerceIn(0f, 1f)
    }

    // Called from MainActivity to keep notification in sync with page/decoder playback
    fun updatePlaybackState(playing: Boolean, posMs: Long, durMs: Long, title: String) {
        val stateChanged = (isPlaying != playing)
        isPlaying = playing
        currentPositionMs = posMs
        currentDurationMs = durMs
        if (title.isNotBlank()) mediaTitle = title

        syncPlaybackState()
        syncMetadata()

        if (stateChanged) {
            if (isPlaying) {
                scheduleVisualUpdates()
            } else {
                mainHandler.removeCallbacks(visualUpdateRunnable)
                renderVisual()
            }
            updateNotification()
        }
    }

    private fun scheduleVisualUpdates() {
        mainHandler.removeCallbacks(visualUpdateRunnable)
        if (isRunning && isPlaying) {
            mainHandler.post(visualUpdateRunnable)
        }
    }

    /**
     * Renders a sleek 5-bar animated level meter / wave into visualBitmap.
     * Reuses the existing Bitmap and Canvas with zero memory allocations per frame.
     */
    private fun renderVisual() {
        visualCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val count = 5
        val barW = 12f
        val gap = 8f
        val startX = (visualWidth - (count * barW + (count - 1) * gap)) / 2f
        val maxH = visualHeight - 12f
        val minH = 6f

        for (i in 0 until count) {
            val weight = barWeights[i]
            val h = if (isPlaying) {
                minH + (maxH - minH) * (currentBeatLevel * weight).coerceIn(0.15f, 1.0f)
            } else {
                minH
            }

            val left = startX + i * (barW + gap)
            val top = visualHeight - 6f - h
            val right = left + barW
            val bottom = visualHeight - 6f

            barRect.set(left, top, right, bottom)
            visualPaint.color = barColors[i]
            visualCanvas.drawRoundRect(barRect, 6f, 6f, visualPaint)
        }
    }

    private fun updateNotification() {
        if (!isRunning) return
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Background music",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Shown only while audio keeps playing in the background"
                ch.setShowBadge(false)
                mgr?.createNotificationChannel(ch)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, open, pFlags)

        val prevPi = getServicePendingIntent(ACTION_PREV, 101)
        val playPausePi = getServicePendingIntent(ACTION_PLAY_PAUSE, 102)
        val nextPi = getServicePendingIntent(ACTION_NEXT, 103)

        // RemoteViews custom layout with live visualizer
        val rv = RemoteViews(packageName, R.layout.notification_media_custom).apply {
            setImageViewBitmap(R.id.notif_visual, visualBitmap)
            setTextViewText(R.id.notif_title, mediaTitle)
            setTextViewText(R.id.notif_artist, mediaArtist)
            setImageViewResource(
                R.id.notif_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            setOnClickPendingIntent(R.id.notif_prev, prevPi)
            setOnClickPendingIntent(R.id.notif_play_pause, playPausePi)
            setOnClickPendingIntent(R.id.notif_next, nextPi)
        }

        val rvBig = RemoteViews(packageName, R.layout.notification_media_custom_big).apply {
            setImageViewBitmap(R.id.notif_big_visual, visualBitmap)
            setTextViewText(R.id.notif_big_title, mediaTitle)
            setTextViewText(R.id.notif_big_artist, mediaArtist)
            setImageViewResource(
                R.id.notif_big_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            setOnClickPendingIntent(R.id.notif_big_prev, prevPi)
            setOnClickPendingIntent(R.id.notif_big_play_pause, playPausePi)
            setOnClickPendingIntent(R.id.notif_big_next, nextPi)
        }

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        b.setContentTitle(mediaTitle)
            .setContentText(mediaArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(visualBitmap)
            .setContentIntent(pi)
            .setOngoing(isPlaying)
            .setCustomContentView(rv)
            .setCustomBigContentView(rvBig)

        // Attach standard MediaStyle with MediaSession token for OS scrubber and lockscreen controls
        val session = mediaSession
        if (session != null) {
            val mediaStyle = Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            b.setStyle(mediaStyle)
        }

        // Standard notification actions (Previous, Play/Pause, Next)
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous",
                prevPi
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(
                    this,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                ),
                if (isPlaying) "Pause" else "Play",
                playPausePi
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next",
                nextPi
            ).build()
        )

        return b.build()
    }

    private fun getServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
