package com.bci.glasses

import android.os.Handler

/**
 * 运动想象实验事件监听接口。
 */
interface MiExperimentListener {
    fun onBlockStarted(block: MiBlock)
    fun onVisualState(state: MiVisualState, eventType: String? = null, eventCode: Int? = null)
    fun onTrialEnded(trial: MiTrial)
    fun onBlockFinished(block: MiBlock, hasNextBlock: Boolean)
}

/**
 * 运动想象实验运行调度器：精确控制 4 阶段时序调度
 * PREPARE (2.0s) -> CUE (1.25s) -> ACTIVE (3.75s) -> REST (2.0s)。
 */
class MiExperimentRunner(
    private val handler: Handler,
    plan: MiPlan,
    private val listener: MiExperimentListener,
) {
    private val allBlocks = listOfNotNull(plan.practiceBlock) + plan.formalBlocks
    private var blockIndex = -1
    private var trialIndex = 0
    private var running = false
    private var waitingForBlockConfirmation = false

    fun start() {
        check(!running) { "MI 实验调度器已在运行中" }
        running = true
        blockIndex = 0
        startCurrentBlock()
    }

    fun continueNextBlock() {
        if (!running || !waitingForBlockConfirmation) return
        waitingForBlockConfirmation = false
        blockIndex += 1
        startCurrentBlock()
    }

    fun cancel() {
        running = false
        waitingForBlockConfirmation = false
        handler.removeCallbacksAndMessages(this)
    }

    fun isRunning(): Boolean = running

    private fun startCurrentBlock() {
        if (!running || blockIndex !in allBlocks.indices) return
        trialIndex = 0
        val block = allBlocks[blockIndex]
        listener.onBlockStarted(block)
        startCurrentTrial()
    }

    private fun startCurrentTrial() {
        if (!running) return
        val block = allBlocks[blockIndex]
        if (trialIndex >= block.trials.size) {
            waitingForBlockConfirmation = blockIndex < allBlocks.lastIndex
            listener.onBlockFinished(block, waitingForBlockConfirmation)
            if (!waitingForBlockConfirmation) running = false
            return
        }
        val trial = block.trials[trialIndex]
        val totalTrials = block.trials.size

        // 阶段 1：PREPARE (2.0s) - 注视中心十字
        listener.onVisualState(
            visual(trial, MiVisualPhase.PREPARE, totalTrials),
            eventType = "PREPARE_ONSET",
            eventCode = MiCalibrationSpec.EventCodes.TRIAL_START,
        )
        post(MiCalibrationSpec.PREPARE_MILLIS) { enterCue(trial, totalTrials) }
    }

    private fun enterCue(trial: MiTrial, totalTrials: Int) {
        if (!running) return
        // 阶段 2：CUE (1.25s) - 左右箭头提示
        listener.onVisualState(
            visual(trial, MiVisualPhase.CUE, totalTrials),
            eventType = "CUE_ONSET",
            eventCode = trial.condition.cueCode,
        )
        post(MiCalibrationSpec.CUE_MILLIS) { enterActive(trial, totalTrials) }
    }

    private fun enterActive(trial: MiTrial, totalTrials: Int) {
        if (!running) return
        // 阶段 3：ACTIVE (3.75s) - 保持运动想象
        listener.onVisualState(
            visual(trial, MiVisualPhase.ACTIVE, totalTrials),
            eventType = "ACTIVE_ONSET",
            eventCode = trial.condition.activeCode,
        )
        post(MiCalibrationSpec.ACTIVE_MILLIS) { leaveActive(trial, totalTrials) }
    }

    private fun leaveActive(trial: MiTrial, totalTrials: Int) {
        if (!running) return
        // 阶段 4：REST (2.0s) - 放松休息
        listener.onVisualState(
            visual(trial, MiVisualPhase.REST, totalTrials),
            eventType = "ACTIVE_OFFSET",
            eventCode = trial.condition.offsetCode,
        )
        post(MiCalibrationSpec.REST_MILLIS) {
            if (!running) return@post
            listener.onVisualState(
                visual(trial, MiVisualPhase.REST, totalTrials),
                eventType = "REST_END",
                eventCode = MiCalibrationSpec.EventCodes.REST_END,
            )
            post(FRAME_DELIVERY_GRACE_MILLIS) {
                if (!running) return@post
                listener.onTrialEnded(trial)
                trialIndex += 1
                startCurrentTrial()
            }
        }
    }

    private fun visual(trial: MiTrial, phase: MiVisualPhase, totalTrials: Int) = MiVisualState(
        blockId = trial.blockId,
        trialId = trial.trialId,
        totalTrialsInBlock = totalTrials,
        condition = trial.condition,
        phase = phase,
        practice = trial.practice,
    )

    private fun post(delayMillis: Long, action: () -> Unit) {
        handler.postAtTime(Runnable(action), this, android.os.SystemClock.uptimeMillis() + delayMillis)
    }

    companion object {
        private const val FRAME_DELIVERY_GRACE_MILLIS = 50L
    }
}
