package com.bci.glasses

import android.os.Handler

interface CalibrationExperimentListener {
    fun onBlockStarted(block: CalibrationBlock)
    fun onVisualState(
        state: CalibrationVisualState,
        eventType: String? = null,
        eventCode: Int? = null,
    )
    fun onTrialEnded(trial: CalibrationTrial)
    fun onBlockFinished(block: CalibrationBlock, hasNextBlock: Boolean)
}

class CalibrationExperimentRunner(
    private val handler: Handler,
    plan: CalibrationPlan,
    private val listener: CalibrationExperimentListener,
) {
    private val blocks = plan.formalBlocks
    private var blockIndex = -1
    private var trialIndex = 0
    private var running = false
    private var waitingForBlockConfirmation = false

    fun start() {
        check(!running) { "Calibration runner is already active" }
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
        if (!running || blockIndex !in blocks.indices) return
        trialIndex = 0
        val block = blocks[blockIndex]
        listener.onBlockStarted(block)
        startCurrentTrial()
    }

    private fun startCurrentTrial() {
        if (!running) return
        val block = blocks[blockIndex]
        if (trialIndex >= block.trials.size) {
            waitingForBlockConfirmation = blockIndex < blocks.lastIndex
            listener.onBlockFinished(block, waitingForBlockConfirmation)
            if (!waitingForBlockConfirmation) running = false
            return
        }
        val trial = block.trials[trialIndex]
        listener.onVisualState(
            visual(trial, CalibrationVisualPhase.PREPARE, block.trials.size),
            eventType = "PREPARE_ONSET",
            eventCode = trial.condition.cueCode,
        )
        post(trial.baselineMillis) { enterActive(trial, block.trials.size) }
    }

    private fun enterActive(trial: CalibrationTrial, totalTrials: Int) {
        if (!running) return
        listener.onVisualState(
            visual(trial, CalibrationVisualPhase.ACTIVE, totalTrials),
            eventType = "ACTIVE_ONSET",
            eventCode = trial.condition.activeCode,
        )
        post(CalibrationPlanFactory.ACTIVE_MILLIS) { leaveActive(trial, totalTrials) }
    }

    private fun leaveActive(trial: CalibrationTrial, totalTrials: Int) {
        if (!running) return
        listener.onVisualState(
            visual(trial, CalibrationVisualPhase.REST, totalTrials),
            eventType = "ACTIVE_OFFSET",
            eventCode = trial.condition.offsetCode,
        )
        post(CalibrationPlanFactory.REST_MILLIS) {
            if (!running) return@post
            listener.onVisualState(
                visual(trial, CalibrationVisualPhase.REST, totalTrials),
                eventType = "REST_END",
                eventCode = CalibrationEventCodes.REST_END,
            )
            post(FRAME_DELIVERY_GRACE_MILLIS) {
                if (!running) return@post
                listener.onTrialEnded(trial)
                trialIndex += 1
                startCurrentTrial()
            }
        }
    }

    private fun visual(
        trial: CalibrationTrial,
        phase: CalibrationVisualPhase,
        totalTrials: Int,
    ): CalibrationVisualState = CalibrationVisualState(
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

    private companion object {
        const val FRAME_DELIVERY_GRACE_MILLIS = 100L
    }
}
