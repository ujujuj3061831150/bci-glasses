package com.bci.glasses.stimulus

import kotlin.math.floor
import kotlin.math.sin

object StimulusTiming {
    const val PREPARE_NANOS = 2_000_000_000L
    const val CUE_NANOS = 1_250_000_000L
    const val IMAGERY_NANOS = 2_750_000_000L
    const val FEEDBACK_NANOS = 1_000_000_000L
    const val MI_TRIAL_NANOS = PREPARE_NANOS + CUE_NANOS + IMAGERY_NANOS + FEEDBACK_NANOS

    fun squareWaveOn(frequencyHz: Double, elapsedNanos: Long, dutyCycle: Double = 0.5): Boolean {
        require(frequencyHz > 0.0) { "Frequency must be positive" }
        require(dutyCycle > 0.0 && dutyCycle < 1.0) { "Duty cycle must be between 0 and 1" }
        val cycles = elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0 * frequencyHz
        val phase = cycles - floor(cycles)
        return phase < dutyCycle
    }

    fun sineBrightness(frequencyHz: Double, phaseRadians: Double, elapsedNanos: Long): Float {
        require(frequencyHz > 0.0) { "Frequency must be positive" }
        val seconds = elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0
        return (0.5 * (1.0 + sin(2.0 * Math.PI * frequencyHz * seconds + phaseRadians)))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun miTrialIndex(elapsedNanos: Long): Long = elapsedNanos.coerceAtLeast(0L) / MI_TRIAL_NANOS

    fun miTrialOffset(elapsedNanos: Long): Long = elapsedNanos.coerceAtLeast(0L) % MI_TRIAL_NANOS
}
