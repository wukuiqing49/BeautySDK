package com.beautysdk.demo.feature.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.beautysdk.*
import com.beautysdk.camera.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 相机演示的整体配置快照，将检测后端与效果参数一起替换，避免读取部分更新。 */
data class CameraDemoOptions(val backend: DemoBackend, val effects: DemoEffects,
    val beautyBackend: DemoBeautyBackend = DemoBeautyBackend.BASIC_GL,
    /** 实时预览显式优先GPU；3D/局部美妆另有自动GPU优先。静态图和旧CPU参考控制器仍用CPU。 */
    val mediaPipeGpu: Boolean = false,
    /** 图像匹配跟踪为可选实验配置，不影响检测后端选择。 */
    val frameTracking: Boolean = false,
    /** 可选独立手势支路，默认关闭。 */
    val gestureEnabled: Boolean = false,
    /** 宿主演示显式观察人脸，即使没有依赖人脸的效果。 */
    val observeFaces: Boolean = false,
    /** 显式关闭时，效果也不能隐式重新开启人脸分析。 */
    val faceAnalysisEnabled: Boolean = true,
    /** 可选刚性三维饰品，仅GPU主线支持。 */
    val sticker3D: Sticker3D? = null)
/** 美颜执行后端与人脸检测后端独立；CPU 参考控制器不使用此 GPU 选项。 */
enum class DemoBeautyBackend { BASIC_GL, GPUPIXEL }
/** 页面诊断状态，区分未启用、加载/处理中、有效、无人脸、过期与异常。 */
enum class DemoTrackingState { DISABLED, STARTING, PROCESSING, READY, NO_FACE, STALE, ERROR }
/** 页面只接收调试数值，不通过 feature 的公共类型泄漏底层算法/SDK对象。 */
data class DemoFaceStatus(val state: DemoTrackingState,val resultAgeNs: Long?,val durationNs: Long?,
    val landmarkCount: Int,val error: String?,val tracked: Boolean = false)
/** CPU参考相机输出：显示Bitmap、采集尺寸/镜头及本帧实际使用的分析诊断。 */
data class CameraDemoImage(val bitmap: Bitmap, val width: Int, val height: Int,
    val lens: CameraLens, val faceCount: Int, val processingMs: Long,
    val backend: DemoBackend, val analysis: DemoFaceStatus, val anchoredLayers: Int)

