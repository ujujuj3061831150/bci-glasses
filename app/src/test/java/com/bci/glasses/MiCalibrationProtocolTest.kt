package com.bci.glasses

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiCalibrationProtocolTest {
    @Test
    fun planFactoryCreatesBalancedTrialSequence() {
        val plan = MiPlanFactory.create(seed = 12345L, blockCount = 2, trialsPerBlock = 20)
        assertEquals(2, plan.formalBlocks.size)
        assertEquals(40, plan.totalFormalTrials)
        assertNull(plan.practiceBlock)

        for (block in plan.formalBlocks) {
            assertEquals(20, block.trials.size)
            val leftCount = block.trials.count { it.condition == MiCondition.LEFT_HAND }
            val rightCount = block.trials.count { it.condition == MiCondition.RIGHT_HAND }
            assertEquals(10, leftCount)
            assertEquals(10, rightCount)
            assertEquals((1..20).toList(), block.trials.map { it.trialId })
            assertTrue(block.trials.none { it.practice })
        }
    }

    @Test
    fun practiceBlockIsProperlyBalanced() {
        val plan = MiPlanFactory.create(seed = 54321L, withPractice = true)
        val practice = plan.practiceBlock
        assertNotNull(practice)
        assertEquals(4, practice!!.trials.size)
        assertEquals(2, practice.trials.count { it.condition == MiCondition.LEFT_HAND })
        assertEquals(2, practice.trials.count { it.condition == MiCondition.RIGHT_HAND })
        assertTrue(practice.trials.all { it.practice })
    }

    @Test
    fun sameSeedProducesIdenticalMiPlan() {
        val plan1 = MiPlanFactory.create(42L)
        val plan2 = MiPlanFactory.create(42L)
        assertEquals(plan1, plan2)
    }

    @Test
    fun eventCodesAndLabelsAlignWithBci2a() {
        assertEquals(0, MiCondition.LEFT_HAND.label)
        assertEquals(1, MiCondition.RIGHT_HAND.label)

        assertEquals(768, MiCalibrationSpec.EventCodes.TRIAL_START)
        assertEquals(769, MiCondition.LEFT_HAND.cueCode)
        assertEquals(770, MiCondition.RIGHT_HAND.cueCode)
        assertEquals(101, MiCondition.LEFT_HAND.activeCode)
        assertEquals(102, MiCondition.RIGHT_HAND.activeCode)
        assertEquals(201, MiCondition.LEFT_HAND.offsetCode)
        assertEquals(201, MiCondition.RIGHT_HAND.offsetCode)
        assertEquals(202, MiCalibrationSpec.EventCodes.REST_END)

        // 运动皮层槽位 C3=12, Cz=13, C4=14
        assertEquals(listOf(12, 13, 14), MiCalibrationSpec.MOTOR_MODEL_SLOTS.toList())
    }

    @Test
    fun profileCompatibilityRejects8ChannelAndAccepts32Channel() {
        val thirtyTwoCap = DeviceProfilePresets.neuracleRecorder1000()
        val eightSsvep = DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()

        // 32通道包含 C3/Cz/C4，应当兼容
        assertNull(MiCalibrationSpec.incompatibilityReason(thirtyTwoCap))

        // 8通道仅包含枕区电极，缺少运动区，应当明确拒绝
        val rejectReason = MiCalibrationSpec.incompatibilityReason(eightSsvep)
        assertNotNull(rejectReason)
        assertTrue(rejectReason!!.contains("缺少运动皮层核心通道"))
    }

    @Test
    fun captureArchiveAcceptsMiSessionDirectory() {
        val dir = File.createTempFile("calibration_mi_", "").apply {
            delete()
            mkdir()
        }
        try {
            CaptureArchive.requiredFiles.forEach { name -> File(dir, name).writeText(name) }
            val output = ByteArrayOutputStream()
            CaptureArchive.write(dir, output)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().decodeToString()
                    zip.closeEntry()
                }
            }
            assertEquals(
                CaptureArchive.requiredFiles.map { "${dir.name}/$it" }.toSet(),
                entries.keys,
            )
        } finally {
            CaptureArchive.requiredFiles.forEach { File(dir, it).delete() }
            dir.delete()
        }
    }
}
