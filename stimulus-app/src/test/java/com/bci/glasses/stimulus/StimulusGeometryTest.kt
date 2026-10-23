package com.bci.glasses.stimulus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StimulusGeometryTest {
    @Test
    fun originProjectsToVisualCenter() {
        val point = StimulusGeometry.project(WorldPoint3D(0f, 0f, 2.8f), 1920, 1080)
        assertEquals(960f, point.x, 0.01f)
        assertEquals(550.8f, point.y, 0.01f)
    }

    @Test
    fun axesFollowHeadCenteredConvention() {
        val origin = StimulusGeometry.project(WorldPoint3D(0f, 0f, 2.8f), 1920, 1080)
        val right = StimulusGeometry.project(WorldPoint3D(1f, 0f, 2.8f), 1920, 1080)
        val up = StimulusGeometry.project(WorldPoint3D(0f, 1f, 2.8f), 1920, 1080)
        val farther = StimulusGeometry.project(WorldPoint3D(1f, 0f, 5.6f), 1920, 1080)

        assertTrue(right.x > origin.x)
        assertTrue(up.y < origin.y)
        assertTrue(farther.x - origin.x < right.x - origin.x)
    }

    @Test
    fun ssvepTargetsUseFixedSymmetricCoordinates() {
        val targets = StimulusGeometry.ssvepTargets
        assertEquals(listOf(8.0, 9.0, 10.0, 11.0, 12.0, 13.0), targets.map { it.frequencyHz })
        assertEquals(listOf("前进", "后退", "上升", "下降", "左偏", "右偏"), targets.map { it.label })
        assertEquals(-targets[2].position.x, targets[4].position.x, 0.001f)
        assertEquals(targets[2].position.y, targets[4].position.y, 0.001f)
        assertEquals(-targets[2].position.y, targets[3].position.y, 0.001f)
        assertTrue(targets.all { it.position.z == StimulusGeometry.TARGET_Z })
    }

    @Test
    fun positiveCameraYawMovesForwardPointLeft() {
        val centered = StimulusGeometry.project(WorldPoint3D(0f, 0f, 2.8f), 1920, 1080)
        val rotated = StimulusGeometry.project(
            WorldPoint3D(0f, 0f, 2.8f),
            1920,
            1080,
            CameraPose(yawDegrees = 12f),
        )
        assertTrue(rotated.x < centered.x)
    }

    @Test
    fun yawWrapsWithoutADeadEnd() {
        assertEquals(-170f, StimulusGeometry.normalizeYaw(190f), 0.001f)
        assertEquals(170f, StimulusGeometry.normalizeYaw(-190f), 0.001f)
        assertEquals(0f, StimulusGeometry.normalizeYaw(720f), 0.001f)
    }

    @Test
    fun pointsBehindViewerAreCulled() {
        val hidden = StimulusGeometry.projectOrNull(
            WorldPoint3D(0f, 0f, 2.8f),
            1920,
            1080,
            CameraPose(yawDegrees = 180f),
        )
        assertNull(hidden)
    }
}
