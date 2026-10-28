package com.beautysdk

/** SDK处理统计：CPU同步提交耗时，不包括GPU完成、相机曝光、编码或网络。
 * fps只计process成功交付的新采样，redraw不计入；启动首个统计窗口前为null。
 */
data class BeautyPerformanceSnapshot(val processedFrames:Long,val processedFps:Double?,val cpuSubmissionNs:Long,val averageCpuSubmissionNs:Long,val gpuExecutionNs:Long?=null)

/** 相机和直播引擎共用的轻量计数器；同一处理线程记录，其他线程可读取快照。不等待GPU。 */
class BeautyPerformanceMeter(private val clock:()->Long=System::nanoTime) {
    private var started=clock()
    private var frames=0L
    private var windowFrames=0L
    private var sum=0L
    private var last=0L
    private var average=0L
    private var fps:Double?=null
    @Synchronized fun reset() { started=clock();frames=0;windowFrames=0;sum=0;last=0;average=0;fps=null }
    @Synchronized fun record(cpuSubmissionNs:Long) {
        require(cpuSubmissionNs>=0)
        frames++;windowFrames++;sum+=cpuSubmissionNs;last=cpuSubmissionNs
        val now=clock();val elapsed=now-started
        if(elapsed>=1_000_000_000L) { fps=windowFrames*1e9/elapsed;average=sum/windowFrames;windowFrames=0;sum=0;started=now }
    }
    @Synchronized fun snapshot()=BeautyPerformanceSnapshot(frames,fps,last,average)
}
