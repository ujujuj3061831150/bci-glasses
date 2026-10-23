package com.bci.glasses

object CaptureSessionOutcome {
    fun finalStatus(requestedStatus: String, failureReason: String?): String =
        if (requestedStatus == "complete" && failureReason == null) "complete" else "aborted"
}
