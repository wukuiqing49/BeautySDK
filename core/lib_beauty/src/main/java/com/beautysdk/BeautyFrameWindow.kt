package com.beautysdk

import kotlin.math.ceil

/** 渲染线程固定容量诊断：区分视频新帧与同帧重绘，记录CPU提交耗时及实际绘制快照。
 * 不等待GPU、不读回纹理；FPS是本地消费帧率，不代表编码或远端帧率。
 */
class BeautyFrameWindow @JvmOverloads constructor(private val capacity: Int = 256) {
    init { require(capacity in 16..4096) }
    private val render=LongArray(capacity)
    private val ages=LongArray(capacity)
    private val states=IntArray(FaceAnalysisState.entries.size)
    private var count=0
    private var ageCount=0
    private var unique=0
    private var lastTimestamp = -1L
    private var firstVideoNs=0L
    private var lastVideoNs=0L
    private var tracked=0
    @Synchronized fun record(timestampNs: Long,submissionNs: Long,snapshot: FaceAnalysisSnapshot) {
        require(timestampNs>=0 && submissionNs>=0)
        if(timestampNs!=lastTimestamp) {
            val now=System.nanoTime()
            if(unique==0) firstVideoNs=now
            lastVideoNs=now;unique++;lastTimestamp=timestampNs
        }
        render[count%capacity]=submissionNs;count++
        snapshot.resultAgeNs?.takeIf { it>=0 }?.let { ages[ageCount%capacity]=it;ageCount++ }
        states[snapshot.state.ordinal]++
        if(snapshot.tracked) tracked++
    }
    @Synchronized fun drain(): String {
        fun percentile(values:LongArray,n:Int,p:Double):String {
            if(n==0) return "unavailable"
            val copy=values.copyOf(minOf(n,capacity)).apply { sort() }
            return (copy[(ceil(copy.size*p).toInt()-1).coerceAtLeast(0)]/1e6).toString()
        }
        val elapsed=(lastVideoNs-firstVideoNs).coerceAtLeast(1)
        val fps=if(unique<2) "unavailable" else ((unique-1)*1e9/elapsed).toString()
        val text="renders=$count videoFrames=$unique redraws=${count-unique} " +
            "localConsumeFps=$fps " +
            "cpuSubmitP95Ms=${percentile(render,count,.95)} cpuSubmitP99Ms=${percentile(render,count,.99)} " +
            "resultAgeP95Ms=${percentile(ages,ageCount,.95)} samples=${minOf(count,capacity)} tracked=$tracked " +
            FaceAnalysisState.entries.joinToString(" ") { "${it.name.lowercase()}=${states[it.ordinal]}" }
        count=0;ageCount=0;unique=0;firstVideoNs=0;lastVideoNs=0;tracked=0;states.fill(0)
        return text
    }
}
