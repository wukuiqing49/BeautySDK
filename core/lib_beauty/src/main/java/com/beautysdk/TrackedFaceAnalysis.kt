package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 小尺寸原始方向灰度图；不保存Bitmap，调用者负责保持pixels在匹配期间不变。 */
open class MotionImage(val info: FrameInfo,val width: Int,val height: Int,val pixels: ByteBuffer)
/** 无状态图像匹配后端；prepare可能在检测/跟踪两线程调用，禁止共享可写缓存。 */
interface FaceMotionEstimator {
    fun prepare(frame: RgbaFrame,reuse: MotionImage? = null): MotionImage
    /** 失败返回null；只能根据新图像更新位置，禁止仅按时钟外推冒充跟踪。 */
    fun estimate(reference: MotionImage,current: MotionImage,face: FaceObservation): FaceObservation?
}

/** 单独的有界轻量跟踪worker；检测仍走原AsyncFaceAnalysis，两个worker均不阻塞引擎等待推理。
 * 相邻已处理帧接力匹配；晚到检测经最多8张灰度图重放后校正，不把旧坐标直接贴到新画面。
 * 最多使用300ms以内的检测校正，
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
    private data class Chain(val image: MotionImage,val face: FaceObservation,val source: Source)
    private val sources=ConcurrentHashMap<Long,Source>()
    @Volatile private var lastMatchedTimestampNs=-1L
    // 空结果是匹配失败屏障，不冒充模型无脸，也不继续显示失败前冻结的位置。
    private val trackingHistory=FaceAnalysisHistory(allowScaledFrames=allowScaledFrames)
    private val tracker=AsyncFaceAnalysis(FaceAnalyzerFactory { object: FaceAnalyzer {
        private val images=ArrayDeque<MotionImage>()
        private var chain: Chain? = null
        private var attemptedCorrection = -1L
        override val id="image-motion"
        override val capabilities=emptySet<FaceCapability>()
        override fun analyze(image: AnalysisImage): List<FaceObservation> = error("RGBA required")
        override fun analyzeRgba(frame: RgbaFrame,maxSide: Int): List<FaceObservation> {
            if(!enabled) return emptyList()
            // 先保留独占灰度图：检测尚未返回时的中间帧也用于晚到校正。仅worker持有，复用被淘汰槽。
            if(images.lastOrNull()?.info?.sameGeometry(frame.info)==false) {
                images.clear();chain=null;attemptedCorrection=-1L
            }
            val recycled=if(images.size==8) images.removeFirst() else null
            val current=motion.prepare(frame,recycled)
            images.addLast(current)
            val currentSeed=seed.get() ?: run { chain=null;return emptyList() }
            val age=frame.info.timestampNs-currentSeed.info.timestampNs
            if(age !in 0..maxCorrectionAgeNs || !currentSeed.info.sameGeometry(frame.info) || currentSeed.faces.size!=1) {
                chain=null;return emptyList()
            }
            var next=chain?.takeIf { it.source.continuity===currentSeed.continuity &&
                frame.info.timestampNs-it.source.timestampNs in 0..maxCorrectionAgeNs }
            // 新模型只尝试一次重放；失败时仍可用已有连续链跟踪，不能每帧重复昂贵的失败校正。
            if(currentSeed.info.timestampNs>attemptedCorrection) {
                attemptedCorrection=currentSeed.info.timestampNs
                var corrected=currentSeed.image?.let { Chain(it,currentSeed.faces.single(),
                    Source(currentSeed.info.timestampNs,currentSeed.continuity)) }
                for(image in images) {
                    val previous=corrected ?: break
                    if(image.info.timestampNs<=previous.image.info.timestampNs) continue
                    corrected=motion.estimate(previous.image,image,previous.face)?.let { Chain(image,it,previous.source) }
                }
                if(corrected?.image?.info?.timestampNs==frame.info.timestampNs) next=corrected
            }
            if(next!=null && next.image.info.timestampNs<frame.info.timestampNs) {
                // 不复用仍作为参考的灰度缓冲；即便worker丢掉中间待处理帧，也只推进一次。
                next=motion.estimate(next.image,current,next.face)?.let { Chain(current,it,next.source) }
            }
            chain=next
            // 新模型参考不等于人脸丢失：保留同一连续跟踪段内、实际匹配到的新帧。
            if(seed.get()?.continuity !== currentSeed.continuity) return emptyList()
            sources[frame.info.timestampNs]=next?.source ?: Source(currentSeed.info.timestampNs,currentSeed.continuity)
            if(sources.size>32) sources.keys.minOrNull()?.let(sources::remove)
            return next?.let { listOf(it.face) } ?: emptyList()
        }
        override fun close() { images.clear();chain=null }
    } },allowScaledFrames=allowScaledFrames,onError=onError,onTiming=onTrackingTiming,keepLatestPending=true,
        onResult={ frame,faces ->
            if(sources.containsKey(frame.info.timestampNs)) {
                if(faces.isNotEmpty())lastMatchedTimestampNs=frame.info.timestampNs
                trackingHistory.publish(frame.info,faces,0)
            }
        })
    // 仅检测worker读写；参考图必须在发布锁外完成，不能让取结果/提交帧等待灰度转换。
    // AsyncFaceAnalysis仍在发布锁内检查代次后才调用onResult，换源不会发布旧参考。
    private var preparedSeedImage: MotionImage? = null
    private val detector=AsyncFaceAnalysis(FaceAnalyzerFactory {
        val backend=factory.create()
        object:FaceAnalyzer by backend {
            override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<FaceObservation> {
                preparedSeedImage=null
                val faces=backend.analyzeRgba(frame,maxSide).toList()
                if(enabled && faces.size==1 && faces.single().landmarks.size>=478)
                    preparedSeedImage=motion.prepare(frame)
                return faces
            }
            override fun close() {
                try { backend.close() } finally { preparedSeedImage=null }
            }
        }
    },maxSide=maxSide,allowScaledFrames=allowScaledFrames,
        keepLatestPending=true,onError={ error -> seed.set(null); onError(error) },onTiming=onTiming,onResult={ frame,faces ->
            // 锁内只发布已准备的独占参考；原始RGBA在上面的同步分析返回前一直有效。
            val image=preparedSeedImage
            preparedSeedImage=null
            if(enabled) {
                val previous=seed.get()
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
    /** 两支路复制各自拥有的RGBA缓冲，各保留一张可替换最新待处理帧，不积压。 */
    @Synchronized fun submit(frame: RgbaFrame,expectedGeneration: Long? = null,runDetection: Boolean = true): Boolean {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return false
        if(expectedGeneration!=null && expectedGeneration!=detector.generationToken()) return false
        val accepted=if(runDetection) detector.submit(frame,expectedGeneration) else false
        if(enabled) tracker.submit(frame)
        return accepted
    }
    @Synchronized fun snapshot(info: FrameInfo,maxAgeNs: Long): FaceAnalysisSnapshot {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        val detected=detector.snapshot(info,maxAgeNs)
        if(!enabled) return detected
        // 模型错误或新无脸结果不可被旧跟踪结果覆盖。
        if(detected.state==FaceAnalysisState.ERROR || detected.state==FaceAnalysisState.NO_FACE ||
            detected.state==FaceAnalysisState.DISABLED) return detected
        val reference=seed.get() ?: return detected
        if(reference.faces.size!=1 || reference.info.timestampNs>info.timestampNs ||
            info.timestampNs-reference.info.timestampNs>maxCorrectionAgeNs) return detected
        // 匹配失败后的旧位置不能继续冻结；更晚的真实模型结果仍可恢复。
        // 此历史不记录匹配耗时，耗时由onTrackingTiming报告，避免伪造为0。
        val tracked=trackingHistory.snapshot(info,maxAgeNs).copy(durationNs=null)
        val result=tracked.result ?: return detected
        val source=sources[result.info.timestampNs] ?: return detected
        if(tracked.state==FaceAnalysisState.NO_FACE && source.continuity===reference.continuity &&
            result.info.timestampNs>(detected.result?.info?.timestampNs ?: -1L)) {
            // 辅助匹配失败不能否决比最后成功位置更新的真实模型结果。
            // detected已受原maxAgeNs/代次约束；不复活旧跟踪，不改时间戳，不延长期限。
            if(detected.state==FaceAnalysisState.READY &&
                (detected.result?.info?.timestampNs ?: -1L)>=lastMatchedTimestampNs) return detected
            return FaceAnalysisSnapshot(FaceAnalysisState.STALE,resultAgeNs=tracked.resultAgeNs)
        }
        // 按采样时间选更新的位置，而非按完成顺序切换；旧参考仍受原始300ms上限约束。
        if(tracked.state!=FaceAnalysisState.READY || source.continuity !== reference.continuity ||
            info.timestampNs-source.timestampNs !in 0..maxCorrectionAgeNs ||
            result.info.timestampNs<=reference.info.timestampNs) return detected
        return tracked.copy(tracked=true,detectionTimestampNs=source.timestampNs)
    }
    @Synchronized fun reset() {
        tracker.reset();detector.reset();seed.set(null);sources.clear();trackingHistory.clear();lastMatchedTimestampNs=-1L
    }
    @Synchronized override fun close() {
        tracker.close();detector.close();seed.set(null);sources.clear();trackingHistory.clear()
    }
}
