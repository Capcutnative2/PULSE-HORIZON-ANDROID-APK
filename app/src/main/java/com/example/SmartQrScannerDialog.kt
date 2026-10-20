package com.example

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Smart QR Scanner dialog supporting multi-QR code detection.
 * When multiple QR codes are in view, a distinct arrow button appears on each QR code.
 * Tapping a specific arrow selects and scans only that QR code.
 */
class SmartQrScannerDialog(
    context: Context,
    private val onQrSelected: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: QrOverlayView
    private lateinit var hintTextView: TextView
    private lateinit var torchButton: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        overlayView = QrOverlayView(context) { selectedText ->
            dismiss()
            onQrSelected(selectedText)
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // Top Navigation Bar
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
            text = "⚡ Flash"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 16, 32, 16)
            background = createPillDrawable(Color.parseColor("#331E293B"))
            setOnClickListener {
                toggleTorch()
            }
        }
        topBar.addView(torchButton)

        val titleView = TextView(context).apply {
            text = "Smart QR Scanner"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleView)

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = createPillDrawable(Color.parseColor("#331E293B"))
            setOnClickListener {
                dismiss()
            }
        }
        topBar.addView(closeButton)
        root.addView(topBar)

        // Bottom Instruction Pill
        hintTextView = TextView(context).apply {
            text = "Point camera at a QR code"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            background = createPillDrawable(Color.parseColor("#CC0F172A"))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = 80
            }
            layoutParams = lp
        }
        root.addView(hintTextView)

        setContentView(root)
        startCamera()
    }

    private fun createPillDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(color)
            setStroke(2, Color.parseColor("#4422D3EE"))
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        isTorchOn = !isTorchOn
        try {
            cam.cameraControl.enableTorch(isTorchOn)
            torchButton.text = if (isTorchOn) "⚡ On" else "⚡ Flash"
            torchButton.setTextColor(if (isTorchOn) Color.parseColor("#22D3EE") else Color.WHITE)
        } catch (_: Exception) {}
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = context as? LifecycleOwner ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, QrAnalyzer())
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (_: Exception) {}
    }

    private inner class QrAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
                    val bufferWidth = if (isRotated) imageProxy.height else imageProxy.width
                    val bufferHeight = if (isRotated) imageProxy.width else imageProxy.height

                    overlayView.updateBarcodes(barcodes, bufferWidth, bufferHeight)

                    val count = barcodes.size
                    previewView.post {
                        if (count > 1) {
                            hintTextView.text = "Multiple QR codes detected — Tap an arrow to select"
                            hintTextView.setTextColor(Color.parseColor("#22D3EE"))
                        } else if (count == 1) {
                            hintTextView.text = "Tap the arrow to scan"
                            hintTextView.setTextColor(Color.parseColor("#22D3EE"))
                        } else {
                            hintTextView.text = "Point camera at a QR code"
                            hintTextView.setTextColor(Color.WHITE)
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
        try {
            barcodeScanner.close()
        } catch (_: Exception) {}
    }

    /**
     * Overlay view responsible for drawing bounding boxes, targeting brackets,
     * and the interactive selection arrow on each detected QR code.
     */
    private class QrOverlayView(
        context: Context,
        private val onSelect: (String) -> Unit
    ) : View(context) {

        private var barcodes = listOf<Barcode>()
        private var bufferWidth = 0
        private var bufferHeight = 0

        private val arrowTouchTargets = mutableListOf<Pair<RectF, Barcode>>()

        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F0FF")
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE0B192C")
            style = Paint.Style.FILL
        }

        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
        }

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.FILL
        }

        private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 11f * resources.displayMetrics.density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        fun updateBarcodes(list: List<Barcode>, width: Int, height: Int) {
            this.barcodes = list
            this.bufferWidth = width
            this.bufferHeight = height
            postInvalidate()
        }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            arrowTouchTargets.clear()

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            if (barcodes.isEmpty() || bufferWidth <= 0 || bufferHeight <= 0) {
                // Draw a subtle center viewfinder reticle when idle
                val reticleSize = minOf(viewWidth, viewHeight) * 0.65f
                val left = (viewWidth - reticleSize) / 2f
                val top = (viewHeight - reticleSize) / 2f
                val idleRect = RectF(left, top, left + reticleSize, top + reticleSize)
                bracketPaint.alpha = 70
                drawBrackets(canvas, idleRect)
                bracketPaint.alpha = 255
                return
            }

            val scale = maxOf(viewWidth / bufferWidth, viewHeight / bufferHeight)
            val offsetX = (viewWidth - bufferWidth * scale) / 2f
            val offsetY = (viewHeight - bufferHeight * scale) / 2f
            val multiple = barcodes.size > 1

            for (barcode in barcodes) {
                val box = barcode.boundingBox ?: continue
                val left = (box.left * scale + offsetX).coerceIn(0f, viewWidth)
                val top = (box.top * scale + offsetY).coerceIn(0f, viewHeight)
                val right = (box.right * scale + offsetX).coerceIn(0f, viewWidth)
                val bottom = (box.bottom * scale + offsetY).coerceIn(0f, viewHeight)

                if (right <= left || bottom <= top) continue

                val qrRect = RectF(left, top, right, bottom)
                val cx = qrRect.centerX()
                val cy = qrRect.centerY()

                // Draw bounding corner brackets around the QR code
                drawBrackets(canvas, qrRect)

                // Draw arrow badge
                val radius = 24f * resources.displayMetrics.density
                val badgeBounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                arrowTouchTargets.add(Pair(badgeBounds, barcode))

                // Badge background circle with cyan ring
                canvas.drawCircle(cx, cy, radius, badgeBgPaint)
                canvas.drawCircle(cx, cy, radius, badgeBorderPaint)

                // Draw the directional arrow pointing right: ➔
                drawArrowIcon(canvas, cx, cy, radius * 0.55f)

                // Label tag
                if (multiple) {
                    val tagText = "TAP"
                    val tagW = 34f * resources.displayMetrics.density
                    val tagH = 16f * resources.displayMetrics.density
                    val tagY = cy - radius - 6f * resources.displayMetrics.density
                    val tagRect = RectF(cx - tagW / 2f, tagY - tagH, cx + tagW / 2f, tagY)
                    canvas.drawRoundRect(tagRect, tagH / 2f, tagH / 2f, tagBgPaint)

                    val textY = tagY - tagH / 2f - (tagTextPaint.descent() + tagTextPaint.ascent()) / 2f
                    canvas.drawText(tagText, cx, textY, tagTextPaint)
                }
            }
        }

        private fun drawBrackets(canvas: Canvas, r: RectF) {
            val len = minOf(r.width(), r.height()) * 0.22f
            val path = Path()

            // Top-Left
            path.moveTo(r.left, r.top + len)
            path.lineTo(r.left, r.top)
            path.lineTo(r.left + len, r.top)

            // Top-Right
            path.moveTo(r.right - len, r.top)
            path.lineTo(r.right, r.top)
            path.lineTo(r.right, r.top + len)

            // Bottom-Right
            path.moveTo(r.right, r.bottom - len)
            path.lineTo(r.right, r.bottom)
            path.lineTo(r.right - len, r.bottom)

            // Bottom-Left
            path.moveTo(r.left + len, r.bottom)
            path.lineTo(r.left, r.bottom)
            path.lineTo(r.left, r.bottom - len)

            canvas.drawPath(path, bracketPaint)
        }

        private fun drawArrowIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val half = size * 0.85f
            val shaftLeft = cx - half
            val shaftRight = cx + half

            // Shaft
            canvas.drawLine(shaftLeft, cy, shaftRight, cy, arrowPaint)

            // Arrow head
            val head = half * 0.65f
            canvas.drawLine(shaftRight, cy, shaftRight - head, cy - head, arrowPaint)
            canvas.drawLine(shaftRight, cy, shaftRight - head, cy + head, arrowPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val tx = event.x
                val ty = event.y
                val hitPadding = 24f * resources.displayMetrics.density

                // 1. Check if user tapped directly on any arrow target
                var selected: Barcode? = null
                for ((rect, barcode) in arrowTouchTargets) {
                    val padded = RectF(
                        rect.left - hitPadding,
                        rect.top - hitPadding,
                        rect.right + hitPadding,
                        rect.bottom + hitPadding
                    )
                    if (padded.contains(tx, ty)) {
                        selected = barcode
                        break
                    }
                }

                // 2. Check if user tapped within the bounding box of a barcode
                if (selected == null && barcodes.isNotEmpty()) {
                    val scale = maxOf(width.toFloat() / bufferWidth, height.toFloat() / bufferHeight)
                    val offsetX = (width - bufferWidth * scale) / 2f
                    val offsetY = (height - bufferHeight * scale) / 2f

                    for (b in barcodes) {
                        val box = b.boundingBox ?: continue
                        val r = RectF(
                            box.left * scale + offsetX,
                            box.top * scale + offsetY,
                            box.right * scale + offsetX,
                            box.bottom * scale + offsetY
                        )
                        if (r.contains(tx, ty)) {
                            selected = b
                            break
                        }
                    }
                }

                // 3. If only 1 QR is on screen and tapped anywhere in view
                if (selected == null && barcodes.size == 1) {
                    selected = barcodes[0]
                }

                if (selected != null) {
                    val raw = selected.rawValue ?: selected.displayValue ?: ""
                    if (raw.isNotBlank()) {
                        triggerVibration()
                        onSelect(raw)
                        return true
                    }
                }
            }
            return true
        }

        private fun triggerVibration() {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(45)
                }
            } catch (_: Exception) {}
        }
    }
}
