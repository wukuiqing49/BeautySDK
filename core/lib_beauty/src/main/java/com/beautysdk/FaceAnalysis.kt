package com.beautysdk

import java.util.Collections
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.roundToInt

/** 所有坐标均为原始画面左上角原点、x 向右/y 向下的归一化坐标，尚未应用旋转/镜像元数据。 */
data class FacePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}
/** 原始画面上的归一化人脸框，边界限制在0至1且必须具有正面积。 */
data class FaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() })
        require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top)
    }
}
/** LEFT/RIGHT 指转正后的画面左右，而非人物解剖学左右；坐标仍在原始空间。 */
enum class FaceAnchor { LEFT_EYE, RIGHT_EYE, NOSE, LEFT_MOUTH, RIGHT_MOUTH, UPPER_LIP }
/** 分析后端真实支持的能力集合；缺少能力时隐藏依赖它的挂件。 */
enum class FaceCapability { BOUNDS, EYES, MOUTH_CORNERS, UPPER_LIP, DENSE_LANDMARKS }

/** 不可变人脸观测：复制语义锚点及关键点列表，所有坐标位于原始帧归一化空间。 */
class FaceObservation(
    val bounds: FaceBounds,
    anchors: Map<FaceAnchor, FacePoint> = emptyMap(),
    landmarks: List<FacePoint> = emptyList(),
) {
    val anchors: Map<FaceAnchor, FacePoint> = Collections.unmodifiableMap(
        EnumMap<FaceAnchor, FacePoint>(FaceAnchor::class.java).apply { putAll(anchors) }
    )
    val landmarks: List<FacePoint> = Collections.unmodifiableList(landmarks.toList())
}

/** SDK 独占的紧凑 RGB 副本。仅在 analyze 调用期间有效，后端不得保存或修改 pixels。 */
class AnalysisImage(val info: FrameInfo, val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height * 3) }
    companion object {
        /** 同步复制 RGBA 为独占紧凑 RGB，并按 maxSide 等比例缩小；保留原始帧元数据，避免异步使用已失效缓冲。 */
        fun copyOf(frame: RgbaFrame, maxSide: Int = 320): AnalysisImage {
            require(maxSide in 16..2048)
            val scale = minOf(1.0, maxSide.toDouble() / max(frame.info.width, frame.info.height))
            val w = max(1, (frame.info.width * scale).roundToInt())
            val h = max(1, (frame.info.height * scale).roundToInt())
            val rgb = ByteArray(w * h * 3)
            val source = frame.readOnlyPixels()
            for (y in 0 until h) for (x in 0 until w) {
                val sx = (x.toLong() * frame.info.width / w).toInt()
                val sy = (y.toLong() * frame.info.height / h).toInt()
                val src = sy * frame.rowStrideBytes + sx * 4
                val dst = (y * w + x) * 3
                for (c in 0..2) rgb[dst + c] = source.get(src + c)
            }
            return AnalysisImage(frame.info, w, h, rgb)
        }
    }
}

/** 由 worker 创建、分析、释放；单次同步分析，SDK 已在外层实现异步背压。 */
interface FaceAnalyzer : AutoCloseable {
    val id: String
    val capabilities: Set<FaceCapability>
    /** 同步分析单张 RGB 图并返回原始空间的归一化人脸信息；调用期间输入有效，不得保留或修改图像数组。 */
    fun analyze(image: AnalysisImage): List<FaceObservation>
    /** 同步借用 RGBA 帧，调用返回前宿主必须保持输入有效；禁止在引擎关键线程等待模型。
     * 后端可覆盖以直接转换到复用缓冲；默认路径保留旧 RGB 后端兼容性。
     */
    fun analyzeRgba(frame: RgbaFrame): List<FaceObservation> = analyze(AnalysisImage.copyOf(frame))
    /** 统一 worker 指定检测最长边；在 worker 内缩图，不让引擎回调执行逐像素转换。 */
    fun analyzeRgba(frame: RgbaFrame, maxSide: Int): List<FaceObservation> =
        if (maxSide == 320) analyzeRgba(frame) else analyze(AnalysisImage.copyOf(frame, maxSide))
    /** 后端可选阶段计时；在分析线程读取，缺省表示未提供，不伪造为0耗时。 */
    val lastTiming: FaceAnalyzerTiming? get() = null
    /** 在所属分析线程关闭模型及原生会话；不得与推理并发释放。 */
    override fun close()
}
/** 在分析线程创建独立检测器的工厂；不得返回已关闭或由其他线程管理的实例。 */
fun interface FaceAnalyzerFactory { /** 在当前分析线程创建并交出一个检测器，由调用方在同线程释放。 */ fun create(): FaceAnalyzer }

/** 绑定采样帧元数据的人脸结果，复制列表；渲染前须检查会话、几何及结果年龄。 */
class FaceResult(val info: FrameInfo, faces: List<FaceObservation>) {
    val faces: List<FaceObservation> = Collections.unmodifiableList(faces.toList())
}

/** 检测状态和时延，不含图像；result 只包含当前方向、时间线内且未过期的结果。 */
enum class FaceAnalysisState { DISABLED, STARTING, PROCESSING, READY, NO_FACE, STALE, ERROR }
/** 本帧检测诊断：结果可为空；年龄与分析耗时以纳秒计，错误文本不包含图像数据。 */
data class FaceAnalysisSnapshot(
    val state: FaceAnalysisState,
    val result: FaceResult? = null,
    val resultAgeNs: Long? = null,
    val durationNs: Long? = null,
    val error: String? = null,
    /** true表示由新图像匹配更新，并非一次新的模型检测。 */
    val tracked: Boolean = false,
    /** 跟踪锚定的原始检测采样时间；不能用跟踪时间覆盖这个校正依据。 */
    val detectionTimestampNs: Long? = null,
)

/** 只比较尺寸、旋转和镜像，不比较时间戳，用于判断坐标能否复用。 */
internal fun FrameInfo.sameGeometry(other: FrameInfo) = width == other.width && height == other.height &&
    rotationDegrees == other.rotationDegrees && mirrored == other.mirrored

/** 同步后端计时，不含宿主帧复制/排队；单位纳秒。 */
data class FaceAnalyzerTiming(val preparationNs: Long, val inferenceNs: Long)
/** 统一分析运行时的每次完成统计；本机时钟不与宿主视频时间戳直接相减。 */
data class FaceAnalysisTiming(val frameTimestampNs: Long, val copyNs: Long, val queueNs: Long,
    val analysisNs: Long, val resultIntervalNs: Long?, val faces: Int,
    val backendTiming: FaceAnalyzerTiming?)

/** SDK 与实时 Demo 共用的默认值；宿主可显式配置，但不能用此值承诺处理延迟。 */
object BeautyPipelineDefaults { const val MAX_RESULT_AGE_NS = 150_000_000L }
