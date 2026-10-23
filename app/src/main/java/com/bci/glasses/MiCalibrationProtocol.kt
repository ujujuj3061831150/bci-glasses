package com.bci.glasses

import kotlin.random.Random

/**
 * 运动想象条件枚举：左手 (0) 与右手 (1)。
 */
enum class MiCondition(
    val label: Int,
    val cueCode: Int,
    val activeCode: Int,
    val offsetCode: Int,
    val displayName: String,
    val shortName: String,
) {
    LEFT_HAND(
        label = 0,
        cueCode = MiCalibrationSpec.EventCodes.CUE_LEFT,
        activeCode = MiCalibrationSpec.EventCodes.ACTIVE_LEFT,
        offsetCode = MiCalibrationSpec.EventCodes.ACTIVE_OFFSET,
        displayName = "左手运动想象",
        shortName = "左手",
    ),
    RIGHT_HAND(
        label = 1,
        cueCode = MiCalibrationSpec.EventCodes.CUE_RIGHT,
        activeCode = MiCalibrationSpec.EventCodes.ACTIVE_RIGHT,
        offsetCode = MiCalibrationSpec.EventCodes.ACTIVE_OFFSET,
        displayName = "右手运动想象",
        shortName = "右手",
    ),
}

/**
 * 运动想象试次内的视觉阶段。
 */
enum class MiVisualPhase {
    PREPARE,    // 准备期：注视中心十字
    CUE,        // 提示期：左/右箭头指示
    ACTIVE,     // 任务期：保持运动想象
    REST,       // 休息期：放松
}

/**
 * 传递给 UI 渲染的视觉状态。
 */
data class MiVisualState(
    val blockId: Int,
    val trialId: Int,
    val totalTrialsInBlock: Int,
    val condition: MiCondition,
    val phase: MiVisualPhase,
    val practice: Boolean,
)

/**
 * 绑定屏幕刷新 VSync 的帧事件。
 */
data class MiFrameEvent(
    val visualState: MiVisualState,
    val eventType: String,
    val eventCode: Int,
    val frameTimeNanos: Long = 0L,
)

/**
 * 单个试次定义。
 */
data class MiTrial(
    val blockId: Int,
    val trialId: Int,
    val condition: MiCondition,
    val practice: Boolean,
)

/**
 * 单个实验区块定义。
 */
data class MiBlock(
    val blockId: Int,
    val practice: Boolean,
    val trials: List<MiTrial>,
)

/**
 * 完整实验计划。
 */
data class MiPlan(
    val seed: Long,
    val practiceBlock: MiBlock?,
    val formalBlocks: List<MiBlock>,
) {
    val totalFormalTrials: Int get() = formalBlocks.sumOf { it.trials.size }
}

/**
 * 运动想象实验计划工厂：负责生成严格 1:1 均衡的随机试次序列。
 */
object MiPlanFactory {
    private const val PRACTICE_SALT = 0x5A5A5A5AL

    fun create(
        seed: Long,
        blockCount: Int = MiCalibrationSpec.DEFAULT_BLOCK_COUNT,
        trialsPerBlock: Int = MiCalibrationSpec.DEFAULT_TRIALS_PER_BLOCK,
        withPractice: Boolean = false,
    ): MiPlan {
        require(blockCount in 1..10) { "区块数量必须在 1 到 10 之间" }
        require(trialsPerBlock >= 2 && trialsPerBlock % 2 == 0) { "每区块试次数必须为偶数且至少为 2" }

        val practiceBlock = if (withPractice) {
            val practiceConditions = mutableListOf(
                MiCondition.LEFT_HAND, MiCondition.LEFT_HAND,
                MiCondition.RIGHT_HAND, MiCondition.RIGHT_HAND,
            ).also { it.shuffle(Random(seed xor PRACTICE_SALT)) }

            MiBlock(
                blockId = 0,
                practice = true,
                trials = practiceConditions.mapIndexed { index, condition ->
                    MiTrial(blockId = 0, trialId = index + 1, condition = condition, practice = true)
                },
            )
        } else null

        val rng = Random(seed)
        val formalBlocks = (1..blockCount).map { blockId ->
            val perClass = trialsPerBlock / 2
            val conditions = ArrayList<MiCondition>(trialsPerBlock).apply {
                repeat(perClass) { add(MiCondition.LEFT_HAND) }
                repeat(perClass) { add(MiCondition.RIGHT_HAND) }
                shuffle(rng)
            }
            MiBlock(
                blockId = blockId,
                practice = false,
                trials = conditions.mapIndexed { index, condition ->
                    MiTrial(blockId = blockId, trialId = index + 1, condition = condition, practice = false)
                },
            )
        }

        return MiPlan(seed = seed, practiceBlock = practiceBlock, formalBlocks = formalBlocks)
    }
}
