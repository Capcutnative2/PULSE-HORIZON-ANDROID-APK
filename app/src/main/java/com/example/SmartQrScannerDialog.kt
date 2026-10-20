package com.example

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.concurrent.Executors

/**
 * PULSE HORIZON - Smart QR Scanner.
 *
 * v160: rewritten to cost almost nothing.
 *
 * WHAT CHANGED AND WHY
 *   The old version used CameraX (about 1.5 MB) plus Google's BUNDLED ML Kit
 *   barcode model (9.5 MB). Together that was about 11 MB of the APK for one
 *   feature - and it made the app look bloated for no reason.
 *
 *   This version calls Android instead of bundling a camera stack:
 *
 *     camera2 + ImageReader   part of Android itself. ZERO bytes in the APK.
 *     zxing:core              one pure-Java library, 594 KB. No Play Services,
 *                             so it still works on a de-Googled device.
 *
 *   Total added to the APK: about 600 KB, against 11 MB before.
 *
 * WHAT IT STILL DOES - the part that matters
 *   ZXing's GenericMultipleBarcodeReader finds SEVERAL codes in one frame, and
 *   every result carries its ResultPoints - the corner positions. Those are
 *   what put an arrow on each code.
 *
 *   When more than one code is in view the picture FREEZES so the arrows cannot
 *   move under your finger, and you tap the arrow for the one you want.
 *   Tapping empty space starts scanning again.
 *
 *   Google Lens cannot do that. That is the whole reason this exists.
 */
