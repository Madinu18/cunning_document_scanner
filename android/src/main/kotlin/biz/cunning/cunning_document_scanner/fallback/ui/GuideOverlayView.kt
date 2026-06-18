package biz.cunning.cunning_document_scanner.fallback.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a dimmed scrim over the camera preview with a clear, centered framing
 * rectangle the user lines the document up with. The rectangle's shape is
 * controlled by [guideAspect] (width:height) and [guideInset] (margin as a
 * fraction of the shorter side).
 *
 * The cleared rectangle ([guideRect]) is the single source of truth for both
 * the on-screen guide and the initial crop, so what the user frames is exactly
 * what the crop is preset to.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var guideAspect: Double = 0.707
        set(value) {
            field = value
            invalidate()
        }

    var guideInset: Double = 0.08
        set(value) {
            field = value
            invalidate()
        }

    /** The cleared guide rectangle in this view's pixel coordinates. */
    val guideRect = RectF()

    // Latest detected document quad as 8 normalized values [x,y * 4], or null.
    private var detectedQuad: FloatArray? = null
    private var detectionAligned = false

    /** Auto-capture progress while the document is held aligned (0..1). */
    private var captureProgress = 0f

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val detectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34A853")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val detectPath = Path()
    private val cornerRadius = 18f

    /** Push the latest detection result; pass null when nothing was detected. */
    fun setDetection(quad: FloatArray?, aligned: Boolean) {
        detectedQuad = quad
        detectionAligned = aligned
        invalidate()
    }

    /** Update the auto-capture countdown ring (0..1). */
    fun setCaptureProgress(progress: Float) {
        captureProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    init {
        // CLEAR xfermode needs an offscreen layer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun computeGuideRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val margin = (guideInset.toFloat()).coerceIn(0f, 0.4f) * min(w, h)
        val availW = w - 2 * margin
        val availH = h - 2 * margin

        val aspect = guideAspect.toFloat().coerceAtLeast(0.05f)
        var boxW = availW
        var boxH = boxW / aspect
        if (boxH > availH) {
            boxH = availH
            boxW = boxH * aspect
        }

        val left = (w - boxW) / 2f
        val top = (h - boxH) / 2f
        guideRect.set(left, top, left + boxW, top + boxH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGuideRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (guideRect.isEmpty) computeGuideRect()
        // Dim everything, then punch out the guide rectangle.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRoundRect(guideRect, cornerRadius, cornerRadius, clearPaint)
        canvas.drawRoundRect(guideRect, cornerRadius, cornerRadius, borderPaint)

        // Live detected document outline: green when aligned, amber otherwise.
        val quad = detectedQuad
        if (quad != null && quad.size == 8) {
            val w = width.toFloat()
            val h = height.toFloat()
            detectPath.reset()
            detectPath.moveTo(quad[0] * w, quad[1] * h)
            for (i in 1 until 4) {
                detectPath.lineTo(quad[i * 2] * w, quad[i * 2 + 1] * h)
            }
            detectPath.close()
            detectPaint.color =
                if (detectionAligned) Color.parseColor("#34A853") else Color.parseColor("#FFC107")
            canvas.drawPath(detectPath, detectPaint)
        }

        // Auto-capture countdown ring while aligned & steady.
        if (captureProgress > 0f) {
            val cx = guideRect.centerX()
            val cy = guideRect.centerY()
            val r = min(guideRect.width(), guideRect.height()) * 0.18f
            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                -90f, 360f * captureProgress, false, progressPaint
            )
        }
    }
}
