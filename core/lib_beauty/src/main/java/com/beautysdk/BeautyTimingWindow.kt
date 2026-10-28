package com.beautysdk

import kotlin.math.ceil

/** 固定容量时序统计，不保存图像、关键点或无限增长的样本。
 * 均值/最大值覆盖本窗口全部完成任务；P95/P99取最近capacity个有效样本，日志显式报告样本数。
 * total为worker耗时，不能当作曝光到显示或远端编码延迟。
 */
class BeautyTimingWindow @JvmOverloads constructor(private val capacity: Int = 256) {
    init { require(capacity in 16..4096) }
    private val names=arrayOf("copy","queue","total","prepare","infer","interval")
    private val sums=LongArray(6)
    private val maxima=LongArray(6)
    private val counts=IntArray(6)
    private val samples=Array(6) { LongArray(capacity) }
    private var faces=0
    @Synchronized fun add(t: FaceAnalysisTiming) {
        fun record(i: Int,v: Long?) {
            if(v!=null && v>=0) {
                samples[i][counts[i]%capacity]=v
                sums[i]+=v;maxima[i]=maxOf(maxima[i],v);counts[i]++
            }
        }
        record(0,t.copyNs);record(1,t.queueNs);record(2,t.analysisNs)
        record(3,t.backendTiming?.preparationNs);record(4,t.backendTiming?.inferenceNs);record(5,t.resultIntervalNs)
        if(t.faces>0) faces++
    }
    @Synchronized fun summary(): String = "n=${counts[2]} faceResults=$faces " + names.indices.joinToString(" ") { i ->
        if(counts[i]==0) "${names[i]}Ms=unavailable" else {
            val sorted=samples[i].copyOf(minOf(capacity,counts[i])).apply { sort() }
            fun percentile(p: Double)=sorted[(ceil(sorted.size*p).toInt()-1).coerceAtLeast(0)]/1e6
            "${names[i]}MeanMs=${sums[i]/counts[i]/1e6} ${names[i]}MaxMs=${maxima[i]/1e6} " +
                "${names[i]}P95Ms=${percentile(.95)} ${names[i]}P99Ms=${percentile(.99)} ${names[i]}Samples=${sorted.size}"
        }
    }
    @Synchronized fun clear() { sums.fill(0);maxima.fill(0);counts.fill(0);faces=0 }
    @Synchronized fun drain(): String { val value=summary();clear();return value }
}
