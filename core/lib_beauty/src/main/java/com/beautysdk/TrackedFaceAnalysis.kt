package com.beautysdk

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 小尺寸原始方向灰度图；不保存Bitmap，调用者负责保持pixels在匹配期间不变。 */
class MotionImage(val info: FrameInfo,val width: Int,val height: Int,val pixels: ByteBuffer)
/** 无状态图像匹配后端；prepare可能在检测/跟踪两线程调用，禁止共享可写缓存。 */
interface FaceMotionEstimator {
    fun prepare(frame: RgbaFrame,reuse: MotionImage? = null): MotionImage
    /** 失败返回null；只能根据新图像更新位置，禁止仅按时钟外推冒充跟踪。 */
    fun estimate(reference: MotionImage,current: MotionImage,face: FaceObservation): FaceObservation?
}

/** 单独的有界轻量跟踪worker；检测仍走原AsyncFaceAnalysis，两个worker均不阻塞引擎等待推理。
 * 跟踪始终直接匹配最新检测参考图，不累积多帧漂移。最多使用300ms以内的检测校正，
 * 跟踪帧本身仍受调用者maxAgeNs限制；这不是无限保留旧坐标。默认用于实验对照。
 */
class TrackedFaceAnalysis(
    factory: FaceAnalyzerFactory,
    private val motion: FaceMotionEstimator,
    private val allowScaledFrames: Boolean = false,
    private val maxCorrectionAgeNs: Long = 300_000_000L,
    onError: (Exception) -> Unit = {},
    onTiming: (FaceAnalysisTiming) -> Unit = {},
    onTrackingTiming: (FaceAnalysisTiming) -> Unit = {},
    /** 检测最长边，由公共运行时统一传入，不影响输出纹理。 */
    maxSide: Int = 320,
) : AutoCloseable {
    private data class Seed(val image: MotionImage?,val info: FrameInfo,val faces: List<FaceObservation>, val continuity: Any)
    @Volatile private var enabled=false
    private val seed=AtomicReference<Seed?>()
    // 固定有界元数据索引，将跟踪帧关联到真实检测采样时间，不存储图像历史。
    private data class Source(val timestampNs: Long, val continuity: Any)
    private val sources=ConcurrentHashMap<Long,Source>()
    // 跟踪失败只表示本次无法匹配，不等于模型确认无脸；只保存成功结果，仍按原采样时间过期。
    private val successfulTracking=FaceAnalysisHistory(allowScaledFrames=allowScaledFrames)
    private val tracker=AsyncFaceAnalysis(FaceAnalyzerFactory { object: FaceAnalyzer {
        private var currentImage: MotionImage? = null
        override val id="image-motion"
        override val capabilities=emptySet<FaceCapability>()
        override fun analyze(image: AnalysisImage): List<FaceObservation> = error("RGBA required")
        override fun analyzeRgba(frame: RgbaFrame,maxSide: Int): List<FaceObservation> {
            if(!enabled) return emptyList()
            val currentSeed=seed.get() ?: return emptyList()
            val age=frame.info.timestampNs-currentSeed.info.timestampNs
            if(age !in 1..maxCorrectionAgeNs || !currentSeed.info.sameGeometry(frame.info) || currentSeed.faces.size!=1) return emptyList()
            val current=motion.prepare(frame,currentImage).also { currentImage=it }
            val face=motion.estimate(currentSeed.image ?: return emptyList(),current,currentSeed.faces.single())
                ?: return emptyList()
            // 新模型参考不等于人脸丢失：保留同一连续跟踪段内、实际匹配到的新帧。
            if(seed.get()?.continuity !== currentSeed.continuity) return emptyList()
            sources[frame.info.timestampNs]=Source(currentSeed.info.timestampNs,currentSeed.continuity)
            if(sources.size>32) sources.keys.minOrNull()?.let(sources::remove)
            return listOf(face)
        }
        override fun close() { currentImage=null }
    } },allowScaledFrames=allowScaledFrames,onError=onError,onTiming=onTrackingTiming,
        onResult={ frame,faces ->
            if(faces.isNotEmpty()) successfulTracking.publish(frame.info,faces,0)
        })
    private val detector=AsyncFaceAnalysis(factory,maxSide=maxSide,allowScaledFrames=allowScaledFrames,
        keepLatestPending=true,onError={ error -> seed.set(null); onError(error) },onTiming=onTiming,onResult={ frame,faces ->
            // 原始RGBA仍有效时生成独占的小灰度参考；无脸立即撤销旧参考。
            if(enabled) {
                val previous=seed.get()
                val image=if(faces.size==1 && faces.single().landmarks.size>=478) motion.prepare(frame) else null
                // 无脸、错误、几何变化后建立新连续段，旧匹配即使晚到也不能复活。
                val continuity=previous?.takeIf { image!=null && it.image!=null && it.info.sameGeometry(frame.info) }
                    ?.continuity ?: Any()
                seed.set(Seed(image,frame.info,faces,continuity))
            }
        })
    init { require(maxCorrectionAgeNs in 1..500_000_000L) }
    /** 可选实验功能；切换时必须同时清空两支路，避免旧跟踪结果穿越配置。 */
    @Synchronized fun setTrackingEnabled(value: Boolean) {
        if(enabled!=value) { enabled=value; reset() }
    }
    @Synchronized fun generationToken()=detector.generationToken()
    /** 两支路复制各自拥有的RGBA缓冲，跟踪忙时直接丢帧；不增加无界队列。 */
    @Synchronized fun submit(frame: RgbaFrame,expectedGeneration: Long? = null,runDetection: Boolean = true): Boolean {
        if(expectedGeneration!=null && expectedGeneration!=detector.generationToken()) return false
        val accepted=if(runDetection) detector.submit(frame,expectedGeneration) else false
        if(enabled) tracker.submit(frame)
        return accepted
    }
    @Synchronized fun snapshot(info: FrameInfo,maxAgeNs: Long): FaceAnalysisSnapshot {
        val detected=detector.snapshot(info,maxAgeNs)
        if(!enabled) return detected
        // 模型错误或新无脸结果不可被旧跟踪结果覆盖。
        if(detected.state==FaceAnalysisState.ERROR || detected.state==FaceAnalysisState.NO_FACE ||
            detected.state==FaceAnalysisState.DISABLED) return detected
        val reference=seed.get() ?: return detected
        if(reference.faces.size!=1 || reference.info.timestampNs>info.timestampNs ||
            info.timestampNs-reference.info.timestampNs>maxCorrectionAgeNs) return detected
        // 不用失败帧覆盖上一份成功坐标；无脸仍以模型结果及连续段为准。
        // 此历史不记录匹配耗时，耗时由onTrackingTiming报告，避免伪造为0。
        val tracked=successfulTracking.snapshot(info,maxAgeNs).copy(durationNs=null)
        val result=tracked.result ?: return detected
        val source=sources[result.info.timestampNs] ?: return detected
        // 按采样时间选更新的位置，而非按完成顺序切换；旧参考仍受原始300ms上限约束。
        if(tracked.state!=FaceAnalysisState.READY || source.continuity !== reference.continuity ||
            info.timestampNs-source.timestampNs !in 0..maxCorrectionAgeNs ||
            result.info.timestampNs<=reference.info.timestampNs) return detected
        return tracked.copy(tracked=true,detectionTimestampNs=source.timestampNs)
    }
    @Synchronized fun reset() {
        tracker.reset();detector.reset();seed.set(null);sources.clear();successfulTracking.clear()
    }
    @Synchronized override fun close() {
        tracker.close();detector.close();seed.set(null);sources.clear();successfulTracking.clear()
    }
}
