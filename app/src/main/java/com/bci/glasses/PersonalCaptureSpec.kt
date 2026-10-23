package com.bci.glasses

object PersonalCaptureSpec {
    const val TARGET_FREQUENCY_HZ = 11.0
    const val TRIAL_COUNT = 10
    const val PREPARE_MILLIS = 4_000L
    const val ACTIVE_MILLIS = 60_000L
    const val REST_MILLIS = 6_000L
    const val READINESS_WINDOW_MILLIS = 4_000L
    const val OUTPUT_SAMPLE_RATE_HZ = 250
    const val QUALITY_WINDOW_SAMPLES = OUTPUT_SAMPLE_RATE_HZ * READINESS_WINDOW_MILLIS / 1_000L
    const val TARGET_WIDTH_RATIO = 0.72
    const val TARGET_HEIGHT_RATIO = 0.62
    const val DUTY_CYCLE = 0.5

    val OCCIPITAL_MODEL_SLOTS: IntArray = intArrayOf(26, 27, 28, 29, 30)
}
