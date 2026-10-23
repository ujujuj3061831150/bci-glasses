package com.bci.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import kotlin.math.round

@SuppressLint("ViewConstructor")
class StimulusDashboardView(
    context: Context,
    private val profileName: String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()
    private val target = RectF()
    private var sourceStatus = "connecting"
    private var diagnostics = WindowDiagnostics(0, 0, 0.0, 0.0)

    // SSVEP 视觉状态
    private var visualState: CalibrationVisualState? = null
    private var phaseStartedAtNanos = 0L
    private var pendingEvent: CalibrationFrameEvent? = null
    private var frameListener: ((CalibrationFrameEvent) -> Unit)? = null

    // MI 运动想象视觉状态
    private var miVisualState: MiVisualState? = null
    private var miPhaseStartedAtNanos = 0L
    private var pendingMiEvent: MiFrameEvent? = null
    private var miFrameListener: ((MiFrameEvent) -> Unit)? = null

    private var frameTimeNanos = 0L
    private var lastFrameNanos = 0L
    private var timingStartNanos = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var running = false
    @Volatile private var timing = StimulusTimingStatus.measuring()
    private val timingEvaluator = StimulusTimingEvaluator()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            frameTimeNanos = now
            measureTiming(now)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun updateDataSource(status: String) {
        sourceStatus = status
        invalidate()
    }

    fun updateDiagnostics(value: WindowDiagnostics) {
        diagnostics = value
        invalidate()
    }

    fun stimulusTimingStatus(): StimulusTimingStatus = timing

    // --- SSVEP 回调与状态更新 ---
    fun setCalibrationFrameListener(listener: ((CalibrationFrameEvent) -> Unit)?) {
        frameListener = listener
    }

    fun updateCalibrationVisual(
        state: CalibrationVisualState,
        eventType: String? = null,
        eventCode: Int? = null,
    ) {
        if (visualState?.trialId != state.trialId || visualState?.phase != state.phase) {
            phaseStartedAtNanos = System.nanoTime()
        }
        visualState = state
        if (eventType != null && eventCode != null) pendingEvent = CalibrationFrameEvent(state, eventType, eventCode)
        if (state.phase == CalibrationVisualPhase.ACTIVE) resetTiming()
        invalidate()
    }

    fun clearCalibrationVisual() {
        visualState = null
        pendingEvent = null
        invalidate()
    }

    // --- MI 回调与状态更新 ---
    fun setMiFrameListener(listener: ((MiFrameEvent) -> Unit)?) {
        miFrameListener = listener
    }

    fun updateMiVisual(
        state: MiVisualState,
        eventType: String? = null,
        eventCode: Int? = null,
    ) {
        if (miVisualState?.trialId != state.trialId || miVisualState?.phase != state.phase) {
            miPhaseStartedAtNanos = System.nanoTime()
        }
        miVisualState = state
        if (eventType != null && eventCode != null) pendingMiEvent = MiFrameEvent(state, eventType, eventCode)
        if (state.phase == MiVisualPhase.ACTIVE) resetTiming()
        invalidate()
    }

    fun clearMiVisual() {
        miVisualState = null
        pendingMiEvent = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        resetTiming()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val targetWidth = w * PersonalCaptureSpec.TARGET_WIDTH_RATIO.toFloat()
        val targetHeight = (h * PersonalCaptureSpec.TARGET_HEIGHT_RATIO.toFloat())
            .coerceAtMost(h - dp(230f))
            .coerceAtLeast(1f)
        val centerY = (dp(78f) + h - dp(150f)) / 2f
        target.set(
            (w - targetWidth) / 2f, centerY - targetHeight / 2f,
            (w + targetWidth) / 2f, centerY + targetHeight / 2f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 23, 25))
        val now = frameTimeNanos.takeIf { it > 0L } ?: System.nanoTime()

        when {
            miVisualState != null -> drawMiVisual(canvas, now)
            visualState != null -> drawSsvepVisual(canvas, now)
            else -> drawStandbyVisual(canvas)
        }

        // 分发 SSVEP 帧事件
        pendingEvent?.let { pending ->
            pendingEvent = null
            post { frameListener?.invoke(pending.copy(frameTimeNanos = now)) }
        }

        // 分发 MI 帧事件
        pendingMiEvent?.let { pending ->
            pendingMiEvent = null
            post { miFrameListener?.invoke(pending.copy(frameTimeNanos = now)) }
        }
    }

    private fun drawMiVisual(canvas: Canvas, now: Long) {
        val state = miVisualState ?: return
        val elapsed = (now - miPhaseStartedAtNanos).coerceAtLeast(0L)
        val centerX = target.centerX()
        val centerY = target.centerY()
        val remainingMillis = when (state.phase) {
            MiVisualPhase.PREPARE -> MiCalibrationSpec.PREPARE_MILLIS
            MiVisualPhase.CUE -> MiCalibrationSpec.CUE_MILLIS
            MiVisualPhase.ACTIVE -> MiCalibrationSpec.ACTIVE_MILLIS
            MiVisualPhase.REST -> MiCalibrationSpec.REST_MILLIS
        }
        val remainingSec = ((remainingMillis - elapsed / 1_000_000L).coerceAtLeast(0L) + 999) / 1000

        // 绘制目标边框背景
        fillPaint.color = Color.rgb(20, 28, 30)
        canvas.drawRoundRect(target, dp(12f), dp(12f), fillPaint)

        // 顶部标题
        val blockText = if (state.practice) "练习" else "第 ${state.blockId} 组"
        val topTitle = when (state.phase) {
            MiVisualPhase.PREPARE -> "注视十字 · 准备"
            MiVisualPhase.CUE -> "提示 · ${state.condition.displayName}"
            MiVisualPhase.ACTIVE -> "保持想象 · ${state.condition.shortName}"
            MiVisualPhase.REST -> "休息放松"
        }
        drawFitted(canvas, topTitle, width / 2f, dp(48f), width - dp(40f), sp(22f), Color.WHITE)

        // 中央图形绘制
        when (state.phase) {
            MiVisualPhase.PREPARE -> {
                // 白色注视大十字
                drawCross(canvas, centerX, centerY, dp(72f), dp(6f), Color.rgb(240, 245, 245))
                drawFitted(canvas, "注视中心十字，避免眨眼", centerX, centerY + dp(68f), target.width() - dp(24f), sp(15f), Color.rgb(180, 200, 200))
            }
            MiVisualPhase.CUE -> {
                // 醒目金色箭头提示
                val isLeft = state.condition == MiCondition.LEFT_HAND
                drawArrow(canvas, centerX, centerY, dp(96f), pointingLeft = isLeft, color = Color.rgb(255, 200, 40))
                drawFitted(canvas, "准备想象 ${state.condition.shortName} 握拳", centerX, centerY + dp(76f), target.width() - dp(24f), sp(17f), Color.rgb(255, 220, 80))
            }
            MiVisualPhase.ACTIVE -> {
                // 激活状态：高亮青色/绿色箭头发光脉冲
                val isLeft = state.condition == MiCondition.LEFT_HAND
                val pulse = (kotlin.math.sin(elapsed / 1_000_000_000.0 * 6.0) * 0.15 + 0.85).toFloat()
                drawArrow(canvas, centerX, centerY, dp(96f) * pulse, pointingLeft = isLeft, color = Color.rgb(60, 230, 160))
                drawFitted(canvas, "持续想象 ${state.condition.shortName} 运动...", centerX, centerY + dp(76f), target.width() - dp(24f), sp(17f), Color.rgb(90, 245, 180))
            }
            MiVisualPhase.REST -> {
                // 休息
                drawFitted(canvas, "休息放松，可轻微眨眼", centerX, centerY, target.width() - dp(24f), sp(20f), Color.rgb(130, 160, 160))
            }
        }

        // 底部试次与倒计时进度
        val trialText = "试次 ${state.trialId} / ${state.totalTrialsInBlock} ($blockText)  ·  ${remainingSec} s"
        drawFitted(canvas, trialText, width / 2f, height - dp(120f), width - dp(36f), sp(16f), Color.WHITE)
        drawFitted(canvas, sourceStatus, width / 2f, height - dp(94f), width - dp(36f), sp(11f), Color.rgb(152, 208, 180))
        drawFitted(
            canvas,
            "左右手运动想象 · 专注体验",
            width / 2f, height - dp(72f), width - dp(24f), sp(10f), Color.rgb(166, 179, 177),
        )
    }

    private fun drawSsvepVisual(canvas: Canvas, now: Long) {
        val state = visualState ?: return
        val active = state.phase == CalibrationVisualPhase.ACTIVE
        val elapsed = (now - phaseStartedAtNanos).coerceAtLeast(0L)
        val on = active && squareWaveOn(elapsed)
        fillPaint.color = when {
            on -> Color.rgb(235, 239, 235)
            active -> Color.rgb(24, 32, 33)
            else -> Color.rgb(38, 56, 56)
        }
        canvas.drawRoundRect(target, dp(6f), dp(6f), fillPaint)
        val title = when (state.phase) {
            CalibrationVisualPhase.PREPARE -> "准备"
            CalibrationVisualPhase.ACTIVE -> "注视目标"
            CalibrationVisualPhase.REST -> "休息"
        }
        drawFitted(canvas, title, width / 2f, dp(48f), width - dp(40f), sp(23f), Color.WHITE)
        if (!active) {
            drawFitted(
                canvas, "11 Hz", target.centerX(), target.centerY() + sp(12f),
                target.width() - dp(24f), sp(32f), Color.WHITE,
            )
        }
        val trial = "${state.trialId} / ${state.totalTrialsInBlock}"
        val duration = when (state.phase) {
            CalibrationVisualPhase.PREPARE -> PersonalCaptureSpec.PREPARE_MILLIS
            CalibrationVisualPhase.ACTIVE -> PersonalCaptureSpec.ACTIVE_MILLIS
            CalibrationVisualPhase.REST -> PersonalCaptureSpec.REST_MILLIS
        }
        val remaining = "  ·  ${((duration - elapsed / 1_000_000L).coerceAtLeast(0L) + 999) / 1000} s"
        drawFitted(canvas, "试次 $trial$remaining", width / 2f, height - dp(120f), width - dp(36f), sp(16f), Color.WHITE)
        drawFitted(canvas, sourceStatus, width / 2f, height - dp(94f), width - dp(36f), sp(11f), Color.rgb(152, 208, 180))
        drawFitted(
            canvas, "$profileName  ·  ${timing.reason}",
            width / 2f, height - dp(72f), width - dp(24f), sp(9f), Color.rgb(166, 179, 177),
        )
    }

    private fun drawStandbyVisual(canvas: Canvas) {
        fillPaint.color = Color.rgb(24, 32, 33)
        canvas.drawRoundRect(target, dp(6f), dp(6f), fillPaint)
        drawFitted(canvas, "个人脑电标定采集", width / 2f, dp(48f), width - dp(40f), sp(23f), Color.WHITE)
        drawFitted(
            canvas, "SSVEP / MI 模式就绪", target.centerX(), target.centerY() + sp(10f),
            target.width() - dp(24f), sp(22f), Color.rgb(180, 210, 210),
        )
        drawFitted(canvas, "点击下方“开始采集”启动", width / 2f, height - dp(120f), width - dp(36f), sp(16f), Color.WHITE)
        drawFitted(canvas, sourceStatus, width / 2f, height - dp(94f), width - dp(36f), sp(11f), Color.rgb(152, 208, 180))
        drawFitted(
            canvas, "$profileName  ·  ${timing.reason}",
            width / 2f, height - dp(72f), width - dp(24f), sp(9f), Color.rgb(166, 179, 177),
        )
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, size: Float, strokeWidth: Float, color: Int) {
        linePaint.color = color
        linePaint.strokeWidth = strokeWidth
        val half = size / 2f
        canvas.drawLine(cx - half, cy, cx + half, cy, linePaint)
        canvas.drawLine(cx, cy - half, cx, cy + half, linePaint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, pointingLeft: Boolean, color: Int) {
        arrowPaint.color = color
        arrowPath.reset()
        val half = size / 2f
        val headLength = size * 0.45f
        val shaftHalfWidth = size * 0.16f

        if (pointingLeft) {
            // 向左箭头
            arrowPath.moveTo(cx - half, cy)
            arrowPath.lineTo(cx - half + headLength, cy - half * 0.75f)
            arrowPath.lineTo(cx - half + headLength, cy - shaftHalfWidth)
            arrowPath.lineTo(cx + half, cy - shaftHalfWidth)
            arrowPath.lineTo(cx + half, cy + shaftHalfWidth)
            arrowPath.lineTo(cx - half + headLength, cy + shaftHalfWidth)
            arrowPath.lineTo(cx - half + headLength, cy + half * 0.75f)
            arrowPath.close()
        } else {
            // 向右箭头
            arrowPath.moveTo(cx + half, cy)
            arrowPath.lineTo(cx + half - headLength, cy - half * 0.75f)
            arrowPath.lineTo(cx + half - headLength, cy - shaftHalfWidth)
            arrowPath.lineTo(cx - half, cy - shaftHalfWidth)
            arrowPath.lineTo(cx - half, cy + shaftHalfWidth)
            arrowPath.lineTo(cx + half - headLength, cy + shaftHalfWidth)
            arrowPath.lineTo(cx + half - headLength, cy + half * 0.75f)
            arrowPath.close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
    }

    private fun squareWaveOn(elapsedNanos: Long): Boolean {
        val cycles = elapsedNanos / 1_000_000_000.0 * PersonalCaptureSpec.TARGET_FREQUENCY_HZ
        return cycles - kotlin.math.floor(cycles) < PersonalCaptureSpec.DUTY_CYCLE
    }

    private fun measureTiming(now: Long) {
        val refresh = display?.refreshRate?.coerceAtLeast(1f)?.toDouble() ?: 60.0
        if (timingStartNanos == 0L) timingStartNanos = now
        renderedFrames++
        if (lastFrameNanos != 0L) {
            droppedFrames += (round((now - lastFrameNanos) / (1_000_000_000.0 / refresh)).toLong() - 1L)
                .coerceAtLeast(0)
        }
        lastFrameNanos = now
        val duration = now - timingStartNanos
        if (duration >= 2_000_000_000L) {
            timing = timingEvaluator.evaluate(refresh, renderedFrames, droppedFrames, duration)
            timingStartNanos = now
            renderedFrames = 0L
            droppedFrames = 0L
        }
    }

    private fun resetTiming() {
        lastFrameNanos = 0L
        timingStartNanos = 0L
        renderedFrames = 0L
        droppedFrames = 0L
        timing = StimulusTimingStatus.measuring(display?.refreshRate?.toDouble() ?: 0.0)
    }

    private fun drawFitted(
        canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float, size: Float, color: Int,
    ) {
        textPaint.color = color
        textPaint.textSize = size
        while (textPaint.measureText(value) > maxWidth && textPaint.textSize > sp(8f)) {
            textPaint.textSize -= sp(0.5f)
        }
        canvas.drawText(value, x, y, textPaint)
    }

    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
}
