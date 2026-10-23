package com.bci.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSessionOutcomeTest {
    @Test
    fun completedCaptureOnlySucceedsWithoutWriterFailure() {
        assertEquals("complete", CaptureSessionOutcome.finalStatus("complete", null))
        assertEquals("aborted", CaptureSessionOutcome.finalStatus("complete", "写盘失败"))
    }

    @Test
    fun userStopAndStreamFailureRemainIncomplete() {
        assertEquals("aborted", CaptureSessionOutcome.finalStatus("aborted", "用户主动停止"))
        assertEquals("aborted", CaptureSessionOutcome.finalStatus("aborted", "TCP数据流中断"))
    }
}
