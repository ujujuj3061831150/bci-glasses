package com.bci.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ControlSceneView(
    context: Context,
    private val session: ControlSession,
    private val onFrame: (Long) -> Unit,
) : View(context), Choreographer.FrameCallback {
    var flashingEnabled = false
    private val gazeProvider = SimulatedPointerGazeProvider()
    var gazeAvailable: Boolean
        get() = gazeProvider.available
        set(value) { gazeProvider.available = value }
    var forceOutsideViewport = false
    var encoding: List<ControlDirection> = ControlDirections.all
        private set
    var targetScale = 0.43f
        private set
    var brightnessLimit = 0.85f
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var callbackActive = false
    private var frameTimeNanos = 0L
    private var previousFrameNanos = 0L
    private var frameCount = 0L
    private var droppedFrames = 0L
    private var cx = 0f
    private var cy = 0f
    private var radius = 1f
    private var targetSize = 1f
    private val cyan = Color.rgb(91, 224, 202)

    init {
        isFocusable = true
        contentDescription = "八方向模拟注视操作区，触摸方向位置保持模拟视线；不是实际眼动"
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    fun resetInput() { gazeProvider.reset() }
    fun frameDiagnostics(): String = "frame_time_ns=$frameTimeNanos; callbacks=$frameCount; estimated_dropped=$droppedFrames; flashing=$flashingEnabled"
    fun configureStimulus(frequencies: List<Double>, scale: Float, brightness: Float) {
        require(frequencies.size == 4 && frequencies.all { it.isFinite() && it > 0 && it <= 30 } && frequencies.toSet().size == 4)
        require(scale in 0.25f..0.55f && brightness in 0.2f..1f)
        session.invalidateLayout(SystemClock.elapsedRealtime())
        encoding = ControlDirections.all.map { it.copy(frequencyHz = frequencies[it.index % 4]) }
        targetScale = scale; brightnessLimit = brightness
        targetSize = min(radius * targetScale, dp(64f))
        flashingEnabled = false
        invalidate()
    }
    fun resumeFrames() {
        if (!callbackActive && isAttachedToWindow) {
            callbackActive = true; previousFrameNanos = 0
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun suspendFrames() {
        callbackActive = false; flashingEnabled = false
        Choreographer.getInstance().removeFrameCallback(this)
        invalidate()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); resumeFrames() }
    override fun onDetachedFromWindow() { suspendFrames(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) session.invalidateLayout(SystemClock.elapsedRealtime())
        cx = w * 0.5f; cy = h * 0.49f
        radius = min(w * 0.325f, h * 0.31f).coerceAtLeast(1f)
        targetSize = min(radius * targetScale, dp(64f))
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!callbackActive) return
        this.frameTimeNanos = frameTimeNanos
        if (previousFrameNanos > 0) {
            val expected = 1_000_000_000.0 / (display?.refreshRate ?: 60f)
            droppedFrames += ((frameTimeNanos - previousFrameNanos) / expected).toInt().minus(1).coerceAtLeast(0)
        }
        previousFrameNanos = frameTimeNanos; frameCount++
        val now = SystemClock.elapsedRealtime()
        val fits = radius > 0 && cx - radius - targetSize / 2 >= dp(8f) &&
            cx + radius + targetSize / 2 <= width - dp(8f) &&
            cy - radius - targetSize / 2 >= dp(12f) && cy + radius + targetSize / 2 <= height - dp(24f)
        session.setLayoutValid(fits && !forceOutsideViewport, now)
        session.observe(gazeProvider.sample(now), now)
        session.tick(now)
        onFrame(now)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                updatePointer(event.x, event.y)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked in listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE)) {
            updatePointer(event.x, event.y); return true
        }
        return super.onHoverEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun updatePointer(x: Float, y: Float) {
        gazeProvider.x = ((x - cx) / radius).toDouble(); gazeProvider.y = ((cy - y) / radius).toDouble()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(10, 17, 27))
        paint.strokeWidth = dp(1f); paint.color = Color.rgb(24, 38, 52)
        val grid = dp(30f)
        var x = 0f; while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += grid }
        var y = 0f; while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += grid }
        text(canvas, "对象 01  /  虚拟机械臂工作区", dp(14f), dp(19f), 11f, Color.rgb(150, 177, 196))
        text(canvas, "眼镜显示与空间锚定均为演示", dp(14f), height - dp(28f), 10f, Color.rgb(112, 143, 164))
        text(canvas, "软件帧 ${display?.refreshRate?.toInt() ?: 0}Hz · 回调 $frameCount · 估计漏帧 $droppedFrames", dp(14f), height - dp(12f), 9f, Color.rgb(112, 143, 164))

        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = Color.rgb(43, 66, 85)
        canvas.drawCircle(cx, cy, radius, paint)
        bounds.set(cx - radius * .56f, cy - radius * .48f, cx + radius * .56f, cy + radius * .48f)
        canvas.drawRoundRect(bounds, dp(10f), dp(10f), paint)
        paint.style = Paint.Style.FILL
        val tipX = cx + (session.positionX * radius * .58).toFloat()
        val tipY = cy - (session.positionY * radius * .52).toFloat()
        paint.color = Color.rgb(75, 107, 133); paint.strokeWidth = dp(8f); paint.strokeCap = Paint.Cap.ROUND
        val baseX = cx - radius * .25f; val baseY = cy + radius * .28f
        canvas.drawLine(baseX, baseY, cx - radius * .12f, cy - radius * .20f, paint)
        canvas.drawLine(cx - radius * .12f, cy - radius * .20f, tipX, tipY, paint)
        paint.color = Color.rgb(175, 202, 220); canvas.drawCircle(baseX, baseY, dp(7f), paint)
        paint.color = cyan; canvas.drawCircle(tipX, tipY, dp(6f), paint)
        paint.strokeCap = Paint.Cap.BUTT
        text(canvas, if (session.objectTracked) "模拟对象已绑定" else "对象跟踪丢失", cx, cy + radius * .45f, 9f,
            if (session.objectTracked) cyan else Color.rgb(244, 121, 112), Paint.Align.CENTER)

        val now = SystemClock.elapsedRealtime()
        val hovered = session.hoveredDirection(now)
        for (direction in encoding) {
            val targetX = cx + (direction.dx * radius).toFloat()
            val targetY = cy - (direction.dy * radius).toFloat()
            val active = when (session.phase) {
                ControlPhase.NAVIGATION -> true
                ControlPhase.CANDIDATES -> direction.index in session.candidates
                ControlPhase.EXECUTING -> direction.index == session.selectedDirection
                else -> false
            }
            if (!active && session.phase in listOf(ControlPhase.CANDIDATES, ControlPhase.EXECUTING)) continue
            val seconds = frameTimeNanos / 1_000_000_000.0
            val brightness = if (active && flashingEnabled) brightnessLimit * (0.5 + 0.5 * sin(2 * PI * direction.frequencyHz * seconds + direction.phaseRadians)).toFloat() else if (active) brightnessLimit else .12f
            val shade = (25 + brightness * 140).toInt().coerceIn(0, 255)
            paint.color = if (active) Color.rgb(shade / 2, shade, (shade * .93).toInt()) else Color.rgb(22, 35, 49)
            bounds.set(targetX - targetSize / 2, targetY - targetSize / 2, targetX + targetSize / 2, targetY + targetSize / 2)
            paint.style = Paint.Style.FILL; canvas.drawRoundRect(bounds, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (direction.index == hovered) dp(2.5f) else dp(1f)
            paint.color = if (direction.index == hovered) Color.rgb(255, 211, 126) else if (active) cyan else Color.rgb(45, 63, 80)
            canvas.drawRoundRect(bounds, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.FILL
            text(canvas, direction.label, targetX, targetY - dp(1f), 12f, if (active) Color.WHITE else Color.rgb(108, 132, 151), Paint.Align.CENTER)
            text(canvas, "${"%.2f".format(direction.frequencyHz)}Hz", targetX, targetY + dp(13f), 8f, if (active) Color.rgb(218, 243, 241) else Color.rgb(95, 118, 137), Paint.Align.CENTER)
            if (session.pendingDirection == direction.index) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f); paint.color = Color.rgb(255, 211, 126)
                canvas.drawArc(bounds, -90f, (session.dwellProgress(now) * 360).toFloat(), false, paint)
                paint.style = Paint.Style.FILL
            }
        }
        val gx = cx + (gazeProvider.x * radius).toFloat(); val gy = cy - (gazeProvider.y * radius).toFloat()
        paint.color = if (gazeAvailable) Color.rgb(255, 211, 126) else Color.rgb(231, 109, 107)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
        if (hovered == null) {
            canvas.drawCircle(gx, gy, dp(8f), paint)
            canvas.drawLine(gx - dp(12f), gy, gx + dp(12f), gy, paint)
            canvas.drawLine(gx, gy - dp(12f), gx, gy + dp(12f), paint)
        }
        paint.style = Paint.Style.FILL
        if (!session.layoutValid) text(canvas, "有效显示范围不足 · 已撤销选择", cx, cy, 13f, Color.rgb(255, 173, 117), Paint.Align.CENTER)
        if (!flashingEnabled) text(canvas, "静态预览 · 闪烁关闭", width - dp(12f), dp(19f), 10f, Color.rgb(255, 211, 126), Paint.Align.RIGHT)
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        paint.style = Paint.Style.FILL; paint.textAlign = align; paint.textSize = dp(size); paint.color = color
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
