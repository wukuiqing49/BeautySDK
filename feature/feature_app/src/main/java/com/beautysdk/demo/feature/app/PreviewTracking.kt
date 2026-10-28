package com.beautysdk.demo.feature.app

import com.beautysdk.*

/** 只有同一相机会话、检测方案、方向与 ViewPort 比例的结果才可复用。
 * 两条相机支路共享单调时间线；未来结果和过旧结果均不能绘制到当前帧。
 */
internal data class PreviewDetection(val epoch: Long,val backend: DemoBackend,val frame: FrameInfo,
    val faces: List<FaceObservation>,val duration: Long,val error: String? = null) {
    companion object { const val MAX_AGE_NS = BeautyPipelineDefaults.MAX_RESULT_AGE_NS }
}

/** 相机两支路可出现分析先到、预览后到；复用共享 SDK 的时间选择逻辑。
 * 缓存只包含8条结果，不增加推理任务；配置代次、会话、后端改变后不能读取旧结果。
 */
internal class PreviewDetectionHistory {
    private data class Scope(val epoch: Long, val backend: DemoBackend, val revision: Long)
    private var scope: Scope? = null
    private val history = FaceAnalysisHistory(allowScaledFrames = true)

    /** 仅分析线程发布；各入口加锁，使清理和 GL 快照不会读到半更新状态。 */
    @Synchronized
    fun publish(detection: PreviewDetection, revision: Long) {
        val next = Scope(detection.epoch, detection.backend, revision)
        if (scope != next) { history.clear(); scope = next }
        history.publish(detection.frame, detection.faces, detection.duration, detection.error)
    }

    /** 不把未来的无脸/错误结果提前应用到尚在显示的旧视频帧。 */
    @Synchronized
    fun snapshot(info: FrameInfo, epoch: Long, backend: DemoBackend, revision: Long): FaceAnalysisSnapshot {
        if (backend == DemoBackend.BEAUTY_ONLY) return FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        if (scope != Scope(epoch, backend, revision)) return FaceAnalysisSnapshot(FaceAnalysisState.PROCESSING)
        return history.snapshot(info, PreviewDetection.MAX_AGE_NS)
    }

    /** 停止/切换镜头时丢弃所有历史，不延长任何旧结果的有效期。 */
    @Synchronized
    fun clear() { scope = null; history.clear() }
}
