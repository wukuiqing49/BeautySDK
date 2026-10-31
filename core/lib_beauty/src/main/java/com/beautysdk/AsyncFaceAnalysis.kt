package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** 单 worker 有界分析；可选保留一张最新待处理帧，旧待处理帧直接覆盖。 */
class AsyncFaceAnalysis @JvmOverloads constructor(
    private val factory: FaceAnalyzerFactory,
    private val maxSide: Int = 320,
    private val onError: (Exception) -> Unit = {},
    /** 仅当宿主保证检测图和输出纹理同视场时启用；方向和镜像仍必须一致。 */
    private val allowScaledFrames: Boolean = false,
    private val onTiming: (FaceAnalysisTiming) -> Unit = {},
    /** 开启后最多一张执行、一张待处理；适合宿主提供低分辨率分析支路。 */
    private val keepLatestPending: Boolean = false,
    /** 有效会话结果回调，输入只在回调期间有效；在发布锁内调用，禁止重入或执行推理。 */
    private val onResult: (RgbaFrame, List<FaceObservation>) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "beauty-face-analysis") }
    private var analyzer: FaceAnalyzer? = null
    private var analyzerGeneration = -1L
    private var busy = false
    private var closed = false
    private var generation = 0L
    private var geometry: FrameInfo? = null
    private var submittedNs = -1L
    private val history = FaceAnalysisHistory(allowScaledFrames = allowScaledFrames)
    // 最多一个在途任务；空闲时才覆盖该缓冲，reset也不能提前复用正在读取的内存。
    private var rgbaStorage: ByteBuffer? = null
    private var spareStorage: ByteBuffer? = null
    private data class Job(val image: RgbaFrame, val storage: ByteBuffer, val token: Long,
                           val submittedAt: Long, val copyNs: Long)
    private var pending: Job? = null
    private var lastCompletedNs: Long? = null
    /** 可跨线程取得会话代次；宿主在收帧时捕获并提交，阻止停止后的迟到帧进入新会话。 */
    fun generationToken(): Long = synchronized(lock) { generation }

    init { require(maxSide in 16..2048) }

    /** 回调内仅批量复制 RGBA 到复用原生内存；缩图、旋转和推理全部在 worker 执行。
     * 默认忙时在复制前丢帧；keepLatestPending 模式只覆盖最新候选。返回后宿主可立即回收源帧。
     */
    @JvmOverloads
    fun submit(frame: RgbaFrame, expectedGeneration: Long? = null): Boolean = synchronized(lock) {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return@synchronized false
        check(!closed) { "Analysis is closed" }
        if (expectedGeneration != null && expectedGeneration != generation) return false
        if (geometry?.let { !compatible(it,frame.info) } == true) resetLocked()
        geometry = frame.info
        require(frame.info.timestampNs > submittedNs) { "Analysis timestamps must strictly increase" }
        submittedNs = frame.info.timestampNs
        if (busy && !keepLatestPending) return false
        val copyStarted = System.nanoTime()
        val rowBytes = Math.multiplyExact(frame.info.width,4)
        val bytes = Math.multiplyExact(rowBytes,frame.info.height)
        val candidate = if (busy) spareStorage else rgbaStorage
        val storage = candidate?.takeIf { it.capacity() == bytes } ?: ByteBuffer.allocateDirect(bytes)
        if (busy) spareStorage = storage else rgbaStorage = storage
        storage.clear()
        val input = frame.readOnlyPixels()
        if (frame.rowStrideBytes == rowBytes) {
            input.limit(bytes); storage.put(input)
        } else {
            for (y in 0 until frame.info.height) {
                input.limit(input.capacity()); input.position(y * frame.rowStrideBytes)
                input.limit(y * frame.rowStrideBytes + rowBytes); storage.put(input)
            }
        }
        storage.flip()
        val image = RgbaFrame(frame.info,storage,rowBytes)
        val submittedAt = System.nanoTime()
        val copyNs = submittedAt - copyStarted
        val job = Job(image,storage,generation,submittedAt,copyNs)
        if (busy) { pending = job; return true }
        busy = true
        try {
            worker.execute {
                var current = job
                while (true) {
                    val image = current.image
                    val token = current.token
                    val submittedAt = current.submittedAt
                    val copyNs = current.copyNs
                    val started = System.nanoTime()
                    try {
                        // 已关闭或被换源替代的任务不再创建/重建模型。
                        if (synchronized(lock) { !closed && token == generation }) {
                            if (analyzerGeneration != token) {
                                analyzer?.close(); analyzer = null
                                analyzerGeneration = token
                            }
                            val detector = analyzer ?: factory.create().also { analyzer = it }
                            // 初始化可能很慢；返回后再次检查，防止关闭期间仍启动一次原生推理。
                            if (synchronized(lock) { !closed && token == generation }) {
                            val faces = detector.analyzeRgba(image,maxSide).toList()
                            val timing = synchronized(lock) {
                                if (!closed && token == generation) {
                                    onResult(image,faces)
                                    val finished = System.nanoTime()
                                    history.publish(image.info, faces, finished - started)
                                    val interval = lastCompletedNs?.let { finished-it }
                                    lastCompletedNs = finished
                                    FaceAnalysisTiming(image.info.timestampNs,copyNs,
                                        started-submittedAt,finished-started,interval,faces.size,detector.lastTiming)
                                } else null
                            }
                            // 回调不占用渲染/提交锁；诊断异常不能把成功检测改写为错误。
                            if (timing != null) try { onTiming(timing) } catch (_: Exception) { }
                            }
                        }
                    } catch (error: Exception) {
                        val active = synchronized(lock) {
                            if (!closed && token == generation) {
                                history.publish(image.info, emptyList(), System.nanoTime() - started,
                                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                                true
                            } else false
                        }
                        if (active) try { onError(error) } catch (_: Exception) { /* 错误回调不能破坏资源释放。 */ }
                    }
                    // 推理已返回才交换缓冲，提交线程不能覆盖模型正在读取的内存。
                    val next = synchronized(lock) {
                        val candidateJob = pending
                        pending = null
                        if (!closed && candidateJob != null && candidateJob.token == generation) {
                            spareStorage = current.storage
                            rgbaStorage = candidateJob.storage
                            candidateJob
                        } else {
                            busy = false
                            null
                        }
                    }
                    if (next == null) break
                    current = next
                }
            }
        } catch (error: RejectedExecutionException) {
            busy = false
            throw error
        }
        true
    }

    /** 取得同几何、非未来且未超过 maxAgeNs 的结果；未产生、失效或失败时返回 null。 */
    fun latest(info: FrameInfo, maxAgeNs: Long): FaceResult? = snapshot(info,maxAgeNs).result

    /** 同一锁内取得结果与诊断，避免界面显示的是新检测结果、渲染却使用上一份数据。 */
    fun snapshot(info: FrameInfo, maxAgeNs: Long): FaceAnalysisSnapshot = synchronized(lock) {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return@synchronized FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        require(maxAgeNs >= 0)
        if (closed) FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        else history.snapshot(info, maxAgeNs,
            if (busy) FaceAnalysisState.PROCESSING else FaceAnalysisState.STARTING)
    }

    /** 切摄像头（即使尺寸不变）必须显式调用；丢弃晚到结果，时间戳可重新开始。 */
    fun reset() = synchronized(lock) { check(!closed); resetLocked() }
    /** 持锁递增会话代次并清空结果；已在执行的旧任务完成后也不能发布。 */
    private fun resetLocked() {
        generation++; pending = null; history.clear(); geometry = null; submittedNs = -1; lastCompletedNs = null
    }

    private fun compatible(a: FrameInfo,b: FrameInfo): Boolean =
        a.rotationDegrees == b.rotationDegrees && a.mirrored == b.mirrored &&
            if (allowScaledFrames) kotlin.math.abs(a.width.toDouble()/a.height-b.width.toDouble()/b.height) < .02
            else a.width == b.width && a.height == b.height

    /** 不等待正在执行的推理；close 返回后不再发布结果，后端随后在 worker 上释放。 */
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            resetLocked()
            worker.execute {
                try { analyzer?.close() } catch (error: Exception) {
                    try { onError(error) } catch (_: Exception) { }
                } finally { analyzer = null; synchronized(lock) { rgbaStorage = null; spareStorage = null; pending = null } }
            }
            worker.shutdown()
        }
    }
}
