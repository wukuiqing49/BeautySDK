package com.beautysdk

import kotlin.math.abs

/** 有界检测结果历史，供预览和直播引擎按采样时间选择结果；不保存图像、不排队推理。
 * 分析完成可能早于对应视频帧抵达渲染线程，因此不能只保留最新一条结果。
 * 所有方法线程安全；切流、换镜头或会话重置时必须 clear，避免同尺寸画面复用旧坐标。
 * @param allowScaledFrames 仅当宿主保证分析/输出共享同一裁剪视场时启用，允许等比例不同尺寸。
 */
class FaceAnalysisHistory @JvmOverloads constructor(
    private val capacity: Int = 8,
    private val allowScaledFrames: Boolean = false,
) {
    private data class Sample(val info: FrameInfo, val result: FaceResult?, val durationNs: Long,
                              val error: String?)
    private val samples = ArrayList<Sample>(capacity)

    init { require(capacity in 2..32) }

    /** 单一分析生产者按时间递增发布；乱序/重复结果不覆盖历史，几何变化清空旧坐标。
     * 无脸和错误同样进入历史，作为对应视频时间点的失效信号，不能向更早的有脸结果回退。
     */
    @Synchronized
    @JvmOverloads
    fun publish(info: FrameInfo, faces: List<FaceObservation>, durationNs: Long, error: String? = null): Boolean {
        require(durationNs >= 0)
        val previous = samples.lastOrNull()
        if (previous != null) {
            if (!compatible(previous.info, info)) samples.clear()
            else if (info.timestampNs <= previous.info.timestampNs) return false
        }
        if (samples.size == capacity) samples.removeAt(0)
        samples.add(Sample(info, if (error == null) FaceResult(info, faces) else null, durationNs, error))
        return true
    }

    /** 选择不晚于当前视频帧的最新同视场结果；未来结果不会遮掉仍有效的上一条结果。
     * 不延长 maxAgeNs，不预测坐标；最近可用结果无脸/错误/过期时明确隐藏贴纸。
     */
    @Synchronized
    @JvmOverloads
    fun snapshot(info: FrameInfo, maxAgeNs: Long,
                 emptyState: FaceAnalysisState = FaceAnalysisState.PROCESSING): FaceAnalysisSnapshot {
        require(maxAgeNs >= 0)
        for (i in samples.lastIndex downTo 0) {
            val sample = samples[i]
            if (!compatible(sample.info, info) || sample.info.timestampNs > info.timestampNs) continue
            val age = info.timestampNs - sample.info.timestampNs
            val state = when {
                sample.error != null -> FaceAnalysisState.ERROR
                age > maxAgeNs -> FaceAnalysisState.STALE
                sample.result!!.faces.isEmpty() -> FaceAnalysisState.NO_FACE
                else -> FaceAnalysisState.READY
            }
            return FaceAnalysisSnapshot(state,
                if (state == FaceAnalysisState.READY || state == FaceAnalysisState.NO_FACE) sample.result else null,
                age, sample.durationNs, sample.error)
        }
        return FaceAnalysisSnapshot(emptyState)
    }

    /** 不依赖视频时间戳递增；显式重置后允许新流从较小时间戳重新开始。 */
    @Synchronized
    fun clear() { samples.clear() }

    /** 旋转、镜像始终一致；跨尺寸复用必须由宿主保证裁剪/视场一致。 */
    private fun compatible(a: FrameInfo, b: FrameInfo): Boolean =
        a.rotationDegrees == b.rotationDegrees && a.mirrored == b.mirrored &&
            if (allowScaledFrames) abs(a.width.toDouble() / a.height - b.width.toDouble() / b.height) < .02
            else a.width == b.width && a.height == b.height
}
