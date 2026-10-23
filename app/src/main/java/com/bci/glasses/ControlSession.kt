package com.bci.glasses

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ControlPhase { STOPPED, NAVIGATION, CANDIDATES, EXECUTING, INTERRUPTED }

data class ControlDirection(val index: Int, val label: String, val frequencyHz: Double, val phaseRadians: Double) {
    val angleRadians: Double get() = index * PI / 4.0
    val dx: Double get() = cos(angleRadians)
    val dy: Double get() = sin(angleRadians)
}

object ControlDirections {
    private val frequencies = doubleArrayOf(60.0 / 7.0, 12.0, 10.0, 15.0)
    val all = listOf("右", "右上", "上", "左上", "左", "左下", "下", "右下").mapIndexed { i, label ->
        ControlDirection(i, label, frequencies[i % 4], if (i >= 4) PI else 0.0)
    }
    fun neighbors(center: Int): List<Int> = listOf((center + 7) % 8, center, (center + 1) % 8)
    fun indexAt(x: Double, y: Double): Int = ((atan2(y, x) / (PI / 4.0)).roundToInt() + 8) % 8
    fun angularDistance(a: Double, b: Double): Double = abs(atan2(sin(a - b), cos(a - b)))
}

/** Normalized target-plane coordinates, +x right, +y up; timestamp is local monotonic milliseconds.
 * A vendor adapter must explicitly transform its coordinates and clock before using this contract.
 */
data class ControlGazeSample(
    val x: Double,
    val y: Double,
    val observedAtMillis: Long,
    val valid: Boolean = true,
    val confidence: Double? = null,
    val source: String = "SIMULATED_POINTER",
)

data class ControlConfig(
    val entryDwellMillis: Long = 400,
    val switchDwellMillis: Long = 500,
    val sampleMaxAgeMillis: Long = 250,
    val lossGraceMillis: Long = 600,
    val normalExitDwellMillis: Long = 400,
    val schedulingTimeoutMillis: Long = 1_000,
    val groupMarginDegrees: Double = 8.0,
    val minConfidence: Double = 0.55,
    val movementPerSecond: Double = 0.08,
)

data class ControlEvent(
    val atMillis: Long,
    val type: String,
    val phase: ControlPhase,
    val candidateVersion: Long,
    val detail: String,
)

