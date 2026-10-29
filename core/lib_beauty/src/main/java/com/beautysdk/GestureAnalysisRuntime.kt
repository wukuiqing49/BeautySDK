package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** 可选手势支路，与人脸需求独立。默认关闭、单在途、忙时在复制前丢帧，不阻塞渲染等待模型。
 * submit 返回后即可回收借用输入；模型始终在独占worker创建/使用/释放，无逐帧Bitmap。
 * 宿主提交同视场 RGBA 小图，再用输出帧元数据 snapshot/pollEvents。纯纹理输入可显式借助宿主读回支路。
 */
class GestureAnalysisRuntime @JvmOverloads constructor(
    private val factory: GestureAnalyzerFactory,
    private val config: GestureConfig = GestureConfig(),
    private val maxSide: Int = 384,
    private val intervalNs: Long = 100_000_000L,
    private val maxAgeNs: Long = 300_000_000L,
    private val allowScaledFrames: Boolean = false,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val lock=Any()
    private val worker=Executors.newSingleThreadExecutor {r -> Thread(r,"beauty-gesture-analysis")}
    private val rules=GestureRuleEngine(config)
    private var analyzer: GestureAnalyzer?=null
    private var analyzerGeneration=-1L
    private var storage: ByteBuffer?=null
    private var generation=0L
    private var enabled=false
    private var closed=false
    private var busy=false
    private var geometry: FrameInfo?=null
    private var lastOffered=-1L
    private var lastAccepted=-1L
    private data class Completed(val result: GestureResult,val duration: Long,val error: String?)
    private val history=ArrayDeque<Completed>()
    private val events=ArrayDeque<GestureEvent>()
    init {require(maxSide in 16..2048 && intervalNs>=0 && maxAgeNs>0)}

    fun generationToken(): Long=synchronized(lock){generation}
    fun setEnabled(value: Boolean)=synchronized(lock) {
        check(!closed)
        if(enabled!=value){enabled=value;resetLocked()}
    }
    fun reset()=synchronized(lock){if(!closed)resetLocked()}
    private fun resetLocked() {
        generation++;history.clear();events.clear();rules.reset();geometry=null;lastOffered=-1;lastAccepted=-1
    }
    private fun compatible(a: FrameInfo,b: FrameInfo): Boolean =
        a.rotationDegrees==b.rotationDegrees && a.mirrored==b.mirrored &&
            if(allowScaledFrames)kotlin.math.abs(a.width.toDouble()/a.height-b.width.toDouble()/b.height)<.02
            else a.width==b.width && a.height==b.height

    /** 无需求、重复/逆序、限频或worker忙时返回false；不拷贝不入队。 */
    @JvmOverloads
    fun submit(frame: RgbaFrame,expectedGeneration: Long?=null): Boolean=synchronized(lock) {
        if (!BeautyLicensing.allows(LicensedFeature.GESTURE)) return@synchronized false
        check(!closed)
        if(!enabled || expectedGeneration!=null && expectedGeneration!=generation)return false
        if(geometry?.let {!compatible(it,frame.info)}==true)resetLocked()
        geometry=frame.info
        val t=frame.info.timestampNs
        if(t<=lastOffered)return false
        lastOffered=t
        if(busy || lastAccepted>=0 && t-lastAccepted<intervalNs)return false
        val row=Math.multiplyExact(frame.info.width,4)
        val bytes=Math.multiplyExact(row,frame.info.height)
        val copy=storage?.takeIf {it.capacity()==bytes}?:ByteBuffer.allocateDirect(bytes).also {storage=it}
        copy.clear()
        val input=frame.readOnlyPixels()
        if(frame.rowStrideBytes==row){input.limit(bytes);copy.put(input)}
        else for(y in 0 until frame.info.height) {
            input.limit(input.capacity());input.position(y*frame.rowStrideBytes);input.limit(y*frame.rowStrideBytes+row);copy.put(input)
        }
        copy.flip()
        val image=RgbaFrame(frame.info,copy,row)
        val token=generation
        busy=true;lastAccepted=t
        worker.execute {
            val started=System.nanoTime()
            var failure: Exception?=null
            try {
                if(analyzerGeneration!=token) {analyzer?.close();analyzer=null;analyzerGeneration=token}
                val detector=analyzer?:factory.create().also {analyzer=it}
                val hands=detector.analyzeRgba(image,maxSide).toList()
                require(hands.size<=2){"Gesture analyzer supports at most two hands"}
                synchronized(lock) {
                    if(!closed && enabled && token==generation) {
                        publish(Completed(GestureResult(image.info,hands),System.nanoTime()-started,null))
                        rules.process(image.info,hands).forEach { event ->
                            // 固定容量事件队列。过载时丢弃最旧事件，宿主应以快照校正交互状态。
                            if(events.size==32)events.removeFirst()
                            events.addLast(event)
                        }
                    }
                }
            } catch(e: Exception) {
                synchronized(lock) {
                    if(!closed && token==generation) {
                        history.clear();events.clear();rules.reset()
                        publish(Completed(GestureResult(image.info,emptyList()),System.nanoTime()-started,
                            "${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
                        failure=e
                    }
                }
            } finally {
                synchronized(lock){busy=false}
            }
            failure?.let {try {onError(it)}catch(_: Exception){ /* 回调不能终止worker。 */ }}
        }
        true
    }
    private fun publish(value: Completed) {
        if(history.size==8)history.removeFirst()
        history.addLast(value)
    }
    /** 不返回未来结果；保留少量历史应对分析快于纹理显示。 */
    fun snapshot(info: FrameInfo): GestureSnapshot=synchronized(lock) {
        if (!BeautyLicensing.allows(LicensedFeature.GESTURE)) return@synchronized GestureSnapshot(GestureAnalysisState.DISABLED)
        if(closed || !enabled)return GestureSnapshot(GestureAnalysisState.DISABLED)
        val entry=history.lastOrNull {it.result.info.timestampNs<=info.timestampNs && compatible(it.result.info,info)}
            ?:return GestureSnapshot(GestureAnalysisState.PROCESSING)
        val age=info.timestampNs-entry.result.info.timestampNs
        when {
            age>maxAgeNs -> GestureSnapshot(GestureAnalysisState.STALE,ageNs=age)
            entry.error!=null -> GestureSnapshot(GestureAnalysisState.ERROR,ageNs=age,durationNs=entry.duration,error=entry.error)
            else -> GestureSnapshot(if(entry.result.hands.isEmpty())GestureAnalysisState.NO_HANDS else GestureAnalysisState.READY,
                entry.result,age,entry.duration)
        }
    }
    /** 单消费者一次性交付，最多32项。不交付未来/过期/错误几何事件；不要在多个线程重复轮询。
     * END/CANCEL也受有效期约束。失联/STALE/ERROR/DISABLED时宿主必须自行取消拖动，不等待终止事件。
     */
    fun pollEvents(info: FrameInfo): List<GestureEvent> = synchronized(lock) {
        if (!BeautyLicensing.allows(LicensedFeature.GESTURE)) { events.clear(); return@synchronized emptyList() }
        if(closed || !enabled || geometry?.let {compatible(it,info)}!=true)return emptyList()
        val ready=mutableListOf<GestureEvent>()
        while(events.isNotEmpty()) {
            val event=events.first()
            if(event.timestampNs>info.timestampNs)break
            events.removeFirst()
            if(info.timestampNs-event.timestampNs<=maxAgeNs)ready+=event
        }
        ready
    }
    /** 不等待模型；之后不再发布结果。原生对象最终在所属worker释放。 */
    override fun close()=synchronized(lock) {
        if(!closed) {
            closed=true;enabled=false;resetLocked()
            worker.execute {
                try {analyzer?.close()} catch(e: Exception){try {onError(e)}catch(_: Exception){}}
                finally {analyzer=null;synchronized(lock){storage=null}}
            }
            worker.shutdown()
        }
    }
}
