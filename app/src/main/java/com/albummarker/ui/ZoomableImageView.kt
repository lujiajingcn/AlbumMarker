package com.albummarker.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 支持双击放大、捏合缩放、拖动画布的图片查看 view。
 * 基线为 fitCenter（scaleType=MATRIX 手动管理 matrix）。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val displayRect = RectF()

    private val scaleDetector: ScaleGestureDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val drawable = drawable ?: return false
                var factor = detector.scaleFactor
                val total = currentTotalScale()
                if (total <= baseScale + 0.01f && factor < 1f) factor = 1f
                if (total >= maxScale - 0.01f && factor > 1f) factor = 1f
                if (factor == 1f) return true
                suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                updateMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                clampToBounds()
            }
        })

    private val gestureDetector: GestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentTotalScale() > baseScale + 0.01f) {
                    // 已放大：还原
                    suppMatrix.reset()
                    updateMatrix()
                } else {
                    suppMatrix.postScale(doubleTapZoom, doubleTapZoom, e.x, e.y)
                    updateMatrix()
                    clampToBounds()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTapListener?.invoke()
                return true
            }
        })

    /** 基线缩放（fitCenter），保存用于判断当前是否处于放大态。 */
    private var baseScale = 1f

    /** 当前是否已放大；宿主据此区分「拖动查看」与「下拉返回」。 */
    val isZoomed: Boolean
        get() = currentTotalScale() > baseScale + 0.01f

    var minScale = 1f
    var maxScale = 4f
    var doubleTapZoom = 2.5f

    /** 单击回调（宿主可用来切换视频控件显隐等）。 */
    var onTapListener: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isDragging = false

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitCenter() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) fitCenter()
    }

    private fun fitCenter() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = min(width / dw, height / dh)
        baseScale = max(scale, minScale)
        val dx = (width - dw * scale) / 2f
        val dy = (height - dh * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        suppMatrix.reset()
        updateMatrix()
    }

    private fun updateMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        imageMatrix = drawMatrix
        updateDisplayRect()
    }

    private fun updateDisplayRect() {
        val d = drawable ?: return
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(displayRect)
    }

    private fun currentTotalScale(): Float {
        suppMatrix.getValues(matrixValues)
        return baseScale * matrixValues[Matrix.MSCALE_X]
    }

    /** 把图像限制在可视范围内（允许某一方向越界时居中于另一方向）。 */
    private fun clampToBounds() {
        val d = drawable ?: return
        updateDisplayRect()
        val rect = displayRect
        val dx = when {
            rect.width() <= width -> (width - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= height -> (height - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        suppMatrix.postTranslate(dx, dy)
        updateMatrix()
    }

    /**
     * 无障碍 click 通道。视觉上的「单击」反馈由 GestureDetector.onSingleTapConfirmed
     * 负责（需等双击超时确认，避免与双击放大冲突），这里只做父类派发
     * （发送 TYPE_VIEW_CLICKED），两条路径互不重复触发。
     */
    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return true
                }
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (!isDragging &&
                    (abs(x - lastTouchX) > touchSlop || abs(y - lastTouchY) > touchSlop)
                ) {
                    isDragging = true
                }
                if (isDragging) {
                    var dx = x - lastTouchX
                    var dy = y - lastTouchY
                    if (currentTotalScale() > baseScale + 0.01f) {
                        if (displayRect.width() <= width) dx = 0f
                        if (displayRect.height() <= height) dy = 0f
                        suppMatrix.postTranslate(dx, dy)
                        updateMatrix()
                    }
                    lastTouchX = x
                    lastTouchY = y
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> isDragging = false

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDragging
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                clampToBounds()
                if (isUp && !wasDragging) {
                    // 无障碍 / 键盘等辅助点击通道；视觉上的「单击」反馈仍由
                    // GestureDetector.onSingleTapConfirmed 负责，避免重复回调。
                    performClick()
                }
            }
        }
        return true
    }

    /** 供宿主在页面切换时复位缩放。 */
    fun resetZoom() {
        suppMatrix.reset()
        updateMatrix()
    }
}
