package com.beautysdk.demo.feature.app

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.beautysdk.*
import com.beautysdk.camera.*
import com.beautysdk.mediapipe.NativeFaceMotion
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import com.beautysdk.mediapipe.MediaPipeGestureAnalyzer
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import com.beautysdk.gpupixel.GpuPixelBeautyRenderer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GPU 提交耗时不是 GPU 执行时间；不含相机曝光到显示的端到端延迟。 */
data class GpuDemoStats(val width: Int,val height: Int,val lens: CameraLens,val backend: DemoBackend,
    val faceCount: Int,val anchoredLayers: Int,val processingMs: Long,val analysis: DemoFaceStatus,
    val renderedFrames: Long,val beautyBackend: DemoBeautyBackend = DemoBeautyBackend.BASIC_GL,val performance:BeautyPerformanceSnapshot?=null)

/** 单个 EGL context 同时绘制原图与效果图，避免跨 Surface 共享纹理及逐帧 Bitmap。
 * CameraX Preview -> OES -> BeautyEngineConnector/Pipeline -> FBO -> 屏幕；RGBA 分析走独立相机支路。
 * 所有 GL 资源仅在 GLSurfaceView 的线程创建、使用、释放。检测器由公共 SDK 的独立 worker 管理。
 */
class GpuBeautyPreview @JvmOverloads constructor(context: Context,attrs: AttributeSet? = null) : GLSurfaceView(context,attrs) {
    /** 主线程接收限频统计，只有数值及诊断，不交付Bitmap或可持有的GL纹理。 */
    var onImage: (GpuDemoStats) -> Unit = {}
    /** 主线程限频交付手势快照及一次性事件；只返回数据，不播放或修改视频特效。 */
    var onGestures: (GestureSnapshot, List<GestureEvent>) -> Unit = { _, _ -> }
    @Volatile private var gestureEffects:GestureEffectPack?=null
    fun setGestureEffects(pack:GestureEffectPack?) {gestureEffects=pack;requestRender()}
    /** 主线程诊断：背景是否请求、是否拿到当前有效蒙版。 */
    var onSceneStatus:(Boolean,Boolean)->Unit={_,_->}
    /** 主线程接收CameraX绑定状态；BOUND不代表已经显示第一帧。 */
    var onStatus: (CameraStatus) -> Unit = {}
    /** 主线程接收当前有效会话的错误，宿主负责展示及后续处理。 */
    var onError: (Exception) -> Unit = {}
    @Volatile private var request = PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,
        DemoEffects(.4f,.3f,false,false,false)), false, 0)
    var options: CameraDemoOptions
        get() = request.options
        @Synchronized set(value) { updatePreviewRequest(request.copy(options = value)) }
    /** 应用工坊资源包；null恢复内置选择，空包关闭挂件。UI线程调用，不在这里解码图片。 */
    @Synchronized fun setCustomStickerPack(pack: StickerPack?) {
        updatePreviewRequest(request.copy(customPack=pack)); requestRender()
    }
    /** 修改检测需求时使旧分析结果失效，普通美颜参数变化不重启检测。 */
    private fun updatePreviewRequest(next: PreviewRequest) {
        val old = request
        request = next.copy(revision = old.revision + if (old.needsAnalysis != next.needsAnalysis ||
            old.options.backend != next.options.backend ||
            old.options.mediaPipeGpu != next.options.mediaPipeGpu) 1 else 0)
        if (request.revision != old.revision) { analysis.reset(); foreground.reset() }
        if(old.needsGestures != next.needsGestures) gestures.setEnabled(next.needsGestures)
        foreground.setEnabled(next.needsSegmentation)
        analysis.setEnabled(next.needsAnalysis)
        desiredAssets=if(request.needsAnalysis && request.customPack==null) catalog.selectedIds(request.options.effects) else emptySet()
        requestAssets()
        analysis.setTrackingEnabled(next.options.frameTracking && next.options.backend==DemoBackend.MEDIAPIPE)
    }
    /** 最多一个IO任务，连续选材只保留最新需求，不随滑杆变化堆积任务。 */
    private fun requestAssets() {
        if(closed || desiredAssets==loadedAssets || !assetsRequested.compareAndSet(false,true)) return
        try { assetWorker.execute {
            try {
                val selected=desiredAssets
                catalog.preload(selected) { if(!closed) report(it,epoch.get()) }
                loadedAssets=selected
                if(!closed) requestRender()
            } finally {
                assetsRequested.set(false)
                if(!closed && desiredAssets!=loadedAssets) requestAssets()
            }
        } } catch(_: java.util.concurrent.RejectedExecutionException) { assetsRequested.set(false) }
    }
    @Volatile var effectIsMain = true
    var showOriginal: Boolean
        get() = request.original
        @Synchronized set(value) { updatePreviewRequest(request.copy(original = value)) }
    private var metricTimestamp=Long.MIN_VALUE
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var lastResultAgeNs: Long? = null
        private set
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var lens = CameraLens.FRONT
    @Volatile private var small = Rect()
    private val epoch = AtomicLong()
    private val uiPending = AtomicBoolean()
    private val errorPending = AtomicBoolean()
    private val main = ContextCompat.getMainExecutor(context)
    private val catalog = DemoStickerCatalog(context.applicationContext,deferLoading=true)
    private val assetsRequested=AtomicBoolean()
    @Volatile private var desiredAssets: Set<String> = emptySet()
    @Volatile private var loadedAssets: Set<String> = emptySet()
    private val assetWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val gpu = Renderer()
    // 仅可调试宿主输出聚合时序数据，不记录图像或人脸坐标。
    private val diagnosticLogging = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var lastAnalysisLogNs = 0L
    private val analysisWindow = AnalysisTimingWindow()
    private val trackingWindow = AnalysisTimingWindow()
    private var analysisExecution = "CPU"
    // 与直播 SDK 共用同一个运行时；相机只提交帧，模型始终由 SDK worker 创建/调用/释放。
    private val gestures=GestureAnalysisRuntime(MediaPipeGestureAnalyzer.factory(context),
        allowScaledFrames=true,onError={report(it,epoch.get())})
    private val foreground=MediaPipeForegroundMasks(context,MediaPipeForegroundMasks.Execution.GPU_PREFERRED) { report(it,epoch.get()) }.apply {
        allowScaledFrames=true
        // 位移补偿短测收益不稳定，默认关闭；公共SDK可显式开启作对照。
        enableMotionAlignment=false
        if(diagnosticLogging) {
            var started=System.nanoTime();var count=0;var copy=0L;var infer=0L
            var maxQueue=0L;var maxInfer=0L;var maxPrepare=0L;var maxInterval=0L
            var extract=0L;var convert=0L;var pack=0L;var alignment=0L;var aligned=0
            onTiming={timing->
                count++;copy+=timing.copyNs;infer+=timing.inferenceNs
                extract+=timing.extractionNs;convert+=timing.conversionNs;pack+=timing.packagingNs;alignment+=timing.alignmentNs
                if(timing.alignmentTimestampNs>timing.timestampNs)aligned++
                maxQueue=maxOf(maxQueue,timing.queueNs);maxInfer=maxOf(maxInfer,timing.inferenceNs)
                maxPrepare=maxOf(maxPrepare,timing.preparationNs);maxInterval=maxOf(maxInterval,timing.completedIntervalNs?:0)
                val now=System.nanoTime()
                if(now-started>=1_000_000_000L) {
                    Log.d("BeautySegmentation","execution=${timing.execution} n=$count copyMeanMs=${copy.toDouble()/count/1e6} queueMaxMs=${maxQueue/1e6} "+
                        "prepareMaxMs=${maxPrepare/1e6} inferMeanMs=${infer.toDouble()/count/1e6} inferMaxMs=${maxInfer/1e6} completedIntervalMaxMs=${maxInterval/1e6} "+
                        "extractMeanMs=${extract.toDouble()/count/1e6} convertMeanMs=${convert.toDouble()/count/1e6} packMeanMs=${pack.toDouble()/count/1e6} alignmentMeanMs=${alignment.toDouble()/count/1e6} aligned=$aligned")
                    started=now;count=0;copy=0;infer=0;maxQueue=0;maxInfer=0;maxPrepare=0;maxInterval=0;extract=0;convert=0;pack=0;alignment=0;aligned=0
                }
            }
        }
    }
    private val analysis = BeautyAnalysisRuntime(FaceAnalyzerFactory {
        analysisWindow.clear()
        lastAnalysisLogNs = System.nanoTime()
        analysisExecution = if (request.options.backend == DemoBackend.MEDIAPIPE && request.options.mediaPipeGpu) "GPU" else "CPU"
        if (diagnosticLogging) Log.d("BeautyAnalysis", "initializing backend=${request.options.backend} execution=$analysisExecution")
        when (request.options.backend) {
            DemoBackend.MEDIAPIPE -> MediaPipeFaceAnalyzer.factory(context, execution =
                if (request.options.mediaPipeGpu) MediaPipeFaceAnalyzer.Execution.GPU
                else MediaPipeFaceAnalyzer.Execution.CPU).create()
            else -> error("Analysis backend disabled")
        }
    }, motionEstimator = NativeFaceMotion(), allowScaledFrames = true, onTrackingTiming = { timing ->
        if (diagnosticLogging) trackingWindow.add(timing)
    }, onError = { report(it,epoch.get()) }, onTiming = { timing ->
        val now = System.nanoTime()
        if (diagnosticLogging) analysisWindow.add(timing)
        if (diagnosticLogging && now-lastAnalysisLogNs >= 1_000_000_000L) {
            Log.d("BeautyAnalysisWindow", "execution=$analysisExecution elapsedMs=${(now-lastAnalysisLogNs)/1e6} ${analysisWindow.summary()}")
            analysisWindow.clear()
            lastAnalysisLogNs = now
            Log.d("BeautyAnalysis", "copyMs=${timing.copyNs/1_000_000.0} " +
                "queueMs=${timing.queueNs/1_000_000.0} " +
                "prepareMs=${timing.backendTiming?.preparationNs?.div(1_000_000.0)} " +
                "inferMs=${timing.backendTiming?.inferenceNs?.div(1_000_000.0)} " +
                "totalMs=${timing.analysisNs/1_000_000.0} " +
                "intervalMs=${timing.resultIntervalNs?.div(1_000_000.0)} faces=${timing.faces}")
        }
    })
    private val camera: CustomCameraController = CustomCameraController(context,object : CameraFrameListener {
        /** 纯纹理基础美颜时跳过分析像素复制；只有检测方案启用且相机运行时才接收 RGBA。 */
        override fun acceptsBorrowedFrames() = true
        override fun needsFrame() = running && !closed && (request.needsAnalysis || request.needsSegmentation || request.needsGestures)
        /** 回调内仅批量复制借用帧；忙时丢帧，模型不阻塞相机或直播引擎回调。 */
        override fun onFrame(frame: CameraFrame) {
            val token = analysis.generationToken()
            val gestureToken = gestures.generationToken()
            if (!running || closed || (!request.needsAnalysis && !request.needsSegmentation && !request.needsGestures) || !cameraCurrent(frame)) return
            try {
                val info = FrameInfo(frame.width,frame.height,frame.timestampNs,frame.rotationDegrees,frame.mirrored)
                val input=RgbaFrame(info,frame.readOnlyPixels(),frame.rowStrideBytes)
                if(request.needsAnalysis) analysis.submit(input,token)
                if(request.needsSegmentation) foreground.submit(input)
                if(request.needsGestures) gestures.submit(input,gestureToken)
            } catch (error: Exception) { report(error,epoch.get()) }
        }
    },{ onStatus(it) },{ onError(it) })

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(false)
        setRenderer(gpu)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    /** 检查分析帧是否仍属于当前采集会话，防止旧镜头结果晚到。 */
    private fun cameraCurrent(frame: CameraFrame): Boolean = camera.isCurrent(frame)
    /** 最多保留一个待交付错误，将当前有效代次的异常转到主线程。 */
    private fun report(error: Exception,token: Long) {
        if (!errorPending.compareAndSet(false,true)) return
        main.execute {
            try { if (!closed && running && token == epoch.get()) onError(error) }
            finally { errorPending.set(false) }
        }
    }
    /** 接收相对预览区域的左上坐标小窗范围，复制边界并请求重绘，不处理像素。 */
    fun setEffectWindow(bounds: Rect) { small = Rect(bounds); requestRender() }
    /** 主线程绑定相机纹理预览和独立分析支路；GL资源由 GL 线程创建，旧代次结果立即失效。 */
    fun start(owner: LifecycleOwner,lens: CameraLens = CameraLens.FRONT) {
        check(!closed)
        running = true; this.lens = lens; analysis.reset(); foreground.reset(); gestures.reset(); epoch.incrementAndGet()
        renderedFrames = 0; lastResultAgeNs = null
        camera.startTexture(owner,Preview.SurfaceProvider { request ->
            val token = epoch.get()
            if (!running || closed) { request.willNotProvideSurface(); return@SurfaceProvider }
            queueEvent {
                if (!running || closed || token != epoch.get()) { request.willNotProvideSurface(); return@queueEvent }
                gpu.requestSurface(request,token)
            }
        },display?.rotation ?: Surface.ROTATION_0,CameraConfig(lens,640,480,30))
    }
    /** 只有相机确实发起切换后才递增代次及更新镜头，失败时保留原预览状态。 */
    fun switchCamera() {
        if (camera.trySwitchCamera()) {
            analysis.reset(); foreground.reset(); gestures.reset(); epoch.incrementAndGet()
            lens = if (lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
        }
    }
    /** 主线程停止相机并作废检测结果与统计，下一次重绘清空旧画面。 */
    fun stop() {
        running = false; epoch.incrementAndGet(); analysis.reset(); foreground.reset(); gestures.reset()
        renderedFrames = 0; lastResultAgeNs = null
        camera.stop(); requestRender()
    }
    /** 在 GL 队列安排资源清理后暂停视图；清理任务仍须检查 EGL 是否 current。 */
    override fun onPause() {
        queueEvent { gpu.release() }
        super.onPause()
    }
    /** 主线程幂等关闭采集和模型回调，再安排 GL 资源清理；不跨上下文直接删除纹理。 */
    fun close() {
        if (closed) return
        stop(); closed = true; camera.close(); analysis.close(); foreground.close(); gestures.close(); assetWorker.shutdownNow()
        queueEvent { gpu.release() }
    }
    /** GL线程内的相机纹理消费者和双画面绘制器，独占Shader、VAO、FBO及SurfaceTexture相关状态。 */
    private inner class Renderer : GLSurfaceView.Renderer {
        private var renderer: GlBeautyRenderer? = null
        private var pipeline: BeautyEnginePipeline? = null
        private var connector: BeautyEngineConnector<TextureFrame>? = null
        // 仅在本次onDrawFrame内消费借用输出，下次处理前不会缓存供异步使用。
        private var deliveredFrame: TextureFrame? = null
        private val cameraAdapter=object: BeautyEngineFrameAdapter<TextureFrame> {
            override fun read(frame: TextureFrame): VideoFrame = frame
            override fun write(frame: TextureFrame,output: VideoFrame): Boolean {
                deliveredFrame=output as TextureFrame
                return true
            }
            override fun passthrough(frame: TextureFrame): Boolean { deliveredFrame=frame; return true }
        }
        private var sceneRenderer: BeautySceneRenderer?=null
        private val gesturePlayer=GestureEffectPlayer()
        private var gestureGeneration=-1L
        private val gestureUiEvents=java.util.ArrayDeque<GestureEvent>()
        private var pipelineProcessor: BeautyRenderer? = null
        private var pipelineEpoch = -1L
        private var pipelineInfo: FrameInfo? = null
        private var gpuPixel: GpuPixelBeautyRenderer? = null
        private var gpuPixelFailure: String? = null
        private var previousBeautyBackend: DemoBeautyBackend? = null
        private var contextVersion = 0
        private var ownedContext = EGL14.EGL_NO_CONTEXT
        /** 判断当前 EGL context 是否仍是资源创建时的 context；仅处于 GL 线程不代表校验通过。 */
        private fun ownsCurrentContext() = ownedContext != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == ownedContext
        private var deferred: Pair<androidx.camera.core.SurfaceRequest,Long>? = null
        private var ready = false
        private var oesProgram = 0
        private var imageProgram = 0
        private val uniformLocations = mutableMapOf<Int,IntArray>()
        private var vao = 0
        private var input: SurfaceTexture? = null
        private var texture = 0
        private var size = android.util.Size(1,1)
        private var crop = Rect(0,0,1,1)
        private var rotation = 0
        private var hasCameraTransform = false
        private var sensorRotation = 0
        private var frontCamera = false
        private var token = -1L
        private var screenWidth = 1
        private var screenHeight = 1
        private val pending = AtomicBoolean()
        private var hasFrame = false
        private var transformationReady = false
        private var lastUiNs = 0L
        private var lastTrackingLogNs = 0L
        private var lastFrameLogNs=0L
        private val frameWindow=BeautyFrameWindow()
        private var motionFrames = 0
        private var maxDetectionAgeNs = 0L
        private val trackingCounts = IntArray(FaceAnalysisState.entries.size)
        private var maxResultAgeNs = 0L
        private val transform = FloatArray(16)
        private val normalize = FloatArray(16)
        private val compensated = FloatArray(16)
        private val displaySampling = FloatArray(16)
        private val displayMatrices = Array(8) { CameraTextureTransform.displayToRaw((it%4)*90,it >= 4) }
        /** 在新 EGL context 中创建效果渲染器及显示 Shader，处理待交付 Surface；旧句柄不可复用。 */
        override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
            // context 重建后旧句柄均已失效，不能在新 context 删除它们。
            gpuPixel?.abandonAfterContextLoss(); gpuPixel = null; gpuPixelFailure = null
            previousBeautyBackend = null
            sceneRenderer=null;pipeline=null; connector=null; deliveredFrame=null; pipelineProcessor=null; pipelineInfo=null; pipelineEpoch=-1
            contextVersion++
            ownedContext = EGL14.eglGetCurrentContext()
            ready = true
            try {
                renderer = GlBeautyRenderer(); input = null; texture = 0; hasFrame = false
                oesProgram = program(true); imageProgram = program(false)
                uniformLocations.clear()
                for (id in listOf(oesProgram,imageProgram)) uniformLocations[id] = intArrayOf(
                    GL.glGetUniformLocation(id,"image"),GL.glGetUniformLocation(id,"transform"))
                val ids = IntArray(1); GL.glGenVertexArrays(1,ids,0); vao = ids[0]
                deferred?.let { (request,generation) ->
                    deferred = null
                    if (running && !closed && generation == epoch.get()) provide(request,generation)
                    else request.willNotProvideSurface()
                }
            } catch (error: Exception) {
                release()
                report(error,epoch.get())
            }
        }
        /** 记录屏幕像素尺寸，后续按图像比例计算双画面视口。 */
        override fun onSurfaceChanged(gl: GL10?,width: Int,height: Int) { screenWidth = width; screenHeight = height }
        /** 有可用 EGL context 时交付纹理 Surface，否则保留最新请求并拒绝旧的待处理请求。 */
        fun requestSurface(request: androidx.camera.core.SurfaceRequest,generation: Long) {
            if (ready && ownsCurrentContext()) provide(request,generation)
            else { deferred?.first?.willNotProvideSurface(); deferred = request to generation }
        }
        /** 创建 OES纹理、SurfaceTexture和相机Surface，接收裁剪方向信息；等CameraX使用完成后才释放生产者。 */
        private fun provide(request: androidx.camera.core.SurfaceRequest,generation: Long) {
            val version = contextVersion
            val ids = IntArray(1); GL.glGenTextures(1,ids,0)
            val id = ids[0]
            GL.glBindTexture(0x8D65,id)
            GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            val source = SurfaceTexture(id)
            source.setDefaultBufferSize(request.resolution.width,request.resolution.height)
            val surface = Surface(source)
            input = source; texture = id; size = request.resolution; token = generation
            hasFrame = false; transformationReady = false; pending.set(false); crop = Rect(0,0,size.width,size.height)
            source.setOnFrameAvailableListener {
                if (running && !closed && generation == epoch.get()) { pending.set(true); requestRender() }
            }
            request.setTransformationInfoListener(main) { info ->
                val sensor = camera.sensorRotationDegrees
                val front = lens == CameraLens.FRONT
                queueEvent { if (input === source) {
                    crop = Rect(info.cropRect); rotation = info.rotationDegrees
                    hasCameraTransform = info.hasCameraTransform()
                    sensorRotation = sensor; frontCamera = front
                    transformationReady = true
                } }
            }
            request.provideSurface(surface,main) {
                surface.release()
                // CameraX 确认不再使用 Surface 后，才在拥有 context 的线程释放生产者。
                queueEvent {
                    source.setOnFrameAvailableListener(null); source.release()
                    if (input === source) { input = null; hasFrame = false }
                    if (ready && ownsCurrentContext() && version == contextVersion) GL.glDeleteTextures(1,intArrayOf(id),0)
                }
            }
        }
        /** 取最新相机纹理并读取有效检测快照，GPU合成后绘制主副画面；无GPU读回，不生成显示Bitmap。 */
        override fun onDrawFrame(gl: GL10?) {
            if (!ready || !ownsCurrentContext()) return
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
            GL.glViewport(0,0,screenWidth,screenHeight)
            GL.glClearColor(.04f,.045f,.065f,1f); GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            if (!running || closed || token != epoch.get()) return
            val source = input ?: return
            try {
                if (pending.getAndSet(false)) { source.updateTexImage(); hasFrame = true }
                if (!hasFrame || !transformationReady) return
                source.getTransformMatrix(transform)
                // SDK 的逻辑纹理以左上为原点；ST 矩阵负责生产者变换，额外处理 ViewPort crop。
                val sampling = if (hasCameraTransform)
                    CameraTextureTransform.compensateCamera(transform,sensorRotation,frontCamera,compensated) else transform
                CameraTextureTransform.cropped(sampling,size.width,size.height,crop.left,crop.top,crop.right,crop.bottom,normalize)
                val info = FrameInfo(crop.width(),crop.height(),source.timestamp.coerceAtLeast(0),rotation,lens == CameraLens.FRONT)
                val renderRequest = request
                val selected = renderRequest.options
                val original = renderRequest.original
                var snapshot = if (!renderRequest.needsAnalysis) FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
                    else analysis.snapshot(info,BeautyPipelineDefaults.MAX_RESULT_AGE_NS)
                val raw = TextureFrame(info,texture,TextureTarget.EXTERNAL_OES,normalize)
                val settings = catalog.settings(selected.effects,renderRequest.customPack).let {
                    if(selected.beautyBackend==DemoBeautyBackend.GPUPIXEL) it else it.copy(adjustments=BeautyAdjustments())
                }
                val started = System.nanoTime()
                if (previousBeautyBackend != selected.beautyBackend) {
                    // 后端切换释放上一套 GPUPixel FBO/LUT；重新选择允许重试初始化。
                    gpuPixel?.close(); gpuPixel = null; gpuPixelFailure = null
                    previousBeautyBackend = selected.beautyBackend
                }
                val gestureToken=gestures.generationToken()
                if(gestureGeneration!=gestureToken || pipelineEpoch!=token){gesturePlayer.reset();gestureUiEvents.clear();gestureGeneration=gestureToken}
                gesturePlayer.configure(if(original || !renderRequest.needsGestures)null else gestureEffects)
                val gestureSnapshot=gestures.snapshot(info)
                val frameGestureEvents=gestures.pollEvents(info)

                for(event in frameGestureEvents){if(gestureUiEvents.size==32)gestureUiEvents.removeFirst();gestureUiEvents.addLast(event)}
                // 相机与直播引擎复用同一入口；原图/后端失败也统一交回原始纹理。
                val effect = try {
                    val latchedFailure=!original && selected.beautyBackend==DemoBeautyBackend.GPUPIXEL && gpuPixelFailure!=null
                    val processor = when {
                        original || latchedFailure -> checkNotNull(renderer)
                        selected.beautyBackend==DemoBeautyBackend.BASIC_GL -> checkNotNull(renderer)
                        settings.smoothing==0f && settings.whitening==0f && settings.adjustments.isIdentity && settings.eyeMakeup==null -> checkNotNull(renderer)
                        else -> gpuPixel ?: GpuPixelBeautyRenderer(context.applicationContext).also { gpuPixel=it }
                    }
                    if(pipelineProcessor !== processor) {
                        connector?.close()
                        // 借用渲染器和跨线程分析运行时；切后端不关闭相机分析支路。
                        val composed=BeautySceneRenderer(object: BeautyRenderer {
                            override fun render(frame: VideoFrame,settings: BeautySettings,face: FaceObservation?) =
                                processor.render(frame,settings,face)
                            override fun close() = Unit
                        },foreground).also { sceneRenderer=it }
                        val active=BeautyEnginePipeline(renderer=GestureEffectRenderer(composed,gesturePlayer),textureAnalysis=false,sharedAnalysis=analysis,onError={ report(it,token) })
                        pipeline=active
                        connector=BeautyEngineConnector(cameraAdapter,active,onError={ report(it,token) }).also { it.start() }
                        pipelineProcessor=processor;pipelineInfo=null;pipelineEpoch=token
                    }
                    val active=checkNotNull(pipeline)
                    val entry=checkNotNull(connector)
                    val previous=pipelineInfo
                    if(pipelineEpoch!=token || previous!=null && (raw.info.timestampNs<previous.timestampNs ||
                        raw.info.width!=previous.width || raw.info.height!=previous.height ||
                        raw.info.rotationDegrees!=previous.rotationDegrees || raw.info.mirrored!=previous.mirrored ||
                        raw.info.timestampNs==previous.timestampNs && raw.info!=previous)) {
                        entry.resetSource();sceneRenderer?.reset();gesturePlayer.reset();gestureUiEvents.clear();pipelineInfo=null;pipelineEpoch=token
                    }
                    if(!original && renderRequest.needsGestures)gesturePlayer.accept(frameGestureEvents,info.timestampNs)
                    active.settings=if(original || latchedFailure) BeautySettings() else settings
                    deliveredFrame=null
                    val delivery=if(pipelineInfo==raw.info) entry.onRedraw(raw) else entry.onFrame(raw)
                    check(delivery.accepted) { "Camera frame delivery rejected" }
                    pipelineInfo=raw.info
                    snapshot=when {
                        original -> FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
                        latchedFailure -> snapshot.copy(state=FaceAnalysisState.ERROR,result=null,error=gpuPixelFailure)
                        else -> active.lastFrameAnalysis
                    }
                    // 分析错误只使依赖人脸的效果失效，不锁死独立的磨皮、背景或运镜。
                    checkNotNull(deliveredFrame).also { deliveredFrame=null }
                } catch (error: Exception) {
                    deliveredFrame=null
                    val message="${error.javaClass.simpleName}: ${error.message}"
                    if(selected.beautyBackend==DemoBeautyBackend.GPUPIXEL) {
                        val firstFailure=gpuPixelFailure==null
                        if(firstFailure) gpuPixelFailure=message
                        if(firstFailure) report(error,token)
                    } else report(error,token)
                    snapshot=snapshot.copy(state=FaceAnalysisState.ERROR,result=null,error=gpuPixelFailure ?: message)
                    raw
                }
                val elapsed = System.nanoTime()-started
                draw(if (effectIsMain && !original) effect else raw,Rect(0,0,screenWidth,screenHeight))
                val window = small
                if (!window.isEmpty) {
                    GL.glEnable(GL.GL_SCISSOR_TEST)
                    GL.glScissor(window.left,screenHeight-window.bottom,window.width(),window.height())
                    GL.glClear(GL.GL_COLOR_BUFFER_BIT)
                    GL.glDisable(GL.GL_SCISSOR_TEST)
                    draw(if (effectIsMain || original) raw else effect,window)
                }
                if(metricTimestamp!=info.timestampNs) { renderedFrames++;metricTimestamp=info.timestampNs }; lastResultAgeNs = snapshot.resultAgeNs
                if(diagnosticLogging) {
                    frameWindow.record(info.timestampNs,elapsed,snapshot)
                    val clock=System.nanoTime()
                    if(clock-lastFrameLogNs>=1_000_000_000L) {
                        Log.d("BeautyFrameWindow","${info.width}x${info.height} renderer=${selected.beautyBackend} ${frameWindow.drain()}")
                        if(renderRequest.needsSegmentation) {
                            val mask=foreground.snapshot(info)
                            Log.d("BeautyMaskAge","sourceAgeMs=${mask?.let{(info.timestampNs-it.info.timestampNs)/1e6}} ready=${mask!=null}")
                        }
                        lastFrameLogNs=clock
                    }
                }
                val now = System.nanoTime()
                if (diagnosticLogging && renderRequest.needsAnalysis) {
                    trackingCounts[snapshot.state.ordinal]++
                    if(snapshot.tracked) motionFrames++
                    snapshot.detectionTimestampNs?.let { maxDetectionAgeNs=maxOf(maxDetectionAgeNs,info.timestampNs-it) }
                    maxResultAgeNs = maxOf(maxResultAgeNs,snapshot.resultAgeNs ?: 0)
                    if (now-lastTrackingLogNs >= 1_000_000_000L) {
                        Log.d("BeautyTracking", "ready=${trackingCounts[FaceAnalysisState.READY.ordinal]} " +
                            "stale=${trackingCounts[FaceAnalysisState.STALE.ordinal]} " +
                            "noFace=${trackingCounts[FaceAnalysisState.NO_FACE.ordinal]} " +
                            "processing=${trackingCounts[FaceAnalysisState.PROCESSING.ordinal]} " +
                            "error=${trackingCounts[FaceAnalysisState.ERROR.ordinal]} tracked=$motionFrames maxDetectionAgeMs=${maxDetectionAgeNs/1e6} maxAgeMs=${maxResultAgeNs/1_000_000.0}")
                        Log.d("BeautyMotion", trackingWindow.drain())
                        trackingCounts.fill(0); motionFrames=0; maxDetectionAgeNs=0; maxResultAgeNs = 0; lastTrackingLogNs = now
                    }
                }
                if (now-lastUiNs > 250_000_000L && uiPending.compareAndSet(false,true)) {
                    lastUiNs = now
                    val state = when (snapshot.state) {
                        FaceAnalysisState.DISABLED -> DemoTrackingState.DISABLED
                        FaceAnalysisState.STARTING -> DemoTrackingState.STARTING
                        FaceAnalysisState.PROCESSING -> DemoTrackingState.PROCESSING
                        FaceAnalysisState.READY -> DemoTrackingState.READY
                        FaceAnalysisState.NO_FACE -> DemoTrackingState.NO_FACE
                        FaceAnalysisState.STALE -> DemoTrackingState.STALE
                        FaceAnalysisState.ERROR -> DemoTrackingState.ERROR
                    }
                    val displayedFace = snapshot.result?.faces?.firstOrNull()
                    val layers = settings.stickerPack?.layers.orEmpty().count { layer ->
                        val anchors = displayedFace?.anchors.orEmpty()
                        when (layer.anchor) {
                            StickerAnchor.EYES -> anchors.containsKey(FaceAnchor.LEFT_EYE) && anchors.containsKey(FaceAnchor.RIGHT_EYE)
                            StickerAnchor.UPPER_LIP -> anchors.containsKey(FaceAnchor.UPPER_LIP) && anchors.containsKey(FaceAnchor.LEFT_MOUTH) && anchors.containsKey(FaceAnchor.RIGHT_MOUTH)
                        }
                    }
                    val gestureEvents=gestureUiEvents.toList();gestureUiEvents.clear()
                    val backgroundEnabled=!original && settings.scene.needsSegmentation
                    val backgroundReady=backgroundEnabled && foreground.snapshot(info)!=null
                    val publicationEpoch = token
                    val stats = GpuDemoStats(info.width,info.height,lens,selected.backend,snapshot.result?.faces?.size ?: 0,
                        layers,elapsed/1_000_000,DemoFaceStatus(state,snapshot.resultAgeNs,snapshot.durationNs,displayedFace?.landmarks?.size ?: 0,snapshot.error,snapshot.tracked),renderedFrames,selected.beautyBackend,pipeline?.performance)
                    main.execute {
                        try { if (running && !closed && publicationEpoch == epoch.get()) {
                            onImage(stats)
                            onSceneStatus(backgroundEnabled,backgroundReady)
                            if(gestureToken==gestures.generationToken()) onGestures(gestureSnapshot,gestureEvents)
                        } }
                        finally { uiPending.set(false) }
                    }
                }
            } catch (error: Exception) { report(error,token) }
        }
        /** 按输出方向及镜像计算采样矩阵，以等比例视口显示OES或2D纹理，避免显示时重复旋转。 */
        private fun draw(frame: TextureFrame,bounds: Rect) {
            val info = frame.info
            val rotated = info.rotationDegrees % 180 != 0
            val ratio = if (rotated) info.height.toFloat()/info.width else info.width.toFloat()/info.height
            var width = bounds.width(); var height = (width/ratio).toInt()
            if (height > bounds.height()) { height = bounds.height(); width = (height*ratio).toInt() }
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
            GL.glViewport(bounds.left+(bounds.width()-width)/2,screenHeight-bounds.top-(bounds.height()+height)/2,width,height)
            val oes = frame.target == TextureTarget.EXTERNAL_OES
            val program = if (oes) oesProgram else imageProgram
            GL.glUseProgram(program); GL.glBindVertexArray(vao)
            GL.glDisable(GL.GL_BLEND); GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_SCISSOR_TEST)
            GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindSampler(0,0)
            GL.glBindTexture(if (oes) 0x8D65 else GL.GL_TEXTURE_2D,frame.textureId)
            val locations = uniformLocations.getValue(program)
            GL.glUniform1i(locations[0],0)
            CameraTextureTransform.multiply(frame.copyTransform(),displayMatrices[info.rotationDegrees/90+if (info.mirrored) 4 else 0],displaySampling)
            GL.glUniformMatrix4fv(locations[1],1,false,displaySampling,0)
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
        }
        /** 编译并链接原图/效果显示程序，失败时清理Shader与程序句柄并保留错误日志。 */
        private fun program(oes: Boolean): Int {
            val vertex = """#version 300 es
                out vec2 uv;
                void main() { vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
                uv=vec2(p.x,1.0-p.y); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }
            """.trimIndent()
            val fragment = """#version 300 es
                ${if (oes) "#extension GL_OES_EGL_image_external_essl3 : require" else ""}
                precision highp float;
                uniform ${if (oes) "samplerExternalOES" else "sampler2D"} image;
                uniform mat4 transform;
                in vec2 uv; out vec4 color;
                void main() { color=texture(image,(transform*vec4(uv,0.0,1.0)).xy); }
            """.trimIndent()
            /** 编译单个显示Shader，编译失败时释放句柄并抛出错误。 */
            fun shader(type: Int,code: String): Int {
                val id = GL.glCreateShader(type); GL.glShaderSource(id,code); GL.glCompileShader(id)
                val status = IntArray(1); GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,status,0)
                if (status[0] == 0) { val log=GL.glGetShaderInfoLog(id); GL.glDeleteShader(id); error(log) }
                return id
            }
            val v = shader(GL.GL_VERTEX_SHADER,vertex)
            var f = 0; var p = 0
            try {
                f = shader(GL.GL_FRAGMENT_SHADER,fragment)
                p = GL.glCreateProgram(); GL.glAttachShader(p,v); GL.glAttachShader(p,f); GL.glLinkProgram(p)
                val status=IntArray(1); GL.glGetProgramiv(p,GL.GL_LINK_STATUS,status,0)
                check(status[0] != 0) { GL.glGetProgramInfoLog(p) }
                return p
            } catch (error: Exception) { if (p != 0) GL.glDeleteProgram(p); throw error }
            finally { GL.glDeleteShader(v); if (f != 0) GL.glDeleteShader(f) }
        }
        /** 仅在资源所属EGL上下文仍current时显式删除GL对象；上下文失效后放弃旧句柄，由EGL回收，防止跨context误删。 */
        fun release() {
            deferred?.first?.willNotProvideSurface(); deferred = null
            if (!ready) return
            // queueEvent 只保证线程，不保证 EGL 仍 current。上下文已销毁时由 EGL 回收旧资源，禁止删新 context 的同号句柄。
            if (ownsCurrentContext()) {
                connector?.close()
                gpuPixel?.close()
                renderer?.close()
                if (oesProgram != 0) GL.glDeleteProgram(oesProgram)
                if (imageProgram != 0) GL.glDeleteProgram(imageProgram)
                GL.glDeleteVertexArrays(1,intArrayOf(vao),0)
            } else gpuPixel?.abandonAfterContextLoss()
            gpuPixel = null; gpuPixelFailure = null; previousBeautyBackend = null
            sceneRenderer=null;pipeline=null; connector=null; deliveredFrame=null;pipelineProcessor=null;pipelineInfo=null;pipelineEpoch=-1
            renderer = null; ready = false; ownedContext = EGL14.EGL_NO_CONTEXT
            oesProgram = 0; imageProgram = 0; vao = 0; hasFrame = false
            uniformLocations.clear()
            catalog.clear()
        }
    }
}