class SmartQrScannerDialog(
    context: Context,
    private val onQrSelected: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    // ---- camera ----
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var sensorOrientation = 90
    private var frameW = 0
    private var frameH = 0
    private var isTorchOn = false
    private var isFrozen = false
    private var sessionReady = false

    // ---- decoding, on one worker so the UI never stutters ----
    private val worker = Executors.newSingleThreadExecutor()
    private val multiReader = GenericMultipleBarcodeReader(
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    )

    // ---- views ----
    private lateinit var textureView: TextureView
    private lateinit var overlayView: QrOverlayView
    private lateinit var hintTextView: TextView
    private lateinit var torchButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = surfaceListener
        }
        root.addView(textureView)

        overlayView = QrOverlayView(context, { text ->
            dismiss()
            onQrSelected(text)
        }, {
            resumeScanning()
        }).apply {
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // ---- top bar ----
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 56, 40, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        torchButton = TextView(context).apply {
            text = "Flash"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 16, 32, 16)
            background = pill(Color.parseColor("#331E293B"))
            setOnClickListener { toggleTorch() }
        }
        topBar.addView(torchButton)

        topBar.addView(TextView(context).apply {
            text = "Smart QR Scanner"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        topBar.addView(TextView(context).apply {
            text = "X"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = pill(Color.parseColor("#331E293B"))
            setOnClickListener { dismiss() }
        })
        root.addView(topBar)

        // ---- hint pill ----
        hintTextView = TextView(context).apply {
            text = "Point camera at a QR code"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            background = pill(Color.parseColor("#66111827"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 90 }
        }
        root.addView(hintTextView)

        setContentView(root)
    }

    private fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = 48f
    }

    // ================================================================
    //  CAMERA - camera2, straight from the OS
    // ================================================================

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun openCamera() {
        if (cameraDevice != null) return
        try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var chosen: String? = null
            var chars: CameraCharacteristics? = null
            for (id in mgr.cameraIdList) {
                val c = mgr.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id
                    chars = c
                    break
                }
            }
            if (chosen == null) {
                chosen = mgr.cameraIdList.firstOrNull()
                if (chosen != null) chars = mgr.getCameraCharacteristics(chosen)
            }
            val id = chosen ?: return

            sensorOrientation =
                chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = pickSize(map)
            frameW = size.width
            frameH = size.height

            cameraThread = HandlerThread("qr-camera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)

            imageReader = ImageReader.newInstance(frameW, frameH, android.graphics.ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                if (!isFrozen) decodeFrame(img)
                try { img.close() } catch (_: Exception) {}
            }, cameraHandler)

            @Suppress("MissingPermission")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    try { device.close() } catch (_: Exception) {}
                    cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    try { device.close() } catch (_: Exception) {}
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (_: Exception) {
            toast("Could not open the camera")
        }
    }

    private fun pickSize(map: StreamConfigurationMap?): Size {
        val fallback = Size(1280, 720)
        val sizes = try {
            map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        } catch (_: Exception) { null } ?: return fallback
        // prefer 720p-ish: enough to decode a QR, cheap to process
        val wanted = 1280 * 720
        return sizes.filter { it.width * it.height <= wanted * 2 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: fallback
    }

    private fun startSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        try {
            st.setDefaultBufferSize(frameW, frameH)
            val previewSurface = Surface(st)
            val readerSurface = imageReader?.surface ?: return

            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }

            device.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionReady = true
                        try { session.setRepeatingRequest(req.build(), null, cameraHandler) } catch (_: Exception) {}
                        textureView.post { applyPreviewTransform() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionReady = false
                    }
                },
                cameraHandler
            )
        } catch (_: Exception) {
        }
    }

    /** Rebuilds the repeating request - used for the torch and to resume. */
    private fun refreshRequest() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        val readerSurface = imageReader?.surface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(st))
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            captureSession?.setRepeatingRequest(req.build(), null, cameraHandler)
        } catch (_: Exception) {}
    }

    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        try { torchButton.text = if (isTorchOn) "Flash on" else "Flash" } catch (_: Exception) {}
        refreshRequest()
    }

    // ================================================================
    //  THE FREEZE - so the arrows cannot move under your finger
    // ================================================================

    private fun freezeForSelection() {
        if (isFrozen) return
        isFrozen = true
        overlayView.isFrozen = true
        // stopRepeating stops the live feed. The TextureView keeps the last
        // frame, so the picture genuinely freezes where the codes were.
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try {
            hintTextView.text = "Tap an arrow to pick that QR - or tap empty space to scan again"
            hintTextView.setTextColor(Color.parseColor("#22D3EE"))
        } catch (_: Exception) {}
    }

    private fun resumeScanning() {
        if (!isFrozen) return
        isFrozen = false
        overlayView.isFrozen = false
        overlayView.clearCodes()
        try {
            hintTextView.text = "Point camera at a QR code"
            hintTextView.setTextColor(Color.WHITE)
        } catch (_: Exception) {}
        refreshRequest()
    }

    // ================================================================
    //  DECODING - zxing:core, on the worker thread
    // ================================================================

    private fun decodeFrame(image: Image) {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // The Y plane IS the luminance, so it can be decoded directly. When the
        // rows are padded we copy them out tightly first.
        val data: ByteArray
        if (pixelStride == 1 && rowStride == w) {
            data = ByteArray(w * h)
            buffer.get(data, 0, minOf(data.size, buffer.remaining()))
        } else {
            data = ByteArray(w * h)
            val row = ByteArray(rowStride)
            var out = 0
            for (y in 0 until h) {
                val toRead = minOf(rowStride, buffer.remaining())
                if (toRead <= 0) break
                buffer.get(row, 0, toRead)
                val copy = minOf(w, toRead)
                System.arraycopy(row, 0, data, out, copy)
                out += copy
                if (out >= data.size) break
            }
        }

        worker.execute {
            val found = try {
                val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                multiReader.decodeMultiple(bitmap)?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (found.isEmpty()) return@execute

            val entries = found.mapNotNull { r ->
                val text = r.text ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                Entry(text, r.resultPoints)
            }
            if (entries.isEmpty()) return@execute

            textureView.post {
                if (isFrozen) return@post
                overlayView.setCodes(entries, w, h, sensorOrientation)
                if (entries.size > 1) {
                    freezeForSelection()
                } else {
                    hintTextView.text = "Tap the arrow to scan"
                    hintTextView.setTextColor(Color.parseColor("#22D3EE"))
                }
            }
        }
    }

    // ================================================================
    //  COORDINATES - one place, used by the preview AND the overlay, so
    //  they can never disagree about where a code is.
    // ================================================================

    private fun rotatedFrame(): Pair<Float, Float> =
        if (sensorOrientation == 90 || sensorOrientation == 270) Pair(frameH.toFloat(), frameW.toFloat())
        else Pair(frameW.toFloat(), frameH.toFloat())

    private fun applyPreviewTransform() {
        if (frameW <= 0 || frameH <= 0) return
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val (rw, rh) = rotatedFrame()
        val scale = maxOf(vw / rw, vh / rh)
        val tx = (vw - rw * scale) / 2f
        val ty = (vh - rh * scale) / 2f

        val m = Matrix()
        m.postRotate(sensorOrientation.toFloat())
        when (sensorOrientation) {
            90 -> m.postTranslate(frameH.toFloat(), 0f)
            270 -> m.postTranslate(0f, frameW.toFloat())
            180 -> m.postTranslate(frameW.toFloat(), frameH.toFloat())
        }
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        try { textureView.setTransform(m) } catch (_: Exception) {}
    }

    // ================================================================
    //  THE OVERLAY - boxes, brackets, and an arrow on every code
    // ================================================================

    private data class Entry(val text: String, val points: Array<ResultPoint>?)

    private class QrOverlayView(
        context: Context,
        private val onSelect: (String) -> Unit,
        private val onResumeRequest: () -> Unit
    ) : View(context) {

        var isFrozen = false

        private var entries = listOf<Entry>()
        private var srcW = 0
        private var srcH = 0
        private var rotation = 90
        private val arrowTargets = mutableListOf<Pair<RectF, String>>()

        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F0FF")
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.FILL
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#04121C")
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

        fun clearCodes() {
            entries = emptyList()
            postInvalidate()
        }

        fun setCodes(list: List<Entry>, width: Int, height: Int, rot: Int) {
            entries = list
            srcW = width
            srcH = height
            rotation = rot
            postInvalidate()
        }

        private fun rotatedFrame(): Pair<Float, Float> =
            if (rotation == 90 || rotation == 270) Pair(srcH.toFloat(), srcW.toFloat())
            else Pair(srcW.toFloat(), srcH.toFloat())

        /** Same maths as the preview transform, so the box lands on the code. */
        private fun mapPoint(x: Float, y: Float): PointF {
            val (rw, rh) = rotatedFrame()
            val vw = width.toFloat()
            val vh = height.toFloat()
            if (rw <= 0f || rh <= 0f) return PointF(x, y)
            val scale = maxOf(vw / rw, vh / rh)
            val tx = (vw - rw * scale) / 2f
            val ty = (vh - rh * scale) / 2f
            val rx: Float
            val ry: Float
            when (rotation) {
                90 -> { rx = srcH - y; ry = x }
                180 -> { rx = srcW - x; ry = srcH - y }
                270 -> { rx = y; ry = srcW - x }
                else -> { rx = x; ry = y }
            }
            return PointF(rx * scale + tx, ry * scale + ty)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            arrowTargets.clear()

            if (entries.isEmpty() || srcW <= 0 || srcH <= 0) {
                val s = minOf(width.toFloat(), height.toFloat()) * 0.6f
                val l = (width - s) / 2f
                val t = (height - s) / 2f
                bracketPaint.alpha = 70
                drawBrackets(canvas, RectF(l, t, l + s, t + s))
                bracketPaint.alpha = 255
                return
            }

            val multiple = entries.size > 1
            for (e in entries) {
                val quad = quadFor(e) ?: continue

                // v161: THE BOX NOW SITS ON THE CODE'S OWN BORDER.
                //
                // zxing gives three points for a QR - the centres of the three
                // finder patterns. A box around three points does NOT cover the
                // code, which is exactly why it was impossible to tell which box
                // belonged to which code. The fourth corner is derived from the
                // other three and the quad is drawn around all four, so the
                // outline hugs that code and nothing else.
                val path = Path()
                path.moveTo(quad[0].x, quad[0].y)
                for (i in 1 until quad.size) path.lineTo(quad[i].x, quad[i].y)
                path.close()
                canvas.drawPath(path, bracketPaint)

                if (multiple) {
                    // The arrow sits on the topmost corner of THIS code's own
                    // outline, so it is visibly attached to it.
                    var top = quad[0]
                    for (p in quad) if (p.y < top.y || (p.y == top.y && p.x > top.x)) top = p
                    val d = 26f * resources.displayMetrics.density
                    val badge = RectF(top.x - d, top.y - d, top.x + d, top.y + d)
                    canvas.drawRoundRect(badge, d, d, badgePaint)
                    drawArrowIcon(canvas, top.x, top.y, d * 0.5f)
                    arrowTargets.add(Pair(badge, e.text))
                }
            }
        }

        /**
         * v161: THE FOUR CORNERS OF ONE CODE.
         *
         * zxing reports a QR as three points - the finder pattern centres. The
         * fourth corner of the square is the missing one, and it can be worked
         * out: if the three known points are the right-angle corner V and its
         * two neighbours P and Q, the fourth is P + Q - V.
         *
         * Then the square is grown slightly, because the finder centres sit a
         * little inside the code. That is what makes the outline land on the
         * code's own border rather than floating over its middle.
         */
        private fun quadFor(e: Entry): List<PointF>? {
            val pts = e.points
            if (pts == null || pts.size < 3) return fallbackQuad()
            val m = pts.map { mapPoint(it.x, it.y) }
            if (m.size < 3) return fallbackQuad()

            val a = m[0]; val b = m[1]; val c = m[2]
            // which of the three is the right-angle corner?
            fun angleAt(v: PointF, p: PointF, q: PointF): Float {
                val v1x = p.x - v.x; val v1y = p.y - v.y
                val v2x = q.x - v.x; val v2y = q.y - v.y
                val n1 = kotlin.math.hypot(v1x, v1y)
                val n2 = kotlin.math.hypot(v2x, v2y)
                if (n1 < 1f || n2 < 1f) return 180f
                val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
                return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
            }
            val da = kotlin.math.abs(angleAt(a, b, c) - 90f)
            val db = kotlin.math.abs(angleAt(b, a, c) - 90f)
            val dc = kotlin.math.abs(angleAt(c, a, b) - 90f)

            val v: PointF; val p: PointF; val q: PointF
            if (da <= db && da <= dc) { v = a; p = b; q = c }
            else if (db <= dc) { v = b; p = a; q = c }
            else { v = c; p = a; q = b }

            val fourth = PointF(p.x + q.x - v.x, p.y + q.y - v.y)
            val corners = listOf(v, p, q, fourth)

            // grow it so it sits on the code's border
            var cx = 0f; var cy = 0f
            for (c2 in corners) { cx += c2.x; cy += c2.y }
            cx /= 4f; cy /= 4f
            val grow = 1.18f
            return corners.map { PointF(cx + (it.x - cx) * grow, cy + (it.y - cy) * grow) }
        }

        private fun fallbackQuad(): List<PointF> {
            val s = minOf(width.toFloat(), height.toFloat()) * 0.5f
            val l = (width - s) / 2f
            val t = (height - s) / 2f
            return listOf(
                PointF(l, t), PointF(l + s, t), PointF(l + s, t + s), PointF(l, t + s)
            )
        }

        /** Bounding rect of a code's outline - used for hit testing. */
        private fun boxFor(e: Entry): RectF? {
            val q = quadFor(e) ?: return null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in q) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            if (maxX <= minX || maxY <= minY) return null
            return RectF(minX, minY, maxX, maxY)
        }

        private fun drawBrackets(canvas: Canvas, r: RectF) {
            val arm = minOf(r.width(), r.height()) * 0.28f
            canvas.drawLine(r.left, r.top, r.left + arm, r.top, bracketPaint)
            canvas.drawLine(r.left, r.top, r.left, r.top + arm, bracketPaint)
            canvas.drawLine(r.right, r.top, r.right - arm, r.top, bracketPaint)
            canvas.drawLine(r.right, r.top, r.right, r.top + arm, bracketPaint)
            canvas.drawLine(r.left, r.bottom, r.left + arm, r.bottom, bracketPaint)
            canvas.drawLine(r.left, r.bottom, r.left, r.bottom - arm, bracketPaint)
            canvas.drawLine(r.right, r.bottom, r.right - arm, r.bottom, bracketPaint)
            canvas.drawLine(r.right, r.bottom, r.right, r.bottom - arm, bracketPaint)
        }

        private fun drawArrowIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val p = Path()
            p.moveTo(cx - size * 0.45f, cy - size * 0.7f)
            p.lineTo(cx + size * 0.55f, cy)
            p.lineTo(cx - size * 0.45f, cy + size * 0.7f)
            canvas.drawPath(p, arrowPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val tx = event.x
            val ty = event.y
            val pad = 28f * resources.displayMetrics.density

            // 1. an arrow?
            for ((rect, text) in arrowTargets) {
                val padded = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
                if (padded.contains(tx, ty)) {
                    vibrate()
                    onSelect(text)
                    return true
                }
            }

            // 2. inside a code's box?
            for (e in entries) {
                val box = boxFor(e) ?: continue
                if (box.contains(tx, ty)) {
                    vibrate()
                    onSelect(e.text)
                    return true
                }
            }

            // 3. one code on screen - anywhere counts
            if (entries.size == 1) {
                vibrate()
                onSelect(entries[0].text)
                return true
            }

            // 4. frozen and nothing hit - start scanning again
            if (isFrozen) {
                onResumeRequest()
                return true
            }
            return true
        }

        private fun vibrate() {
            try {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(45)
                }
            } catch (_: Exception) {}
        }
    }

    // ================================================================
    //  CLEANUP - nothing may outlive the dialog
    // ================================================================

    private fun toast(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    override fun onStop() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        sessionReady = false
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
        super.onStop()
    }


    override fun dismiss() {
        try { worker.shutdownNow() } catch (_: Exception) {}
        super.dismiss()
    }
}
