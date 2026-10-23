package com.bci.glasses.stimulus

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Head-centered world coordinates: +X right, +Y up, +Z forward from the viewer.
 */
data class WorldPoint3D(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class ScreenPoint2D(
    val x: Float,
    val y: Float,
    val scale: Float,
)

data class CameraPose(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
)

data class StimulusTarget(
    val id: String,
    val frequencyHz: Double,
    val phaseRadians: Double,
    val label: String,
    val position: WorldPoint3D,
    val accentColor: Int,
)

object StimulusGeometry {
    const val CAMERA_X = 0f
    const val CAMERA_Y = 0f
    const val CAMERA_Z = 0f
    const val TARGET_Z = 2.8f
    // Approx. 4-6 degrees of visual angle on a typical glasses display; tune on the target hardware.
    const val TARGET_WIDTH = 0.30f
    const val TARGET_HEIGHT = 0.30f

    val ssvepTargets = listOf(
        StimulusTarget("0", 8.0, 0.0, "前进", WorldPoint3D(0f, 0.88f, TARGET_Z), 0xFF63D7CA.toInt()),
        StimulusTarget("1", 9.0, 1.5708, "后退", WorldPoint3D(0f, -0.88f, TARGET_Z), 0xFFF2B84B.toInt()),
        StimulusTarget("2", 10.0, 3.1416, "上升", WorldPoint3D(-1.28f, 0.42f, TARGET_Z), 0xFF64C987.toInt()),
        StimulusTarget("3", 11.0, 4.7124, "下降", WorldPoint3D(-1.28f, -0.42f, TARGET_Z), 0xFFF07A70.toInt()),
        StimulusTarget("4", 12.0, 0.0, "左偏", WorldPoint3D(1.28f, 0.42f, TARGET_Z), 0xFF8EA8F8.toInt()),
        StimulusTarget("5", 13.0, 1.5708, "右偏", WorldPoint3D(1.28f, -0.42f, TARGET_Z), 0xFFE795C8.toInt()),
    )

    val leftHandCue = WorldPoint3D(-0.82f, 0f, 2.6f)
    val rightHandCue = WorldPoint3D(0.82f, 0f, 2.6f)
    val fixation = WorldPoint3D(0f, 0f, 2.6f)

    fun project(
        point: WorldPoint3D,
        width: Int,
        height: Int,
        cameraPose: CameraPose = CameraPose(),
    ): ScreenPoint2D = requireNotNull(projectOrNull(point, width, height, cameraPose)) {
        "Point must be in front of the viewer"
    }

    fun projectOrNull(
        point: WorldPoint3D,
        width: Int,
        height: Int,
        cameraPose: CameraPose = CameraPose(),
    ): ScreenPoint2D? {
        require(width > 0 && height > 0) { "Viewport must be positive" }
        val cameraPoint = toCameraSpace(point, cameraPose)
        if (cameraPoint.z <= 0.25f) return null

        val focalLength = min(width, height) * 1.12f
        val depth = cameraPoint.z - CAMERA_Z
        val scale = focalLength / depth
        return ScreenPoint2D(
            x = width * 0.5f + (cameraPoint.x - CAMERA_X) * scale,
            y = height * 0.51f - (cameraPoint.y - CAMERA_Y) * scale,
            scale = scale,
        )
    }

    fun normalizeYaw(yawDegrees: Float): Float {
        val wrapped = (yawDegrees + 180f) % 360f
        return (if (wrapped < 0f) wrapped + 360f else wrapped) - 180f
    }

    fun moveInViewPlane(
        point: WorldPoint3D,
        deltaXPixels: Float,
        deltaYPixels: Float,
        width: Int,
        height: Int,
        cameraPose: CameraPose,
    ): WorldPoint3D {
        val projected = project(point, width, height, cameraPose)
        val cameraPoint = toCameraSpace(point, cameraPose)
        val movedCameraPoint = WorldPoint3D(
            x = cameraPoint.x + deltaXPixels / projected.scale,
            y = cameraPoint.y - deltaYPixels / projected.scale,
            z = cameraPoint.z,
        )
        return fromCameraSpace(movedCameraPoint, cameraPose)
    }

    private fun toCameraSpace(point: WorldPoint3D, pose: CameraPose): WorldPoint3D {
        val yaw = Math.toRadians(pose.yawDegrees.toDouble())
        val pitch = Math.toRadians(pose.pitchDegrees.toDouble())
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()

        val x1 = cy * point.x - sy * point.z
        val z1 = sy * point.x + cy * point.z
        val y2 = cp * point.y - sp * z1
        val z2 = sp * point.y + cp * z1
        return WorldPoint3D(x1, y2, z2)
    }

    private fun fromCameraSpace(point: WorldPoint3D, pose: CameraPose): WorldPoint3D {
        val yaw = Math.toRadians(pose.yawDegrees.toDouble())
        val pitch = Math.toRadians(pose.pitchDegrees.toDouble())
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()

        val y1 = cp * point.y + sp * point.z
        val z1 = -sp * point.y + cp * point.z
        val x = cy * point.x + sy * z1
        val z = -sy * point.x + cy * z1
        return WorldPoint3D(x, y1, z)
    }
}
