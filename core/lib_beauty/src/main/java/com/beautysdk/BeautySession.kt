package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
/** 一路视频对应一个 session。生命周期/渲染同线程；异步后端由独立 worker 管理。
 * 输出所有权由 renderer 约定：CPU 独占副本；GL 输出仅有效至下一次 render/close。
 * @param factory 分析后端工厂，null表示只做美颜；后端由独立分析线程创建和释放。
 * @param renderer CPU或GL渲染器，GL实现必须在宿主创建它的EGL上下文调用。
 * @param maxResultAgeNs 检测结果允许复用的最大年龄，单位纳秒；过期时隐藏依赖关键点的挂件。
 * @param onError 分析或渲染错误通知，可能来自不同处理线程，UI更新须由宿主切主线程。
 * @param analysisIntervalNs 最小分析采样间隔，单位纳秒；0表示只受分析worker忙闲限制。
 * @param analysisMaxSide 分析副本最长边上限，单位像素；不会改变输出视频尺寸。
 */
class BeautySession @JvmOverloads constructor(
    factory: FaceAnalyzerFactory? = null,
    private val renderer: BeautyRenderer = RgbaBeautyRenderer(),
    private val maxResultAgeNs: Long = BeautyPipelineDefaults.MAX_RESULT_AGE_NS,
    private val onError: (Exception) -> Unit = {},
    /** 分析采样最小间隔（纳秒）；0 表示仅按 worker 忙闲丢帧，不限制渲染帧率。 */
    private val analysisIntervalNs: Long = 0,
    /** 仅缩小分析副本；输出视频尺寸保持不变。 */
    analysisMaxSide: Int = 320,
    /** 由宿主保证检测图与输出纹理共享同一裁剪视场，才允许尺寸不同。 */
    private val allowScaledAnalysis: Boolean = false,
    onAnalysisTiming: (FaceAnalysisTiming) -> Unit = {},
    /** 可选图像匹配后端；null保持仅模型检测。纹理宿主仍需提交同视场RGBA分析支路。 */
    motionEstimator: FaceMotionEstimator? = null,
    /** 可借用公共运行时接收独立采集线程的分析帧；其需求配置和释放由宿主管理。 */
    sharedAnalysis: BeautyAnalysisRuntime? = null,
) : RedrawFrameProcessor {
    private val lifecycle = BeautyProcessor()
    private val ownsAnalysis=sharedAnalysis==null
    private val analysis=sharedAnalysis ?: factory?.let { BeautyAnalysisRuntime(it,analysisMaxSide,
        analysisIntervalNs,allowScaledAnalysis,motionEstimator,onError,onAnalysisTiming) }
    private fun resetAnalysis() { if(ownsAnalysis) analysis?.reset() }
    private fun analysisSnapshot(info: FrameInfo): FaceAnalysisSnapshot =
        analysis?.snapshot(info,maxResultAgeNs) ?: FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
    private var closed = false
    private var geometry: FrameInfo? = null
    private var mapping: AnalysisFrameMapping? = null
    private var mappedSource: FaceResult? = null
    private var mappedResult: FaceResult? = null
    private var lastFrameInfo: FrameInfo? = null
    @Volatile var settings = BeautySettings()
        set(value) {
            if(ownsAnalysis) analysis?.setEnabled(value.requiresFaceAnalysis)
            field=value
        }
    /** 本次实际交给渲染器的检测快照，不能用渲染结束后才产生的新结果冒充已绘制人脸。 */
    @Volatile var lastFrameAnalysis = FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        private set
    init { require(maxResultAgeNs >= 0 && analysisIntervalNs >= 0); require(analysisMaxSide in 16..2048)
        require(sharedAnalysis==null || factory==null) { "Use factory or sharedAnalysis, not both" }
        if(ownsAnalysis && motionEstimator!=null) analysis?.setTrackingEnabled(true)
    }
    /** 在当前处理线程启动会话；后续处理及释放必须由同一线程调用。 */
    override fun start() = lifecycle.start()
    /** 提交有界异步分析并用最近有效锚点合成本帧；不等待推理。渲染异常时报告错误并返回原帧。 */
    override fun process(frame: VideoFrame): VideoFrame {
        lifecycle.process(frame)
        val selectedMapping = mapping
        if (selectedMapping != null) {
            require(selectedMapping.target.matches(frame.info)) { "Output geometry changed; update analysis mapping first" }
        } else {
            updateGeometry(frame.info)
            if (frame is RgbaFrame) offerAnalysis(frame)
        }
        lastFrameInfo=frame.info
        return renderCurrent(frame)
    }
    /** 同一采样帧仅因UI调参重绘，不改写时间戳、不重复提交分析。 */
    override fun redraw(frame: VideoFrame): VideoFrame {
        lifecycleCheck()
        require(lastFrameInfo==frame.info) { "redraw requires the most recently processed frame geometry and timestamp" }
        return renderCurrent(frame)
    }
    private var licenseDenied = false
    private fun renderCurrent(frame: VideoFrame): VideoFrame {
        if (!BeautyLicensing.allowsSettings(settings)) {
            lastFrameAnalysis = FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
            if (!licenseDenied) { licenseDenied = true; try { onError(SecurityException("LICENSE_FEATURE_UNAVAILABLE")) } catch (_: Exception) {} }
            return frame
        }
        licenseDenied = false
        lastFrameAnalysis = snapshotForOutput(frame.info)
        val face = lastFrameAnalysis.result?.faces?.firstOrNull()
        return try { renderer.render(frame, settings, face) } catch (error: Exception) {
            lastFrameAnalysis = lastFrameAnalysis.copy(state=FaceAnalysisState.ERROR,result=null,
                error="${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            try { onError(error) } catch (_: Exception) { }
            frame
        }
    }
    /** 纹理模式由宿主提供与逻辑纹理画面一致的 RGBA 分析图，避免 SDK 隐式 GPU 读回。
     * 与 process 在同一线程按相同时间线调用；FrameInfo 必须描述实际分析图尺寸，不伪造尺寸。
     * allowScaledAnalysis=true 时可用低分辨率同视场分析图搭配高清纹理；归一化坐标直接复用。
     * 不同裁剪、不同宽高比须由宿主先统一视场，不能仅开启该选项绕过坐标映射。
     */
    fun submitAnalysis(frame: RgbaFrame): Boolean {
        lifecycleCheck()
        // 先登记几何信息，防止紧随其后的纹理帧把刚提交的新方向结果再次清空。
        val selectedMapping = mapping
        if (selectedMapping != null) require(selectedMapping.source.matches(frame.info)) {
            "Analysis geometry changed; update analysis mapping first"
        } else updateGeometry(frame.info)
        return offerAnalysis(frame)
    }
    /** 在session所属线程配置跨尺寸/裁剪映射；首次start后、提交新配置的帧前调用。
     * 每次调用均清空旧结果与在途代次（即使几何相同，可用于换摄像头/视场）。
     * 映射模式必须显式submitAnalysis，process不再重复分析输出RGBA；null恢复旧同视场模式。
     */
    fun setAnalysisMapping(value: AnalysisFrameMapping?) {
        lifecycleCheck()
        analysis?.reset(); mapping=value; geometry=null; mappedSource=null; mappedResult=null
        lastFrameInfo=null
        lastFrameAnalysis=FaceAnalysisSnapshot(FaceAnalysisState.STARTING)
    }
    /** 按源采样时间检查过期，再转换到目标坐标；禁止把渲染时间写回检测结果伪装新鲜度。 */
    private fun snapshotForOutput(info: FrameInfo): FaceAnalysisSnapshot {
        val selected=mapping ?: return analysisSnapshot(info)
        if (!selected.target.matches(info)) return FaceAnalysisSnapshot(FaceAnalysisState.PROCESSING)
        val snapshot=analysisSnapshot(selected.source.at(info.timestampNs))
        val result=snapshot.result ?: return snapshot
        // 同一检测结果只转换一次；渲染30/60fps不重复分配478点列表。
        if (mappedSource !== result) {
            mappedResult=FaceResult(selected.target.at(result.info.timestampNs),result.faces.mapNotNull(selected::map))
            mappedSource=result
        }
        val converted=checkNotNull(mappedResult)
        return snapshot.copy(state=if(converted.faces.isEmpty()) FaceAnalysisState.NO_FACE else FaceAnalysisState.READY,
            result=converted)
    }

    /** 检测尺寸、旋转或镜像变化，清空旧坐标与采样时间线，避免跨画面复用。 */
    private fun updateGeometry(info: FrameInfo) {
        val previous = geometry
        val compatible = previous == null || (previous.rotationDegrees == info.rotationDegrees &&
            previous.mirrored == info.mirrored && if (allowScaledAnalysis)
                kotlin.math.abs(previous.width.toDouble()/previous.height-info.width.toDouble()/info.height) < .02
            else previous.width == info.width && previous.height == info.height)
        if (!compatible) {
            resetAnalysis()
            lastFrameInfo = null
        }
        geometry = info
    }
    /** 按单调时间戳及采样间隔提交分析；重复时间或限频返回 false，模型支路最多保留一张最新待处理帧。 */
    private fun offerAnalysis(frame: RgbaFrame): Boolean = analysis?.submit(frame) ?: false
    /** 确认会话已启动且当前线程符合处理器契约。 */
    private fun lifecycleCheck() { lifecycle.requireRunning() }
    /** 只返回同方向、同镜像且未过期的结果；无脸或分析失败时返回空结果或 null。 */
    fun latestFaces(info: FrameInfo): FaceResult? = snapshotForOutput(info).result
    /** 停止处理并清空检测结果、画面几何和采样时间线；之后可重新启动。 */
    override fun stop() {
        lifecycle.stop(); resetAnalysis(); geometry = null; mapping = null; mappedSource=null; mappedResult=null
        lastFrameInfo=null
        lastFrameAnalysis = FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
    }
    /** 切摄像头时 stop/start，避免同尺寸摄像头沿用旧结果。 */
    override fun close() {
        lifecycle.close()
        if (!closed) {
            closed = true
            mappedSource=null; mappedResult=null; mapping=null
            if(ownsAnalysis) analysis?.close()
            renderer.close()
        }
    }
}
