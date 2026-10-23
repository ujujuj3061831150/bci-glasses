package com.bci.glasses.stimulus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class StimulusSceneView @JvmOverloads constructor(
    context: Context,
    private val onCameraPoseChanged: (CameraPose) -> Unit = {},
) : View(context), Choreographer.FrameCallback {
    private enum class Mode { VISUAL, MOTOR_IMAGERY }
    private enum class MiCue(val title: String, val detail: String) {
        LEFT_HAND("左手", "左转"),
        RIGHT_HAND("右手", "右转"),
    }
    private enum class MiPhase { PREPARE, CUE, IMAGERY, FEEDBACK }

    private data class MiState(
        val cue: MiCue,
        val phase: MiPhase,
        val remainingSeconds: Double,
    )

    private data class PlaneProjection(
        val front: List<ScreenPoint2D>,
        val back: List<ScreenPoint2D>,
        val center: ScreenPoint2D,
        val bounds: RectF,
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.configuration.fontScale * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val preferences = context.getSharedPreferences("stimulus_display_layout", Context.MODE_PRIVATE)

    private val visualTabBounds = RectF()
    private val miTabBounds = RectF()
    private val placementBounds = RectF()
    private val resetBounds = RectF()
    private val playBounds = RectF()
    private val zMinusBounds = RectF()
    private val zPlusBounds = RectF()
    private val targetHitBounds = Array(StimulusGeometry.ssvepTargets.size) { RectF() }
    private val miHitBounds = Array(2) { RectF() }

    private val ssvepPositions = StimulusGeometry.ssvepTargets.mapIndexed { index, target ->
        loadPosition("ssvep", index, target.position)
    }.toMutableList()
    private val miPositions = mutableListOf(
        loadPosition("mi", 0, StimulusGeometry.leftHandCue),
        loadPosition("mi", 1, StimulusGeometry.rightHandCue),
    )

    private var mode = Mode.VISUAL
    private var running = false
    private var placementEnabled = false
    private var selectedIndex = -1
    private var startedAtNanos = 0L
    private var pausedElapsedNanos = 0L
    private var frameTimeNanos = 0L
    private var lastFrameNanos = 0L
    private var smoothedRefreshHz = 0.0
    private var frameCallbackActive = false
    private var sceneYawDegrees = 0f
    private var scenePitchDegrees = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moved = false
    private var draggingSelected = false
    private var downInControl = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!placementEnabled || selectedIndex < 0) return false
            val current = selectedPosition() ?: return false
            val nextZ = (current.z / detector.scaleFactor).coerceIn(MIN_TARGET_Z, MAX_TARGET_Z)
            updateSelectedPosition(current.copy(z = nextZ))
            moved = true
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            savePositions()
        }
    })

    init {
        isFocusable = true
        contentDescription = "BCI 智能眼镜刺激显示，视觉 SSVEP，已暂停"
        setBackgroundColor(Color.TRANSPARENT)
        onCameraPoseChanged(cameraPose())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!frameCallbackActive) {
            frameCallbackActive = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        frameCallbackActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!frameCallbackActive) return
        this.frameTimeNanos = frameTimeNanos
        if (lastFrameNanos > 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            if (delta in 1_000_000L..100_000_000L) {
                val instantHz = 1_000_000_000.0 / delta
                smoothedRefreshHz = if (smoothedRefreshHz == 0.0) instantHz else smoothedRefreshHz * 0.92 + instantHz * 0.08
            }
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSceneScrim(canvas)
        canvas.save()
        canvas.clipRect(0f, dp(72f), width.toFloat(), height.toFloat())
        when (mode) {
            Mode.VISUAL -> drawSsvepScene(canvas)
            Mode.MOTOR_IMAGERY -> drawMotorImageryScene(canvas)
        }
        drawFixation(canvas)
        canvas.restore()
        drawOpticalFrame(canvas)
        drawCoordinateAxes(canvas)
        drawToolbar(canvas)
        if (placementEnabled) drawPlacementBar(canvas)
    }

    fun showVisualMode() = setMode(Mode.VISUAL)

    fun showMotorImageryMode() = setMode(Mode.MOTOR_IMAGERY)

    fun toggleRunning() {
        if (placementEnabled) return
        val now = currentFrameTime()
        if (running) {
            pausedElapsedNanos = elapsedNanos(now)
            running = false
        } else {
            startedAtNanos = now - pausedElapsedNanos
            running = true
        }
        updateContentDescription()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                moved = false
                downInControl = isInControl(event.x, event.y)
                val hit = if (placementEnabled && !downInControl) findTargetAt(event.x, event.y) else -1
                if (hit >= 0) selectedIndex = hit
                draggingSelected = placementEnabled && hit >= 0
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !downInControl) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (kotlin.math.abs(event.x - downX) > dp(4f) || kotlin.math.abs(event.y - downY) > dp(4f)) moved = true
                    if (draggingSelected && selectedIndex >= 0) {
                        val current = selectedPosition()
                        if (current != null) {
                            val movedPoint = StimulusGeometry.moveInViewPlane(
                                current,
                                dx,
                                dy,
                                max(width, 1),
                                max(height, 1),
                                cameraPose(),
                            )
                            updateSelectedPosition(
                                movedPoint.copy(
                                    x = movedPoint.x.coerceIn(-2.25f, 2.25f),
                                    y = movedPoint.y.coerceIn(-1.25f, 1.25f),
                                    z = movedPoint.z.coerceIn(MIN_TARGET_Z, MAX_TARGET_Z),
                                ),
                            )
                        }
                    } else {
                        sceneYawDegrees = StimulusGeometry.normalizeYaw(sceneYawDegrees - dx * YAW_SENSITIVITY)
                        scenePitchDegrees = (scenePitchDegrees + dy * PITCH_SENSITIVITY).coerceIn(-MAX_PITCH, MAX_PITCH)
                        onCameraPoseChanged(cameraPose())
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) handleTap(event.x, event.y)
                if (draggingSelected) savePositions()
                draggingSelected = false
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> draggingSelected = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        when {
            visualTabBounds.contains(x, y) -> setMode(Mode.VISUAL)
            miTabBounds.contains(x, y) -> setMode(Mode.MOTOR_IMAGERY)
            placementBounds.contains(x, y) -> togglePlacement()
            resetBounds.contains(x, y) -> resetViewAndLayout()
            playBounds.contains(x, y) -> toggleRunning()
            placementEnabled && zMinusBounds.contains(x, y) -> adjustSelectedDepth(0.18f)
            placementEnabled && zPlusBounds.contains(x, y) -> adjustSelectedDepth(-0.18f)
            placementEnabled -> {
                val hit = findTargetAt(x, y)
                if (hit >= 0) selectedIndex = hit
                invalidate()
            }
        }
    }

    private fun isInControl(x: Float, y: Float): Boolean =
        visualTabBounds.contains(x, y) ||
            miTabBounds.contains(x, y) ||
            placementBounds.contains(x, y) ||
            resetBounds.contains(x, y) ||
            playBounds.contains(x, y) ||
            zMinusBounds.contains(x, y) ||
            zPlusBounds.contains(x, y)

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        running = false
        placementEnabled = false
        selectedIndex = -1
        startedAtNanos = 0L
        pausedElapsedNanos = 0L
        updateContentDescription()
        invalidate()
    }

    private fun togglePlacement() {
        placementEnabled = !placementEnabled
        running = false
        pausedElapsedNanos = 0L
        startedAtNanos = 0L
        selectedIndex = if (placementEnabled) 0 else -1
        updateContentDescription()
        invalidate()
    }

    private fun resetViewAndLayout() {
        sceneYawDegrees = 0f
        scenePitchDegrees = 0f
        onCameraPoseChanged(cameraPose())
        if (mode == Mode.VISUAL) {
            StimulusGeometry.ssvepTargets.forEachIndexed { index, target -> ssvepPositions[index] = target.position }
        } else {
            miPositions[0] = StimulusGeometry.leftHandCue
            miPositions[1] = StimulusGeometry.rightHandCue
        }
        savePositions()
        invalidate()
    }

    private fun drawSceneScrim(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (running) 82 else 96, 3, 8, 10)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.argb(145, 4, 10, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), dp(72f), paint)
    }

    private fun drawSsvepScene(canvas: Canvas) {
        val elapsed = elapsedNanos(currentFrameTime())
        StimulusGeometry.ssvepTargets.forEachIndexed { index, target ->
            val intensity = if (running) {
                StimulusTiming.sineBrightness(target.frequencyHz, target.phaseRadians, elapsed)
            } else {
                0.08f
            }
            val projection = projectPlane(
                ssvepPositions[index],
                StimulusGeometry.TARGET_WIDTH,
                StimulusGeometry.TARGET_HEIGHT,
            )
            if (projection == null) {
                targetHitBounds[index].setEmpty()
                return@forEachIndexed
            }
            targetHitBounds[index].set(projection.bounds)
            drawTargetPlane(
                canvas,
                projection,
                target.label,
                "${target.frequencyHz.toInt()} Hz",
                target.accentColor,
                intensity,
                placementEnabled && selectedIndex == index,
                ssvepPositions[index],
            )
        }
    }

    private fun drawMotorImageryScene(canvas: Canvas) {
        val state = if (running) currentMiState(elapsedNanos(currentFrameTime())) else null
        MiCue.entries.forEachIndexed { index, cue ->
            val active = state?.phase == MiPhase.CUE && state.cue == cue
            val projection = projectPlane(
                miPositions[index],
                StimulusGeometry.TARGET_WIDTH,
                StimulusGeometry.TARGET_HEIGHT,
            )
            if (projection == null) {
                miHitBounds[index].setEmpty()
                return@forEachIndexed
            }
            miHitBounds[index].set(projection.bounds)
            drawTargetPlane(
                canvas,
                projection,
                cue.title,
                cue.detail,
                cueColor(cue),
                if (active) 0.84f else 0.08f,
                placementEnabled && selectedIndex == index,
                miPositions[index],
            )
        }

        when (state?.phase) {
            MiPhase.PREPARE -> drawCenterStatus(canvas, "准备", "${state.remainingSeconds.coerceAtLeast(0.0).toInt() + 1}")
            MiPhase.CUE -> drawMiArrow(canvas, state.cue, state.remainingSeconds)
            MiPhase.IMAGERY -> Unit
            MiPhase.FEEDBACK -> drawCenterStatus(canvas, "✓ ${state.cue.title}", state.cue.detail)
            null -> if (!placementEnabled) drawCenterStatus(canvas, "待机", "MI")
        }
    }

    private fun drawTargetPlane(
        canvas: Canvas,
        projection: PlaneProjection,
        title: String,
        subtitle: String,
        accentColor: Int,
        intensity: Float,
        selected: Boolean,
        position: WorldPoint3D,
    ) {
        drawFace(canvas, projection.back, Color.argb(72, 2, 7, 9), Paint.Style.FILL)

        path.reset()
        path.moveTo(projection.front[1].x, projection.front[1].y)
        path.lineTo(projection.back[1].x, projection.back[1].y)
        path.lineTo(projection.back[2].x, projection.back[2].y)
        path.lineTo(projection.front[2].x, projection.front[2].y)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(92, 4, 10, 12)
        canvas.drawPath(path, paint)

        // The marker remains translucent while its luminance follows the PC-side SSVEP phase.
        val luminance = (42 + intensity * 213).toInt().coerceIn(0, 255)
        val alpha = (46 + intensity * 118).toInt().coerceIn(42, 164)
        val fillColor = Color.argb(alpha, luminance / 3, 104 + luminance / 3, luminance)
        drawFace(canvas, projection.front, fillColor, Paint.Style.FILL)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) dp(3f) else dp(2.4f)
        paint.color = if (selected) 0xFF65E0A3.toInt() else 0xFF2196F3.toInt()
        drawPath(canvas, projection.front, paint)

        if (selected) {
            paint.strokeWidth = dp(7f)
            paint.color = Color.argb(70, 101, 224, 163)
            drawPath(canvas, projection.front, paint)
        }

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(9.5f)
        val label = "$title ($subtitle)"
        val labelWidth = max(dp(96f), paint.measureText(label) + dp(18f))
        val labelTop = projection.bounds.bottom + dp(7f)
        val labelRect = RectF(
            projection.center.x - labelWidth * 0.5f,
            labelTop,
            projection.center.x + labelWidth * 0.5f,
            labelTop + dp(22f),
        )
        paint.color = if (selected) 0xE1319B70.toInt() else 0xE02180D4.toInt()
        canvas.drawRoundRect(labelRect, dp(3f), dp(3f), paint)
        paint.color = Color.WHITE
        canvas.drawText(label, labelRect.centerX(), labelRect.centerY() + dp(3.5f), paint)

        if (placementEnabled) {
            paint.typeface = Typeface.DEFAULT
            paint.textSize = sp(8f)
            paint.color = 0xFFF1F7F8.toInt()
            canvas.drawText(
                "X ${formatAxis(position.x)}  Y ${formatAxis(position.y)}  Z +${"%.2f".format(position.z)}",
                labelRect.centerX(),
                labelRect.bottom + dp(14f),
                paint,
            )
        }
    }

    private fun projectPlane(position: WorldPoint3D, planeWidth: Float, planeHeight: Float): PlaneProjection? {
        val halfW = planeWidth * 0.5f
        val halfH = planeHeight * 0.5f
        val frontWorld = listOf(
            WorldPoint3D(position.x - halfW, position.y + halfH, position.z),
            WorldPoint3D(position.x + halfW, position.y + halfH, position.z),
            WorldPoint3D(position.x + halfW, position.y - halfH, position.z),
            WorldPoint3D(position.x - halfW, position.y - halfH, position.z),
        )
        val backWorld = frontWorld.map { it.copy(z = it.z + 0.10f) }
        val front = frontWorld.map { project(it) ?: return null }
        val back = backWorld.map { project(it) ?: return null }
        val center = project(position) ?: return null
        val bounds = RectF(
            front.minOf { it.x },
            front.minOf { it.y },
            front.maxOf { it.x },
            front.maxOf { it.y },
        )
        return PlaneProjection(front, back, center, bounds)
    }

    private fun drawFace(canvas: Canvas, points: List<ScreenPoint2D>, color: Int, style: Paint.Style) {
        paint.style = style
        paint.color = color
        drawPath(canvas, points, paint)
    }

    private fun drawPath(canvas: Canvas, points: List<ScreenPoint2D>, targetPaint: Paint) {
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        for (index in 1 until points.size) path.lineTo(points[index].x, points[index].y)
        path.close()
        canvas.drawPath(path, targetPaint)
    }

    private fun drawMiArrow(canvas: Canvas, cue: MiCue, remainingSeconds: Double) {
        val position = miPositions[cue.ordinal]
        val center = project(position) ?: return
        val length = dp(72f)
        val direction = if (cue == MiCue.LEFT_HAND) -1f else 1f
        paint.style = Paint.Style.FILL
        paint.color = cueColor(cue)
        path.reset()
        path.moveTo(center.x + direction * length, center.y)
        path.lineTo(center.x + direction * length * 0.34f, center.y - length * 0.42f)
        path.lineTo(center.x + direction * length * 0.34f, center.y - length * 0.16f)
        path.lineTo(center.x - direction * length * 0.62f, center.y - length * 0.16f)
        path.lineTo(center.x - direction * length * 0.62f, center.y + length * 0.16f)
        path.lineTo(center.x + direction * length * 0.34f, center.y + length * 0.16f)
        path.lineTo(center.x + direction * length * 0.34f, center.y + length * 0.42f)
        path.close()
        canvas.drawPath(path, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(19f)
        paint.color = Color.WHITE
        canvas.drawText(cue.title, center.x, center.y + dp(92f), paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(10f)
        paint.color = 0xFFD0DDDF.toInt()
        canvas.drawText("${"%.2f".format(remainingSeconds)} s", center.x, center.y + dp(111f), paint)
    }

    private fun drawFixation(canvas: Canvas) {
        val center = project(StimulusGeometry.fixation) ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = 0xDFFFFFFF.toInt()
        canvas.drawLine(center.x - dp(8f), center.y, center.x + dp(8f), center.y, paint)
        canvas.drawLine(center.x, center.y - dp(8f), center.x, center.y + dp(8f), paint)
    }

    private fun drawOpticalFrame(canvas: Canvas) {
        val left = width * 0.055f
        val right = width * 0.945f
        val top = height * 0.105f
        val bottom = height * 0.93f
        val length = min(width, height) * 0.045f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.argb(105, 188, 219, 216)
        canvas.drawLine(left, top, left + length, top, paint)
        canvas.drawLine(left, top, left, top + length, paint)
        canvas.drawLine(right, top, right - length, top, paint)
        canvas.drawLine(right, top, right, top + length, paint)
        canvas.drawLine(left, bottom, left + length, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - length, paint)
        canvas.drawLine(right, bottom, right - length, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - length, paint)
    }

    private fun drawCoordinateAxes(canvas: Canvas) {
        val originX = dp(38f)
        val originY = height - dp(34f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = 0xFFE76F68.toInt()
        canvas.drawLine(originX, originY, originX + dp(34f), originY, paint)
        paint.color = 0xFF68C987.toInt()
        canvas.drawLine(originX, originY, originX, originY - dp(34f), paint)
        paint.color = 0xFF63B7D7.toInt()
        canvas.drawLine(originX, originY, originX + dp(22f), originY - dp(22f), paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(9f)
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFE76F68.toInt()
        canvas.drawText("+X", originX + dp(43f), originY + dp(4f), paint)
        paint.color = 0xFF68C987.toInt()
        canvas.drawText("+Y", originX, originY - dp(43f), paint)
        paint.color = 0xFF63B7D7.toInt()
        canvas.drawText("+Z", originX + dp(30f), originY - dp(29f), paint)
    }

    private fun drawToolbar(canvas: Canvas) {
        val top = dp(13f)
        val heightPx = dp(44f)
        val left = dp(16f)
        val tabWidth = dp(128f)
        visualTabBounds.set(left, top, left + tabWidth, top + heightPx)
        miTabBounds.set(left + tabWidth + dp(6f), top, left + tabWidth * 2f + dp(6f), top + heightPx)
        playBounds.set(width - dp(60f), top, width - dp(16f), top + heightPx)
        resetBounds.set(playBounds.left - dp(62f), top, playBounds.left - dp(6f), top + heightPx)
        placementBounds.set(resetBounds.left - dp(76f), top, resetBounds.left - dp(6f), top + heightPx)

        drawButton(canvas, visualTabBounds, "视觉 SSVEP", mode == Mode.VISUAL)
        drawButton(canvas, miTabBounds, "运动想象 MI", mode == Mode.MOTOR_IMAGERY)
        drawButton(canvas, placementBounds, "布局", placementEnabled)
        drawButton(canvas, resetBounds, "复位", false)
        drawPlayButton(canvas)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(9.5f)
        paint.color = 0xFFB7C7CB.toInt()
        val refresh = if (smoothedRefreshHz > 0.0) "${smoothedRefreshHz.toInt()} Hz" else "-- Hz"
        canvas.drawText(
            "$refresh  ·  Y ${sceneYawDegrees.toInt()}°  P ${scenePitchDegrees.toInt()}°",
            placementBounds.left - dp(12f),
            top + heightPx * 0.60f,
            paint,
        )
    }

    private fun drawButton(canvas: Canvas, bounds: RectF, label: String, selected: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (selected) 0xD92A4147.toInt() else 0xA80A1317.toInt()
        canvas.drawRoundRect(bounds, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) dp(2f) else dp(1f)
        paint.color = if (selected) 0xFF66D9CE.toInt() else 0xFF405359.toInt()
        canvas.drawRoundRect(bounds, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = sp(11.5f)
        paint.color = if (selected) Color.WHITE else 0xFFB1C0C4.toInt()
        canvas.drawText(label, bounds.centerX(), bounds.centerY() + dp(4f), paint)
    }

    private fun drawPlayButton(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = if (running) 0xD923343A.toInt() else 0xFF66D9CE.toInt()
        canvas.drawRoundRect(playBounds, dp(4f), dp(4f), paint)
        paint.color = if (running) Color.WHITE else 0xFF061014.toInt()
        if (running) {
            canvas.drawRect(playBounds.centerX() - dp(7f), playBounds.centerY() - dp(10f), playBounds.centerX() - dp(2f), playBounds.centerY() + dp(10f), paint)
            canvas.drawRect(playBounds.centerX() + dp(2f), playBounds.centerY() - dp(10f), playBounds.centerX() + dp(7f), playBounds.centerY() + dp(10f), paint)
        } else {
            path.reset()
            path.moveTo(playBounds.centerX() - dp(6f), playBounds.centerY() - dp(11f))
            path.lineTo(playBounds.centerX() + dp(11f), playBounds.centerY())
            path.lineTo(playBounds.centerX() - dp(6f), playBounds.centerY() + dp(11f))
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawPlacementBar(canvas: Canvas) {
        val selected = selectedPosition()
        val barWidth = min(width * 0.58f, dp(520f))
        val barHeight = dp(48f)
        val left = width * 0.5f - barWidth * 0.5f
        val top = height - dp(62f)
        val bar = RectF(left, top, left + barWidth, top + barHeight)
        paint.style = Paint.Style.FILL
        paint.color = 0xD5081115.toInt()
        canvas.drawRoundRect(bar, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0xFF4F6A70.toInt()
        canvas.drawRoundRect(bar, dp(4f), dp(4f), paint)

        zMinusBounds.set(bar.right - dp(92f), bar.top + dp(7f), bar.right - dp(52f), bar.bottom - dp(7f))
        zPlusBounds.set(bar.right - dp(46f), bar.top + dp(7f), bar.right - dp(6f), bar.bottom - dp(7f))
        drawButton(canvas, zMinusBounds, "Z−", false)
        drawButton(canvas, zPlusBounds, "Z+", false)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(10.5f)
        paint.color = 0xFFE8EFEF.toInt()
        val name = selectedItemName()
        canvas.drawText(name, bar.left + dp(12f), bar.top + dp(19f), paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(9f)
        paint.color = 0xFFADC0C5.toInt()
        val coordinate = selected?.let { "X ${formatAxis(it.x)}   Y ${formatAxis(it.y)}   Z +${"%.2f".format(it.z)}" } ?: "请选择刺激块"
        canvas.drawText(coordinate, bar.left + dp(12f), bar.top + dp(37f), paint)
    }

    private fun drawCenterStatus(canvas: Canvas, primary: String, secondary: String) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(23f)
        paint.color = 0xFFF0F5F5.toInt()
        canvas.drawText(primary, width * 0.5f, height * 0.51f - dp(5f), paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(10f)
        paint.color = 0xFFB5C5C9.toInt()
        canvas.drawText(secondary, width * 0.5f, height * 0.51f + dp(18f), paint)
    }

    private fun currentMiState(elapsedNanos: Long): MiState {
        val trialIndex = StimulusTiming.miTrialIndex(elapsedNanos)
        val offset = StimulusTiming.miTrialOffset(elapsedNanos)
        val cue = MiCue.entries[(trialIndex % MiCue.entries.size).toInt()]
        return when {
            offset < StimulusTiming.PREPARE_NANOS -> MiState(
                cue,
                MiPhase.PREPARE,
                (StimulusTiming.PREPARE_NANOS - offset) / 1_000_000_000.0,
            )
            offset < StimulusTiming.PREPARE_NANOS + StimulusTiming.CUE_NANOS -> MiState(
                cue,
                MiPhase.CUE,
                (StimulusTiming.PREPARE_NANOS + StimulusTiming.CUE_NANOS - offset) / 1_000_000_000.0,
            )
            offset < StimulusTiming.PREPARE_NANOS + StimulusTiming.CUE_NANOS + StimulusTiming.IMAGERY_NANOS -> MiState(
                cue,
                MiPhase.IMAGERY,
                (StimulusTiming.PREPARE_NANOS + StimulusTiming.CUE_NANOS + StimulusTiming.IMAGERY_NANOS - offset) / 1_000_000_000.0,
            )
            else -> MiState(
                cue,
                MiPhase.FEEDBACK,
                (StimulusTiming.MI_TRIAL_NANOS - offset) / 1_000_000_000.0,
            )
        }
    }

    private fun findTargetAt(x: Float, y: Float): Int {
        val bounds = if (mode == Mode.VISUAL) targetHitBounds else miHitBounds
        return bounds.indices.reversed().firstOrNull { bounds[it].contains(x, y) } ?: -1
    }

    private fun selectedPosition(): WorldPoint3D? = when (mode) {
        Mode.VISUAL -> ssvepPositions.getOrNull(selectedIndex)
        Mode.MOTOR_IMAGERY -> miPositions.getOrNull(selectedIndex)
    }

    private fun updateSelectedPosition(point: WorldPoint3D) {
        when (mode) {
            Mode.VISUAL -> if (selectedIndex in ssvepPositions.indices) ssvepPositions[selectedIndex] = point
            Mode.MOTOR_IMAGERY -> if (selectedIndex in miPositions.indices) miPositions[selectedIndex] = point
        }
    }

    private fun adjustSelectedDepth(delta: Float) {
        val current = selectedPosition() ?: return
        updateSelectedPosition(current.copy(z = (current.z + delta).coerceIn(MIN_TARGET_Z, MAX_TARGET_Z)))
        savePositions()
        invalidate()
    }

    private fun selectedItemName(): String = when (mode) {
        Mode.VISUAL -> StimulusGeometry.ssvepTargets.getOrNull(selectedIndex)?.let { "${it.label} · ${it.frequencyHz.toInt()} Hz" } ?: "未选择"
        Mode.MOTOR_IMAGERY -> MiCue.entries.getOrNull(selectedIndex)?.let { "${it.title} · ${it.detail}" } ?: "未选择"
    }

    private fun loadPosition(prefix: String, index: Int, default: WorldPoint3D): WorldPoint3D = WorldPoint3D(
        preferences.getFloat("${prefix}_${index}_x", default.x),
        preferences.getFloat("${prefix}_${index}_y", default.y),
        preferences.getFloat("${prefix}_${index}_z", default.z),
    )

    private fun savePositions() {
        preferences.edit().apply {
            ssvepPositions.forEachIndexed { index, point ->
                putFloat("ssvep_${index}_x", point.x)
                putFloat("ssvep_${index}_y", point.y)
                putFloat("ssvep_${index}_z", point.z)
            }
            miPositions.forEachIndexed { index, point ->
                putFloat("mi_${index}_x", point.x)
                putFloat("mi_${index}_y", point.y)
                putFloat("mi_${index}_z", point.z)
            }
        }.apply()
    }

    private fun cameraPose(): CameraPose = CameraPose(sceneYawDegrees, scenePitchDegrees)

    private fun project(point: WorldPoint3D): ScreenPoint2D? =
        StimulusGeometry.projectOrNull(point, max(width, 1), max(height, 1), cameraPose())

    private fun elapsedNanos(now: Long): Long = when {
        running && startedAtNanos > 0L -> (now - startedAtNanos).coerceAtLeast(0L)
        else -> pausedElapsedNanos
    }

    private fun currentFrameTime(): Long = frameTimeNanos.takeIf { it > 0L } ?: System.nanoTime()

    private fun cueColor(cue: MiCue): Int = if (cue == MiCue.LEFT_HAND) 0xFF4EB6E6.toInt() else 0xFFF1A94B.toInt()

    private fun updateContentDescription() {
        val modeLabel = if (mode == Mode.VISUAL) "视觉 SSVEP" else "运动想象 MI"
        val state = when {
            placementEnabled -> "布局编辑"
            running -> "运行中"
            else -> "已暂停"
        }
        contentDescription = "BCI 智能眼镜刺激显示，$modeLabel，$state"
    }

    private fun formatAxis(value: Float): String = if (value >= 0f) "+${"%.2f".format(value)}" else "%.2f".format(value)
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * scaledDensity

    companion object {
        private const val MAX_PITCH = 85f
        private const val YAW_SENSITIVITY = 0.12f
        private const val PITCH_SENSITIVITY = 0.10f
        private const val MIN_TARGET_Z = 1.8f
        private const val MAX_TARGET_Z = 4.8f
    }
}
