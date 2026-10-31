package com.beautysdk.mediapipe

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import android.content.Context
import com.beautysdk.*
import java.nio.ByteBuffer
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/** CPU/GPU VIDEO 模式在 SDK 的有界 worker 上同步调用；不阻塞引擎线程，不叠加第二套异步队列。
 * 分析图按元数据转正，再把结果逆变换回原始像素空间。模型在 assets 中本地加载。
 */
class MediaPipeFaceAnalyzer(
    context: Context,
    modelAsset: String = "face_landmarker.task",
    val execution: Execution = Execution.CPU,
    /** 仅三维饰品宿主显式开启；旧调用不增加姿态矩阵输出。 */
    val output3D: Boolean,
) : FaceAnalyzer {
    @JvmOverloads constructor(context: Context, modelAsset: String = "face_landmarker.task",
        execution: Execution = Execution.CPU) : this(context,modelAsset,execution,false)
    /** SDK 自有枚举，不向宿主暴露 MediaPipe 类型；GPU 仍使用 RGB 输入，并非纹理零拷贝。 */
    enum class Execution { CPU, GPU }
    private val ownerThread = Thread.currentThread()
    private var closed = false
    /** GPU 必须在创建线程调用和释放；CPU 也遵守相同契约，防止并发覆盖复用缓冲。 */
    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Create, analyze and close on the same worker thread" }
        check(!closed) { "Analyzer is closed" }
    }
    override val id = "mediapipe-face-landmarker"
    override val capabilities = setOf(FaceCapability.BOUNDS, FaceCapability.EYES,
        FaceCapability.MOUTH_CORNERS, FaceCapability.UPPER_LIP, FaceCapability.DENSE_LANDMARKS) +
        if(output3D) setOf(FaceCapability.GEOMETRY_3D) else emptySet()
    private val detector = FaceLandmarker.createFromOptions(context.applicationContext,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset)
                .setDelegate(if (execution == Execution.GPU) Delegate.GPU else Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
            .setOutputFaceBlendshapes(false).setOutputFacialTransformationMatrixes(output3D).build())
    private val rgbInput = MediaPipeRgbInput()
    private var previousMs = -1L
    private var orientationPixels = ByteArray(0)
    /** 最近一次同步分析的分阶段耗时，仅在所属分析线程读取；不含采集和 RGBA 打包。 */
    var lastPreparationNs = 0L
        private set
    var lastInferenceNs = 0L
        private set
    /** 在所属分析线程执行 VIDEO 推理；输入先转正，输出478点及语义锚点再还原原始空间，不计算表情系数。 */
    override fun analyze(source: AnalysisImage): List<FaceObservation> {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return emptyList()
        checkOwner()
        val started = System.nanoTime()
        if (orientationPixels.size != source.pixels.size) orientationPixels = ByteArray(source.pixels.size)
        val orientation = AnalysisOrientation.upright(source, orientationPixels)
        val image = orientation.image
        // Tasks 0.10.32 的 AndroidPacketCreator 支持 RGB ByteBuffer。
        // 同步 VIDEO 返回、MPImage 关闭后才能复用输入；无需逐帧 Bitmap/ARGB IntArray。
        val buffer = rgbInput.prepare(image.pixels,image.width,image.height)
        return detect(buffer, image.width, image.height, source.info, started)
    }

    /** 直接借用引擎/相机 RGBA，单次转换到复用 RGB 原生缓冲；同步返回前不会释放输入。 */
    override fun analyzeRgba(frame: RgbaFrame): List<FaceObservation> = analyzeRgba(frame,320)

    override val lastTiming: FaceAnalyzerTiming
        get() = FaceAnalyzerTiming(lastPreparationNs,lastInferenceNs)

    /** 缩图规格变化才更换缓冲，所有操作仍在统一分析 worker 上执行。 */
    override fun analyzeRgba(frame: RgbaFrame, maxSide: Int): List<FaceObservation> {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return emptyList()
        checkOwner()
        val started = System.nanoTime()
        val buffer = rgbInput.prepare(frame,maxSide)
        return detect(buffer, rgbInput.width, rgbInput.height, frame.info, started)
    }

    /** MPImage 仅包装缓冲；同步模型读取结束并关闭包装后，下一帧才允许覆盖该内存。 */
    private fun detect(buffer: ByteBuffer, width: Int, height: Int, info: FrameInfo,
                       started: Long): List<FaceObservation> {
        val mpImage = ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
        val coordinates = AnalysisCoordinates(info)
        try {
                val timestampMs = maxOf(previousMs + 1, info.timestampNs / 1_000_000)
                previousMs = timestampMs
                lastPreparationNs = System.nanoTime() - started
                val inferenceStarted = System.nanoTime()
                val result = detector.detectForVideo(mpImage, timestampMs)
                lastInferenceNs = System.nanoTime() - inferenceStarted
                return result.faceLandmarks().mapIndexedNotNull { index, landmarks ->
                    val points = landmarks.map { FacePoint(it.x(), it.y()) }
                    if (points.size < 478) return@mapIndexedNotNull null
                    val left = points.minOf { it.x }.coerceIn(0f, 1f)
                    val top = points.minOf { it.y }.coerceIn(0f, 1f)
                    val right = points.maxOf { it.x }.coerceIn(0f, 1f)
                    val bottom = points.maxOf { it.y }.coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) return@mapIndexedNotNull null
                    val eyes = listOf(points[468], points[473]).sortedBy { it.x }
                    val mouth = listOf(points[61], points[291]).sortedBy { it.x }
                    FaceObservation(FaceBounds(left, top, right, bottom), mapOf(
                        FaceAnchor.LEFT_EYE to eyes[0], FaceAnchor.RIGHT_EYE to eyes[1],
                        FaceAnchor.NOSE to points[1], FaceAnchor.LEFT_MOUTH to mouth[0],
                        FaceAnchor.RIGHT_MOUTH to mouth[1], FaceAnchor.UPPER_LIP to points[0]), points,
                        if(output3D) FaceGeometry3D(landmarks.map { FacePoint3D(it.x(),it.y(),it.z()) },
                            info.timestampNs,result.facialTransformationMatrixes().orElse(emptyList()).getOrNull(index)) else null)
                }.map { coordinates.toRaw(it) }
        } finally { mpImage.close() }
    }
    /** 在分析线程关闭 FaceLandmarker，释放模型原生资源。 */
    override fun close() {
        check(Thread.currentThread() === ownerThread) { "Close on the analysis worker thread" }
        if (closed) return
        closed = true
        try { detector.close() }
        finally { rgbInput.clear(); orientationPixels = ByteArray(0) }
    }
    companion object {
        /** 保存 applicationContext 并返回模型工厂；实际检测器在调用 create 的分析线程创建。 */
        @JvmStatic @JvmOverloads
        fun factory(context: Context, modelAsset: String = "face_landmarker.task",
                    execution: Execution = Execution.CPU): FaceAnalyzerFactory = factory(context,modelAsset,execution,false)

        @JvmStatic @JvmOverloads
        fun factory(context: Context, modelAsset: String = "face_landmarker.task",
                    execution: Execution = Execution.CPU, output3D: Boolean): FaceAnalyzerFactory {
            val app = context.applicationContext
            return FaceAnalyzerFactory { MediaPipeFaceAnalyzer(app, modelAsset, execution, output3D) }
        }
    }
}
