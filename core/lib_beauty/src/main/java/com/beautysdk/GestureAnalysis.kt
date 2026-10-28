package com.beautysdk

import java.util.Collections

/** 模型分类与实验性规则分别列出；NUMBER_0..5 表示伸出的手指数，不表示各地区数字手语。 */
enum class GestureKind {
    CLOSED_FIST, OPEN_PALM, POINTING_UP, THUMB_DOWN, THUMB_UP, VICTORY, I_LOVE_YOU,
    OK, NUMBER_0, NUMBER_1, NUMBER_2, NUMBER_3, NUMBER_4, NUMBER_5,
    FINGER_HEART, TWO_HAND_HEART, PRAYER, WAVE, CLAP, CIRCLE, PINCH, TWO_HAND_SCALE
}
enum class Handedness { LEFT, RIGHT, UNKNOWN }
enum class GestureSource { MODEL, EXPERIMENTAL_RULE }

/** 点序与 MediaPipe 21 点一致；x/y 为原始帧左上原点归一化坐标，未烘焙旋转镜像。
 * handedness 是分析图模型的分类，不用画面左右冒充解剖学左右。score 仅表示分类置信度。
 */
class HandObservation(
    landmarks: List<FacePoint>,
    val handedness: Handedness = Handedness.UNKNOWN,
    val handednessScore: Float = 0f,
    val modelGesture: GestureKind? = null,
    val modelScore: Float = 0f,
) {
    val landmarks: List<FacePoint> = Collections.unmodifiableList(landmarks.toList())
    init {
        require(landmarks.size == 21)
        require(handednessScore in 0f..1f && modelScore in 0f..1f)
        require(modelGesture == null || modelGesture in MODEL_GESTURES)
    }
    companion object {
        val MODEL_GESTURES: Set<GestureKind> = Collections.unmodifiableSet(setOf(
            GestureKind.CLOSED_FIST, GestureKind.OPEN_PALM, GestureKind.POINTING_UP,
            GestureKind.THUMB_DOWN, GestureKind.THUMB_UP, GestureKind.VICTORY, GestureKind.I_LOVE_YOU))
    }
}

/** 同步接口由独立 worker 创建/调用/关闭；只借用输入，不保存帧。 */
interface GestureAnalyzer : AutoCloseable {
    fun analyzeRgba(frame: RgbaFrame, maxSide: Int): List<HandObservation>
    override fun close()
}
fun interface GestureAnalyzerFactory { fun create(): GestureAnalyzer }

enum class GestureEventPhase { TRIGGER, START, UPDATE, END, CANCEL }

/** 事件只包含数据，不执行动画。时间来自真实采样帧；位置/拖动增量均为原始帧归一化空间。
 * scale 是捏合起点的相对比例；trackIds 只在本会话内有效，不代表人体身份。
 * 规则事件 score=null，不能把规则阈值解释为模型概率。
 */
class GestureEvent(
    val id: Long,
    val kind: GestureKind,
    val phase: GestureEventPhase,
    val timestampNs: Long,
    trackIds: List<Long>,
    val position: FacePoint,
    val source: GestureSource,
    val score: Float? = null,
    val scale: Float = 1f,
    val delta: FacePoint = FacePoint(0f, 0f),
) {
    val trackIds: List<Long> = Collections.unmodifiableList(trackIds.toList())
}

data class GestureConfig @JvmOverloads constructor(
    val minModelScore: Float = .65f,
    val stableNs: Long = 180_000_000L,
    val cooldownNs: Long = 800_000_000L,
    val maxGapNs: Long = 300_000_000L,
    val experimentalRules: Boolean = true,
) {
    init {
        require(minModelScore in 0f..1f)
        require(stableNs >= 0 && cooldownNs >= 0 && maxGapNs in 1..2_000_000_000L)
    }
}

enum class GestureAnalysisState { DISABLED, PROCESSING, READY, NO_HANDS, STALE, ERROR }

class GestureResult(val info: FrameInfo, hands: List<HandObservation>) {
    val hands: List<HandObservation> = Collections.unmodifiableList(hands.toList())
}
data class GestureSnapshot(
    val state: GestureAnalysisState,
    val result: GestureResult? = null,
    val ageNs: Long? = null,
    val durationNs: Long? = null,
    val error: String? = null,
)
