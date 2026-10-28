package com.beautysdk

/** 公共引擎处理编排，组合已有分析、纹理桥接及Session，不依赖直播厂商。
 * 默认GL渲染器要求创建时已有当前ES3上下文；启停、处理、释放都在该线程/上下文。
 * factory和sharedAnalysis均为空时仅做基础美颜；独立RGBA支路配置textureAnalysis=false，避免GPU读回。
 * 高清输出仍借用至下一次process/close，异步引擎需适配器显式复制/租用及同步。
 */
class BeautyEnginePipeline @JvmOverloads constructor(
    factory: FaceAnalyzerFactory? = null,
    renderer: BeautyRenderer = GlBeautyRenderer(),
    textureAnalysis: Boolean = true,
    analysisMaxSide: Int = 320,
    analysisIntervalNs: Long = 33_333_333L,
    maxResultAgeNs: Long = BeautyPipelineDefaults.MAX_RESULT_AGE_NS,
    private val onError: (Exception) -> Unit = {},
    /** 借用已有独立采集分析支路；启用需求、换源reset和最终close均由宿主管理。 */
    sharedAnalysis: BeautyAnalysisRuntime? = null,
    /** 可选图像跟踪后端，与输入来源解耦；仅用于自有分析，借用模式由宿主配置。 */
    motionEstimator: FaceMotionEstimator? = null,
) : RedrawFrameProcessor {
    private val owner=Thread.currentThread()
    private val lifecycle = BeautyProcessor()
    init {
        require(motionEstimator == null || sharedAnalysis == null && factory != null) {
            "Motion estimator requires an owned analyzer factory"
        }
        require(sharedAnalysis == null || factory == null) { "Use factory or sharedAnalysis, not both" }
        require(sharedAnalysis == null || !textureAnalysis) { "Shared analysis must use the external RGBA path" }
    }
    private val ownsAnalysis=sharedAnalysis==null
    private val analysis = sharedAnalysis ?: factory?.let {
        BeautyAnalysisRuntime(it,maxSide=analysisMaxSide,intervalNs=analysisIntervalNs,
            allowScaledFrames=true,motionEstimator=motionEstimator,onError=onError).also { runtime ->
                if(motionEstimator!=null) runtime.setTrackingEnabled(true)
            }
    }
    private val bridge = if (textureAnalysis && analysis != null)
        GlTextureAnalysisBridge(analysis,maxSide=analysisMaxSide,intervalNs=analysisIntervalNs) else null
    private val session = BeautySession(renderer=renderer,sharedAnalysis=analysis,
        allowScaledAnalysis=true,maxResultAgeNs=maxResultAgeNs,onError=onError,
        analysisMaxSide=analysisMaxSide,analysisIntervalNs=analysisIntervalNs)
    private val performanceMeter=BeautyPerformanceMeter()
    /** 限频读取即可；CPU提交与GPU执行时间严格区分，不含redraw重绘。 */
    val performance:BeautyPerformanceSnapshot get()=performanceMeter.snapshot()
    private var closed=false
    private var geometry: FrameInfo?=null
    /** UI可整体替换配置；实际需求在处理线程更新。 */
    @Volatile var settings=BeautySettings()
    /** 最近一帧实际用于合成的分析状态。 */
    val lastFrameAnalysis: FaceAnalysisSnapshot get() = session.lastFrameAnalysis

    override fun start() { checkOwner(); lifecycle.start(); session.start();performanceMeter.reset() }
    override fun process(frame: VideoFrame): VideoFrame {
        checkOwner(); lifecycle.process(frame)
        val processingStarted=System.nanoTime()
        val previous=geometry
        if(previous!=null && (previous.width!=frame.info.width || previous.height!=frame.info.height ||
                previous.rotationDegrees!=frame.info.rotationDegrees || previous.mirrored!=frame.info.mirrored)) resetAnalysis()
        geometry=frame.info
        val current=settings
        session.settings=current
        if(ownsAnalysis) analysis?.setEnabled(current.requiresFaceAnalysis)
        if(frame is TextureFrame && bridge!=null) {
            try { bridge.submit(frame,current.requiresFaceAnalysis) }
            catch(e: Exception) {
                // 分析支路失败不截断本帧美颜；清除旧人脸后继续合成并报告。
                resetAnalysis()
                try { onError(e) } catch (_: Exception) { }
            }
        }
        return session.process(frame).also { performanceMeter.record(System.nanoTime()-processingStarted) }
    }
    /** 同一视频采样仅因调参重绘；不调用桥接读回或重复投递RGBA。 */
    override fun redraw(frame: VideoFrame): VideoFrame {
        checkOwner(); lifecycle.requireRunning()
        val current=settings
        session.settings=current
        if(ownsAnalysis) analysis?.setEnabled(current.requiresFaceAnalysis)
        return session.redraw(frame)
    }
    /** 独立采集RGBA支路：调用者在固定处理线程提交，与输出具有相同视场/时间基准。
     * 使用此模式时构造textureAnalysis=false，禁止同时投递两种来源。
     */
    fun submitAnalysis(frame: RgbaFrame): Boolean {
        checkOwner(); lifecycle.requireRunning()
        check(bridge==null) { "Select external RGBA or texture analysis, not both" }
        if(ownsAnalysis) analysis?.setEnabled(settings.requiresFaceAnalysis)
        return analysis?.submit(frame) ?: false
    }
    /** 同尺寸换镜头/视频源时重启时间线；自有检测会清除，借用检测由宿主另行reset。 */
    fun resetSource() {
        checkOwner(); lifecycle.requireRunning()
        resetAnalysis(); geometry=null;performanceMeter.reset()
        session.stop(); session.start(); lifecycle.stop(); lifecycle.start()
    }
    private fun checkOwner() { check(Thread.currentThread() === owner) { "Use the pipeline creating thread" } }
    private fun resetAnalysis() { if(bridge!=null) bridge.reset() else if(ownsAnalysis) analysis?.reset() }
    override fun stop() {
        checkOwner(); lifecycle.requireRunning()
        try { session.stop() } finally { resetAnalysis(); geometry=null; lifecycle.stop() }
    }
    override fun close() {
        checkOwner()
        if(closed) { lifecycle.close(); return }
        // 先检查所属线程，不允许在错误线程部分释放。
        lifecycle.requireOwnerThread()
        try { bridge?.close() } finally {
            try { session.close() } finally { if(ownsAnalysis) analysis?.close(); lifecycle.close(); closed=true; geometry=null }
        }
    }
}
