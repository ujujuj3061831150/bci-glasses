package com.bci.glasses

/** Implement a vendor adapter only after transforming vendor coordinates and timestamps.
 * SDK fields that are unavailable must remain unknown; a head ray is not an eye ray.
 */
interface ControlGazeProvider {
    val sourceName: String
    val simulated: Boolean
    fun sample(nowMillis: Long): ControlGazeSample?
}

class SimulatedPointerGazeProvider : ControlGazeProvider {
    override val sourceName = "SIMULATED_POINTER"
    override val simulated = true
    var x = 0.0
    var y = 0.0
    var available = true
    override fun sample(nowMillis: Long) = ControlGazeSample(x, y, nowMillis, available, 1.0, sourceName)
    fun reset() { x = 0.0; y = 0.0 }
}