/** Pure control logic. This prototype injects selection/confirmation events; it does not decode EEG. */
class ControlSession(
    val config: ControlConfig = ControlConfig(),
    private val onEvent: (ControlEvent) -> Unit = {},
) {
    var phase = ControlPhase.STOPPED; private set
    var candidates: List<Int> = emptyList(); private set
    var candidateVersion = 0L; private set
    var selectedDirection: Int? = null; private set
    var gaze: ControlGazeSample? = null; private set
    var attentionLow = false; private set
    var objectTracked = true; private set
    var layoutValid = true; private set
    var positionX = 0.0; private set
    var positionY = 0.0; private set
    var status = "尚未启动 · 原生眼动与脑电未连接"; private set
    var pendingDirection: Int? = null; private set
    private var pendingSince: Long? = null
    private var invalidSince: Long? = null
    private var exitSince: Long? = null
    private var lastTick: Long? = null

    fun start(now: Long) {
        if (!objectTracked || !layoutValid) {
            status = "请先恢复对象跟踪与有效显示范围"
            emit(now, "START_REJECTED", status)
            return
        }
        if (phase == ControlPhase.EXECUTING) stop(now, "重新开始，已停止虚拟动作")
        selectedDirection = null
        gaze = null
        attentionLow = false
        invalidSince = now
        exitSince = null
        lastTick = now
        navigation(now, "导航：将模拟视线移到方向闪块，稳定后保留三候选")
        emit(now, "SESSION_STARTED", "object=virtual-arm-01; input=SIMULATED; no device commands")
    }

    fun stop(now: Long, reason: String = "手动停止") {
        stopMovement(now, reason)
        phase = ControlPhase.STOPPED
        clearCandidates()
        status = reason
        emit(now, "SESSION_STOPPED", reason)
    }

    fun resetPosition(now: Long) {
        stop(now, "已停止并重置虚拟末端")
        positionX = 0.0
        positionY = 0.0
        emit(now, "POSITION_RESET", "x=0;y=0")
    }

    fun setAttentionLow(value: Boolean, now: Long) {
        attentionLow = value
        exitSince = null
        emit(now, "SIMULATED_ATTENTION", if (value) "low" else "normal")
    }

    fun setObjectTracked(value: Boolean, now: Long) {
        objectTracked = value
        emit(now, "OBJECT_TRACKING", if (value) "tracked" else "lost")
        if (!value && active()) interrupt(now, "对象跟踪丢失，已停止；恢复后需重新开始")
    }

    fun setLayoutValid(value: Boolean, now: Long) {
        if (layoutValid == value) return
        layoutValid = value
        emit(now, "LAYOUT_VALIDITY", value.toString())
        if (!value && active()) interrupt(now, "目标超出有效显示范围，已停止")
    }

    fun invalidateLayout(now: Long) {
        if (active()) interrupt(now, "显示布局已改变，旧选择已失效，请重新开始")
    }

    fun observe(sample: ControlGazeSample, now: Long) {
        val previous = gaze
        if (previous != null && sample.observedAtMillis < previous.observedAtMillis) {
            emit(now, "GAZE_REJECTED", "out_of_order")
            return
        }
        gaze = sample
    }

    fun gazeUsable(now: Long): Boolean {
        val sample = gaze ?: return false
        return sample.valid && sample.x.isFinite() && sample.y.isFinite() &&
            sample.observedAtMillis <= now && now - sample.observedAtMillis <= config.sampleMaxAgeMillis &&
            (sample.confidence == null || (sample.confidence.isFinite() && sample.confidence in config.minConfidence..1.0))
    }

    fun hoveredDirection(now: Long): Int? {
        if (!gazeUsable(now)) return null
        val sample = gaze ?: return null
        if (hypot(sample.x, sample.y) !in 0.60..1.38) return null
        return ControlDirections.indexAt(sample.x, sample.y)
    }

    fun dwellProgress(now: Long): Double {
        val since = pendingSince ?: return 0.0
        val duration = if (phase == ControlPhase.CANDIDATES) config.switchDwellMillis else config.entryDwellMillis
        return ((now - since).toDouble() / duration).coerceIn(0.0, 1.0)
    }

    fun injectSelectionAndConfirmation(direction: Int, version: Long, now: Long): Boolean {
        val reason = when {
            phase != ControlPhase.CANDIDATES -> "not_in_candidates"
            version != candidateVersion -> "stale_candidate_version"
            direction !in candidates -> "direction_not_in_candidates"
            !objectTracked || !layoutValid -> "object_or_layout_invalid"
            !gazeUsable(now) || hoveredDirection(now) != direction -> "gaze_not_on_current_candidate"
            attentionLow -> "simulated_attention_low"
            else -> null
        }
        if (reason != null) {
            emit(now, "CONFIRMATION_REJECTED", reason)
            return false
        }
        selectedDirection = direction
        phase = ControlPhase.EXECUTING
        resetPending()
        exitSince = null
        status = "模拟执行：${ControlDirections.all[direction].label} · 仅驱动屏幕内虚拟末端"
        emit(now, "SIMULATED_SELECTION_AND_CONFIRMATION", "direction=$direction; source=MANUAL_EVENT_INJECTION; no EEG decoder")
        emit(now, "MOVEMENT_STARTED", "virtual-only; direction=$direction")
        lastTick = now
        return true
    }

    fun tick(now: Long) {
        if (!active()) { lastTick = now; return }
        val previousTick = lastTick
        lastTick = now
        if (previousTick != null && (now < previousTick || now - previousTick > config.schedulingTimeoutMillis)) {
            interrupt(now, "调度中断，已停止；需重新开始")
            return
        }
        if (!gazeUsable(now)) {
            if (invalidSince == null) {
                invalidSince = now
                emit(now, "GAZE_UNAVAILABLE", "invalid_or_stale; selection suspended")
            }
            resetPending()
            exitSince = null // Invalid gaze is not evidence of looking away.
            if (now - (invalidSince ?: now) >= config.lossGraceMillis) {
                interrupt(now, "眼动持续无效或过期，已停止；恢复后需重新开始")
            }
            return // Freeze virtual motion immediately while gaze is unavailable.
        }
        if (invalidSince != null) emit(now, "GAZE_RECOVERED", "valid")
        invalidSince = null

        if (phase == ControlPhase.EXECUTING) {
            val sample = gaze ?: return
            val selected = selectedDirection ?: return
            val angle = atan2(sample.y, sample.x)
            val nearSelected = hypot(sample.x, sample.y) in 0.55..1.45 &&
                ControlDirections.angularDistance(angle, ControlDirections.all[selected].angleRadians) <= PI / 4.0
            if (attentionLow && !nearSelected) {
                if (exitSince == null) exitSince = now
                if (now - (exitSince ?: now) >= config.normalExitDwellMillis) {
                    stopMovement(now, "注意力下降且有效视线移开")
                    navigation(now, "正常退出：注意力下降且视线移开，已恢复八方向")
                    emit(now, "NORMAL_EXIT", "attention_low AND gaze_away")
                    return
                }
            } else exitSince = null
            val dt = ((now - (previousTick ?: now)) / 1000.0).coerceIn(0.0, 0.1)
            val direction = ControlDirections.all[selected]
            val nextX = positionX + direction.dx * config.movementPerSecond * dt
            val nextY = positionY + direction.dy * config.movementPerSecond * dt
            positionX = nextX.coerceIn(-0.78, 0.78)
            positionY = nextY.coerceIn(-0.78, 0.78)
            if (nextX != positionX || nextY != positionY) interrupt(now, "到达虚拟工作区边界，已停止")
            return
        }

        val index = hoveredDirection(now)
        if (index == null) { resetPending(); return }
        if (phase == ControlPhase.CANDIDATES) {
            val angle = atan2(gaze!!.y, gaze!!.x)
            val margin = config.groupMarginDegrees * PI / 180.0
            if (candidates.any { ControlDirections.angularDistance(angle, ControlDirections.all[it].angleRadians) <= PI / 8.0 + margin }) {
                resetPending()
                return
            }
        }
        if (pendingDirection != index) {
            pendingDirection = index
            pendingSince = now
        }
        val dwell = if (phase == ControlPhase.CANDIDATES) config.switchDwellMillis else config.entryDwellMillis
        if (now - (pendingSince ?: now) >= dwell) {
            candidates = ControlDirections.neighbors(index)
            candidateVersion++
            phase = ControlPhase.CANDIDATES
            status = "三候选：${candidates.joinToString(" / ") { ControlDirections.all[it].label }} · 可组内精选择"
            resetPending()
            emit(now, "CANDIDATES_ACTIVATED", "indices=${candidates.joinToString(",")}; decoder window reset required")
        }
    }

    private fun active() = phase in listOf(ControlPhase.NAVIGATION, ControlPhase.CANDIDATES, ControlPhase.EXECUTING)
    private fun resetPending() { pendingDirection = null; pendingSince = null }
    private fun clearCandidates() { candidates = emptyList(); selectedDirection = null; candidateVersion++; resetPending(); exitSince = null }
    private fun navigation(now: Long, reason: String) {
        clearCandidates()
        phase = ControlPhase.NAVIGATION
        status = reason
        emit(now, "NAVIGATION_ENTERED", reason)
    }
    private fun stopMovement(now: Long, reason: String) {
        if (phase == ControlPhase.EXECUTING) emit(now, "MOVEMENT_STOPPED", "$reason; virtual-feedback=stopped")
    }
    private fun interrupt(now: Long, reason: String) {
        stopMovement(now, reason)
        phase = ControlPhase.INTERRUPTED
        clearCandidates()
        status = reason
        emit(now, "CONTROL_INTERRUPTED", reason)
    }
    private fun emit(now: Long, type: String, detail: String) = onEvent(ControlEvent(now, type, phase, candidateVersion, detail))
}
