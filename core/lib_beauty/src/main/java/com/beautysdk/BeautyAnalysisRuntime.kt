package com.beautysdk

/** 统一有界分析运行时，可由相机采集线程提交、渲染线程读取。
 * 仅持有 worker 独占副本；不接管相机、不读取 GPU，不延长宿主帧寿命。
 * 一个视频源对应一个实例。借给 BeautySession 时由创建方负责 close。
 */
class BeautyAnalysisRuntime @JvmOverloads constructor(
    factory: FaceAnalyzerFactory,
    maxSide: Int = 320,
    private val intervalNs: Long = 0,
    allowScaledFrames: Boolean = false,
    motionEstimator: FaceMotionEstimator? = null,
    onError: (Exception) -> Unit = {},
    onTiming: (FaceAnalysisTiming) -> Unit = {},
    onTrackingTiming: (FaceAnalysisTiming) -> Unit = {},
) : AutoCloseable {
    private val detector = if(motionEstimator==null) AsyncFaceAnalysis(factory,maxSide=maxSide,
        allowScaledFrames=allowScaledFrames,onError=onError,onTiming=onTiming,keepLatestPending=true) else null
    private val tracked = motionEstimator?.let { TrackedFaceAnalysis(factory,it,allowScaledFrames=allowScaledFrames,
        maxSide=maxSide,onError=onError,onTiming=onTiming,onTrackingTiming=onTrackingTiming) }
    private var enabled=false
    private var closed=false
    private var lastOffered = -1L
    private var lastDetection = -1L
    private var geometry: FrameInfo? = null
    private val scaled=allowScaledFrames
    init { require(maxSide in 16..2048 && intervalNs>=0) }

    /** 无贴纸时在复制前跳过；需求切换作废在途结果，避免重新开启时复活旧坐标。 */
    @Synchronized fun setEnabled(value: Boolean) {
        check(!closed) { "Analysis runtime is closed" }
        if(enabled!=value) { enabled=value; reset() }
    }
    @Synchronized fun setTrackingEnabled(value: Boolean) {
        check(!closed) { "Analysis runtime is closed" }
        tracked?.setTrackingEnabled(value)
    }
    @Synchronized fun generationToken(): Long = tracked?.generationToken() ?: detector!!.generationToken()
    /** 限频只限制模型；轻量跟踪继续读取新图像，不用时钟外推代替实际匹配。 */
    @JvmOverloads @Synchronized fun submit(frame: RgbaFrame, expectedGeneration: Long? = null): Boolean {
        check(!closed) { "Analysis runtime is closed" }
        if(!enabled || expectedGeneration!=null && expectedGeneration!=generationToken()) return false
        val previous=geometry
        if(previous!=null && (previous.rotationDegrees!=frame.info.rotationDegrees || previous.mirrored!=frame.info.mirrored ||
            if(scaled) kotlin.math.abs(previous.width.toDouble()/previous.height-frame.info.width.toDouble()/frame.info.height)>=.02
            else previous.width!=frame.info.width || previous.height!=frame.info.height)) reset()
        geometry=frame.info
        val time=frame.info.timestampNs
        if(time<=lastOffered) return false
        lastOffered=time
        val detect=lastDetection<0 || time-lastDetection>=intervalNs
        val accepted=tracked?.submit(frame,runDetection=detect) ?: if(detect) detector!!.submit(frame) else false
        if(accepted) lastDetection=time
        return accepted
    }
    @Synchronized fun snapshot(info: FrameInfo,maxAgeNs: Long): FaceAnalysisSnapshot {
        require(maxAgeNs>=0)
        if(closed || !enabled) return FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        return tracked?.snapshot(info,maxAgeNs) ?: detector!!.snapshot(info,maxAgeNs)
    }
    @Synchronized fun reset() {
        if(closed) return
        tracked?.reset(); detector?.reset(); lastOffered=-1; lastDetection=-1; geometry=null
    }
    @Synchronized override fun close() {
        if(closed) return
        closed=true; enabled=false; tracked?.close(); detector?.close(); geometry=null
    }
}