/** 相机与SDK之间的测试适配层。CPU参考渲染请求320x240/最多30次每秒，不代表生产直播管线。 */
class CameraBeautyController(
    context: Context,
    private val onImage: (CameraDemoImage) -> Unit,
    onStatus: (CameraStatus) -> Unit,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    companion object {
        // 保留异步回归使用的有效期；相机 Demo 使用同帧分析，不再把旧坐标画到新画面。
        const val MAX_RESULT_AGE_NS = 500_000_000L
    }
    private val app = context.applicationContext
    private val stickers = DemoStickerCatalog(app)
    private val main = Handler(Looper.getMainLooper())
    private val displayEpoch = AtomicLong()
    private val uiPending = AtomicBoolean()
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile var options = CameraDemoOptions(DemoBackend.MEDIAPIPE,DemoEffects(.4f,.3f,false,true,true))
    private var session: BeautySession? = null
    private var sessionId = -1L
    private var backend: DemoBackend? = null
    private val camera: CustomCameraController = CustomCameraController(app,object : CameraFrameListener {
        /** CPU 参考路径的单帧入口：按会话选择检测器，合成并转换 Bitmap；最多等待一个主线程显示回调。 */
        override fun onFrame(frame: CameraFrame) {
            if (!running || closed || !uiPending.compareAndSet(false,true)) return
            val generation = displayEpoch.get()
            try {
                val selected = options
                if (frame.sessionId != sessionId || backend != selected.backend) {
                    releaseSession()
                    val factory = when (selected.backend) {
                        DemoBackend.BEAUTY_ONLY -> null
                        DemoBackend.MEDIAPIPE -> MediaPipeFaceAnalyzer.factory(app)
                    }
                    session = BeautySession(factory = factory,maxResultAgeNs = MAX_RESULT_AGE_NS,
                        onError = { report(it,generation) }).also { it.start() }
                    sessionId = frame.sessionId; backend = selected.backend
                }
                val effects = selected.effects
                val processor = checkNotNull(session)
                processor.settings = stickers.settings(effects)
                val info = FrameInfo(frame.width,frame.height,frame.timestampNs,frame.rotationDegrees,frame.mirrored)
                val input = RgbaFrame(info,frame.readOnlyPixels(),frame.rowStrideBytes)
                val started = System.nanoTime()
                val output = processor.process(input) as RgbaFrame
                val bitmap = FrameBitmap.create(output)
                val snapshot = processor.lastFrameAnalysis
                val face = snapshot.result?.faces?.firstOrNull()
                val anchored = processor.settings.stickerPack?.layers.orEmpty().count { layer ->
                    val anchors = face?.anchors.orEmpty()
                    when (layer.anchor) {
                        StickerAnchor.EYES -> anchors.containsKey(FaceAnchor.LEFT_EYE) && anchors.containsKey(FaceAnchor.RIGHT_EYE)
                        StickerAnchor.UPPER_LIP -> anchors.containsKey(FaceAnchor.UPPER_LIP) &&
                            anchors.containsKey(FaceAnchor.LEFT_MOUTH) && anchors.containsKey(FaceAnchor.RIGHT_MOUTH)
                    }
                }
                val result = CameraDemoImage(bitmap,frame.width,frame.height,frame.lens,
                    snapshot.result?.faces?.size ?: 0,(System.nanoTime()-started)/1_000_000,
                    selected.backend,DemoFaceStatus(when(snapshot.state) {
                        FaceAnalysisState.DISABLED -> DemoTrackingState.DISABLED
                        FaceAnalysisState.STARTING -> DemoTrackingState.STARTING
                        FaceAnalysisState.PROCESSING -> DemoTrackingState.PROCESSING
                        FaceAnalysisState.READY -> DemoTrackingState.READY
                        FaceAnalysisState.NO_FACE -> DemoTrackingState.NO_FACE
                        FaceAnalysisState.STALE -> DemoTrackingState.STALE
                        FaceAnalysisState.ERROR -> DemoTrackingState.ERROR
                    },snapshot.resultAgeNs,snapshot.durationNs,face?.landmarks?.size ?: 0,snapshot.error),anchored)
                main.post {
                    try {
                        if (!closed && running && displayEpoch.get() == generation && camera.isCurrent(frame)) onImage(result)
                        else bitmap.recycle()
                    } finally { uiPending.set(false) }
                }
            } catch (error: Exception) { uiPending.set(false); report(error,generation) }
        }
        /** 在相机分析线程结束旧会话，关闭对应模型；控制器关闭时清空素材缓存。 */
        override fun onStreamStopped() { releaseSession(); if (closed) stickers.clear() }
    },onStatus,onError)

    /** 主线程启动 CPU 相机参考链路，递增显示代次以作废旧回调；实时GPU页面不使用此入口。 */
    fun start(owner: LifecycleOwner,preview: PreviewView,lens: CameraLens = CameraLens.FRONT) {
        check(!closed)
        running = true; displayEpoch.incrementAndGet()
        camera.start(owner,preview,CameraConfig(lens,320,240,30))
    }
    /** 主线程请求切镜头并使旧显示回调失效。 */
    fun switchCamera() { displayEpoch.incrementAndGet(); camera.switchCamera() }
    /** 停止采集并递增显示代次；已经排到主线程的旧图像不会再显示。 */
    fun stop() { running = false; displayEpoch.incrementAndGet(); camera.stop() }
    /** 将错误切换到主线程，只交付当前仍有效的运行代次。 */
    private fun report(error: Exception,generation: Long) {
        main.post { if (!closed && running && displayEpoch.get() == generation) onError(error) }
    }
    /** 在相机处理线程释放美颜会话，清空镜头会话标识和后端记录。 */
    private fun releaseSession() { session?.close(); session = null; sessionId = -1; backend = null }
    /** 幂等关闭相机及当前处理链路，禁止后续启动。 */
    override fun close() {
        if (!closed) { closed = true; running = false; displayEpoch.incrementAndGet(); camera.close() }
    }
}

/** CPU参考结果到Android显示图片的适配器，GPU实时页面不经过此转换。 */
internal object FrameBitmap {
    /** 将 CPU RGBA 输出转为显示 Bitmap，并按元数据转正和镜像；仅用于参考路径。 */
    fun create(frame: RgbaFrame): Bitmap {
        val info = frame.info; val bytes = frame.readOnlyPixels()
        val colors = IntArray(info.width*info.height)
        for (y in 0 until info.height) for (x in 0 until info.width) {
            val i = y*frame.rowStrideBytes+x*4
            val r = bytes.get(i).toInt() and 255; val g = bytes.get(i+1).toInt() and 255
            val b = bytes.get(i+2).toInt() and 255; val a = bytes.get(i+3).toInt() and 255
            colors[y*info.width+x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val raw = Bitmap.createBitmap(colors,info.width,info.height,Bitmap.Config.ARGB_8888)
        if (info.rotationDegrees == 0 && !info.mirrored) return raw
        val matrix = Matrix().apply {
            postRotate(info.rotationDegrees.toFloat())
            if (info.mirrored) postScale(-1f,1f)
        }
        val result = Bitmap.createBitmap(raw,0,0,info.width,info.height,matrix,true)
        if (result !== raw) raw.recycle()
        return result
    }
}
