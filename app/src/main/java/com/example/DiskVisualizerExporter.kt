package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.SweepGradient
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Off-screen renderer -> MediaCodec video -> audio pass-through/mux -> MediaMuxer.
 * Exports .mp4 with visualizer animation and audio track.
 */
class DiskVisualizerExporter(private val context: Context) {

    fun export(
        audioUri: Uri,
        mode: DiskVisualizerView.Mode,
        customColor: Int?,
        vibrationScale: Float,
        logoBitmap: Bitmap?,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                doExport(
                    audioUri,
                    mode,
                    customColor,
                    vibrationScale,
                    logoBitmap,
                    onProgress,
                    onComplete,
                    onError
                )
            } catch (e: Exception) {
                Log.e("PH_Exporter", "Export error", e)
                Handler(Looper.getMainLooper()).post {
                    onError(e.message ?: "Export failed")
                }
            }
        }.start()
    }

    private fun doExport(
        audioUri: Uri,
        mode: DiskVisualizerView.Mode,
        customColor: Int?,
        vibrationScale: Float,
        logoBitmap: Bitmap?,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val width = 720
        val height = 720
        val fps = 30
        val bitRate = 4_000_000

        // Extract audio duration
        val audioExtractor = MediaExtractor()
        if (audioUri.scheme == "content" || audioUri.scheme == "file") {
            try {
                val pfd = context.contentResolver.openFileDescriptor(audioUri, "r")
                if (pfd != null) {
                    audioExtractor.setDataSource(pfd.fileDescriptor)
                    pfd.close()
                } else {
                    audioExtractor.setDataSource(context, audioUri, null)
                }
            } catch (_: Exception) {
                audioExtractor.setDataSource(context, audioUri, null)
            }
        } else {
            audioExtractor.setDataSource(context, audioUri, null)
        }
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }
        if (audioTrackIndex < 0 || audioFormat == null) {
            audioExtractor.release()
            Handler(Looper.getMainLooper()).post { onError("No audio track found in file") }
            return
        }

        audioExtractor.selectTrack(audioTrackIndex)
        val totalDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            30_000_000L // fallback 30s
        }
        // Cap export preview to max 60 seconds for performance
        val maxDurationUs = minOf(totalDurationUs, 60_000_000L)

        // Setup output file
        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val outFile = File(outDir, "pulse_disk_${System.currentTimeMillis()}.mp4")

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Setup Video MediaCodec
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        // Setup off-screen drawing geometry
        val WAVE_N = 220
        val xs = FloatArray(WAVE_N)
        val ys = FloatArray(WAVE_N)
        val oxs = FloatArray(WAVE_N)
        val oys = FloatArray(WAVE_N)
        val wavePath = Path()
        val linePath = Path()
        val logoClipPath = Path()
        val matrix = Matrix()

        val baseThickness = 12f * (width / 360f)
        val diskRadius = width * 0.28f
        val cx = width / 2f
        val cy = height / 2f

        val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0C0C12")
        }
        val diskInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#222230")
            strokeWidth = 2f
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        val rainbowColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.argb(0, 255, 0, 80),
            Color.argb(255, 255, 0, 80),
            Color.argb(255, 255, 120, 0),
            Color.argb(255, 255, 230, 0),
            Color.argb(255, 0, 255, 120),
            Color.argb(255, 0, 220, 255),
            Color.argb(255, 60, 80, 255),
            Color.argb(255, 200, 0, 255),
            Color.argb(0, 200, 0, 255),
            Color.TRANSPARENT
        )
        val positions = floatArrayOf(
            0.00f, 0.04f, 0.08f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f, 0.32f, 0.36f, 0.40f, 1.00f
        )
        val colors = if (customColor != null) {
            val c = customColor
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            intArrayOf(
                Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb(0, r, g, b), Color.argb(255, r, g, b),
                Color.argb(220, (r * 0.8f).toInt(), (g * 0.8f).toInt(), (b * 0.8f).toInt()),
                Color.argb(255, (r * 1.1f).coerceAtMost(255f).toInt(), (g * 1.1f).coerceAtMost(255f).toInt(), (b * 1.1f).coerceAtMost(255f).toInt()),
                Color.argb(180, r, g, b), Color.argb(255, r, g, b),
                Color.argb(240, (r * 0.9f).toInt(), (g * 0.9f).toInt(), (b * 0.9f).toInt()),
                Color.argb(255, r, g, b), Color.argb(0, r, g, b),
                Color.TRANSPARENT
            )
        } else {
            rainbowColors
        }

        val sweepShader = SweepGradient(cx, cy, colors, positions)
        strokePaint.shader = sweepShader
        glowPaint.shader = sweepShader
        wavePaint.shader = sweepShader

        val frameIntervalUs = 1_000_000L / fps
        val totalFrames = (maxDurationUs / frameIntervalUs).toInt()

        var travelAngle = 0f
        var wavePhase = 0f
        var tremorPhase = 0f
        var windLevel = 0f
        var lastBeat = 0f
        var gust = 0f
        val timeScale = 1.0f // 30 fps reference = 1.0f

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) {
                videoEncoder.signalEndOfInputStream()
            }
            while (true) {
                val encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw RuntimeException("format changed twice")
                    val newFormat = videoEncoder.outputFormat
                    muxerVideoTrack = muxer.addTrack(newFormat)
                    muxerAudioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encoderStatus >= 0) {
                    val encodedData = videoEncoder.getOutputBuffer(encoderStatus)
                        ?: throw RuntimeException("encoderOutputBuffer was null")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                    }
                    videoEncoder.releaseOutputBuffer(encoderStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }

        // Render video frames
        for (f in 0 until totalFrames) {
            val presentationTimeUs = f * frameIntervalUs

            // Advance beat simulated / wave geometry with exact timeScale
            val beatSin = 0.5f + 0.5f * sin((f * 0.15).toDouble()).toFloat()
            val beat = Math.pow(beatSin.toDouble(), 3.0).toFloat()
            val pulse = 0.35f + 0.65f * beat

            travelAngle = (travelAngle + 3.2f * timeScale) % 360f
            matrix.setRotate(travelAngle, cx, cy)
            sweepShader.setLocalMatrix(matrix)

            wavePhase += (0.045f + 0.11f * beat) * timeScale
            tremorPhase = (tremorPhase + 2.073f * timeScale) % 6.2831855f

            val riseDelta = (beat - lastBeat) / timeScale
            if (riseDelta > 0.06f) {
                gust = (gust + riseDelta * 2.4f).coerceAtMost(1.3f)
                if (tremorPhase > 1.0f) tremorPhase = 0f
            }
            lastBeat = beat
            gust *= Math.pow(0.90, timeScale.toDouble()).toFloat()
            windLevel = beat

            val thickness = baseThickness * (0.48f + 1.15f * beat)
            strokePaint.strokeWidth = thickness
            glowPaint.strokeWidth = thickness * 1.8f
            val alphaInt = (255 * pulse).toInt().coerceIn(0, 255)
            strokePaint.alpha = alphaInt
            glowPaint.alpha = (alphaInt * 0.45f).toInt()
            wavePaint.alpha = (alphaInt * 0.85f).toInt()

            val canvas = inputSurface.lockCanvas(null)
            canvas.drawColor(Color.parseColor("#09090D"))

            val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))
            val tremor = baseThickness * 0.90f * vibrationScale * windLevel *
                sin(tremorPhase.toDouble()).toFloat()

            var i = 0
            while (i < WAVE_N) {
                val u = i.toFloat() / WAVE_N
                val th = u * 6.2831855f
                val c = cos(th.toDouble()).toFloat()
                val sn = sin(th.toDouble()).toFloat()
                xs[i] = cx + diskRadius * c
                ys[i] = cy + diskRadius * sn

                val s1 = sin((wavePhase + u * 12.566f).toDouble()).toFloat()
                val s2 = sin((wavePhase * 1.7f + u * 25.0f + 1.3f).toDouble()).toFloat()
                val rSurface = amp * (0.55f + 0.45f * (s1 * s1 * s1)) * (0.60f + 0.40f * (s2 * s2 * s2))
                oxs[i] = cx + (diskRadius + rSurface + tremor) * c
                oys[i] = cy + (diskRadius + rSurface + tremor) * sn
                i++
            }

            wavePath.reset()
            wavePath.moveTo(xs[0], ys[0])
            i = 1
            while (i < WAVE_N) { wavePath.lineTo(xs[i], ys[i]); i++ }
            i = WAVE_N - 1
            while (i >= 0) { wavePath.lineTo(oxs[i], oys[i]); i-- }
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)

            linePath.reset()
            linePath.moveTo(xs[0], ys[0])
            i = 1
            while (i < WAVE_N) { linePath.lineTo(xs[i], ys[i]); i++ }
            linePath.close()
            canvas.drawPath(linePath, glowPaint)
            canvas.drawPath(linePath, strokePaint)

            canvas.drawCircle(cx, cy, diskRadius - 1f, diskPaint)
            canvas.drawCircle(cx, cy, diskRadius * 0.82f, diskInnerRingPaint)
            canvas.drawCircle(cx, cy, diskRadius * 0.64f, diskInnerRingPaint)

            val logoR = diskRadius * 0.42f
            if (logoBitmap != null && !logoBitmap.isRecycled) {
                canvas.save()
                logoClipPath.reset()
                logoClipPath.addCircle(cx, cy, logoR, Path.Direction.CW)
                canvas.clipPath(logoClipPath)
                canvas.rotate(travelAngle, cx, cy)
                val scale = (logoR * 2f) / maxOf(logoBitmap.width, logoBitmap.height).toFloat()
                val drawW = logoBitmap.width * scale
                val drawH = logoBitmap.height * scale
                canvas.drawBitmap(logoBitmap, cx - drawW / 2f, cy - drawH / 2f, logoPaint)
                canvas.restore()
                canvas.drawCircle(cx, cy, logoR, strokePaint)
            } else {
                canvas.save()
                canvas.rotate(travelAngle, cx, cy)
                canvas.drawCircle(cx, cy, logoR * 0.5f, diskInnerRingPaint)
                canvas.drawCircle(cx, cy, 6f, strokePaint)
                canvas.restore()
            }

            inputSurface.unlockCanvasAndPost(canvas)
            drainEncoder(false)

            if (f % 15 == 0) {
                val pct = (f * 70) / totalFrames
                Handler(Looper.getMainLooper()).post { onProgress(pct) }
            }
        }

        drainEncoder(true)

        // Pass-through audio track from audioExtractor to muxer
        if (muxerStarted && muxerAudioTrack >= 0) {
            val audioBuffer = ByteBuffer.allocateDirect(128 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                audioBuffer.clear()
                val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (sampleSize < 0) break
                val sampleTime = audioExtractor.sampleTime
                if (sampleTime > maxDurationUs) break

                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                audioExtractor.advance()
            }
        }

        audioExtractor.release()
        try { videoEncoder.stop() } catch (_: Exception) {}
        try { videoEncoder.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}

        Handler(Looper.getMainLooper()).post {
            onProgress(100)
            onComplete(outFile)
        }
    }
}
