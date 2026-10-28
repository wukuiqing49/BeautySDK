package com.beautysdk.mediapipe

import android.content.Context
import com.beautysdk.*
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** 分割耗时仅含本支路，不是端到端视频延迟。采样时间保留原始视频时钟。 */
data class ForegroundMaskTiming(val timestampNs:Long,val copyNs:Long,val queueNs:Long,val preparationNs:Long,
    val inferenceNs:Long,val postprocessNs:Long,val completedIntervalNs:Long?,
    val execution:MediaPipeForegroundMasks.Execution=MediaPipeForegroundMasks.Execution.CPU,
    /** postprocess的细分：模型结果提取、原生转换、不可变快照；均不含推理。 */
    val extractionNs:Long=0,val conversionNs:Long=0,val packagingNs:Long=0,
    val alignmentNs:Long=0,val alignmentConfidence:Float=0f,val alignmentTimestampNs:Long=timestampNs)

/** 本地人像分割支路。一个处理槽、一个可替换的最新待处理槽，不积压、不等待推理。
 * 可提交相机/引擎RGBA或已有RGB小图；纯纹理宿主复用GlTextureAnalysisBridge后提交RGB。
 * RGB小图复制后调用返回；模型创建/推理/关闭始终在同一worker。结果与人脸分析独立。
 */
class MediaPipeForegroundMasks(context:Context,private val onError:(Exception)->Unit = {}) : ForegroundMaskProvider,AutoCloseable {
    /** CPU保持兼容默认；GPU仍需RGB输入与置信度输出，不代表纹理零拷贝。 */
    enum class Execution {CPU,GPU,GPU_PREFERRED}
    private var execution=Execution.CPU
    private var effectiveExecution=Execution.CPU
    constructor(context:Context,execution:Execution,onError:(Exception)->Unit = {}):this(context,onError){this.execution=execution}
    private val app=context.applicationContext
    private val worker=Executors.newSingleThreadExecutor()
    private class InputSlot {
        val rgb=MediaPipeRgbInput()
        var oriented=ByteArray(0)
        lateinit var buffer:ByteBuffer
        lateinit var info:FrameInfo
        var generation=0L
        var copyNs=0L
        var enqueuedNs=0L
    }
    private val slots=Array(2){InputSlot()}
    private var active:InputSlot?=null
    private var pending:InputSlot?=null
    private var scheduled=false
    private var lastSubmitted:Long?=null
    private var lastCompletedNs:Long?=null
    /** 在分割worker回调，只提供耗时与时间戳，不泄露图像。宿主应限频记录。 */
    @Volatile var onTiming:(ForegroundMaskTiming)->Unit = {}
    private val lock=Any()
    private var generation=0L
    private var closed=false
    private var enabled=false
    private var result:ForegroundMask?=null
    private var unalignedResult:ForegroundMask?=null
    /** 仅同视场、等宽高比宿主可显式开启；默认继续严格拒绝不同尺寸。 */
    var allowScaledFrames=false
    private var mappedSource:ForegroundMask?=null
    private var mappedResult:ForegroundMask?=null
    private var segmenter:ImageSegmenter?=null
    private val maskConverter=NativeMaskConverter()
    /** 实验性有校验的全局平移补偿。默认关闭，不替代非刚性光流或人像matting模型。 */
    @Volatile var enableMotionAlignment=false
    private var motionInput:ByteBuffer?=null
    /** 开关变化清除旧结果和在途发布，不影响人脸分析器。 */
    fun setEnabled(value:Boolean) = synchronized(lock) {
        if(enabled!=value) { enabled=value;invalidate() }
    }
    /** 换源、停机、尺寸变化由宿主显式调用；旧任务完成后不会发布。 */
    fun reset() = synchronized(lock) { invalidate() }
    private fun invalidate() {
        generation++;result=null;unalignedResult=null;pending=null;lastSubmitted=null;lastCompletedNs=null
        mappedSource=null;mappedResult=null
    }
    override fun snapshot(info:FrameInfo):ForegroundMask? = synchronized(lock) {
        val latest=result
        // 独立RGBA小图可能比当前视频纹理更新：回到同一次模型的原始像素，避免提前应用位移或闪回原背景。
        val source=if(latest!=null && info.timestampNs<latest.alignmentTimestampNs)unalignedResult else latest
        if(closed || !enabled || source==null)return@synchronized null
        if(source.isUsable(info))return@synchronized source
        if(!allowScaledFrames)return@synchronized null
        val cached=mappedResult
        if(mappedSource===source && cached?.isUsable(info)==true)return@synchronized cached
        source.forScaledOutput(info)?.also {mappedSource=source;mappedResult=it}
    }
    /** 借用RGBA在回调返回前经一次C++融合缩小/转正，槽位独占复用；不保留相机/引擎帧。
     * sourceInfo可声明同视场高清尺寸，但方向、镜像必须与输入一致。
     */
    @JvmOverloads fun submit(frame:RgbaFrame,sourceInfo:FrameInfo=frame.info):Boolean {
        require(sourceInfo.rotationDegrees==frame.info.rotationDegrees && sourceInfo.mirrored==frame.info.mirrored)
        return enqueue(sourceInfo) {slot->slot.buffer=slot.rgb.prepare(frame,256)}
    }
    /** RGB小图已由宿主拥有；同步复制到待处理槽，忙时只替换待处理帧。 */
    fun submit(image:AnalysisImage):Boolean {
        require(maxOf(image.width,image.height)<=256)
        return enqueue(image.info) {slot->
            if(slot.oriented.size!=image.pixels.size)slot.oriented=ByteArray(image.pixels.size)
            val upright=AnalysisOrientation.upright(image,slot.oriented).image
            slot.buffer=slot.rgb.prepare(upright.pixels,upright.width,upright.height)
        }
    }
    private fun enqueue(info:FrameInfo,copy:(InputSlot)->Unit):Boolean = synchronized(lock) {
        if(closed || !enabled || lastSubmitted?.let{info.timestampNs<=it}==true)return false
        // 推理只读取active；生产者只能覆写pending，固定两槽，不创建帧历史。
        val slot=pending ?: slots.first{it!==active}
        pending=null
        val started=System.nanoTime()
        copy(slot)
        slot.info=info;slot.generation=generation
        slot.enqueuedNs=System.nanoTime();slot.copyNs=slot.enqueuedNs-started
        pending=slot;lastSubmitted=info.timestampNs
        if(!scheduled) {scheduled=true;worker.execute{drain()}}
        true
    }
    private fun drain() {
        while(true) {
            val slot=synchronized(lock) {
                val next=pending
                if(closed || !enabled || next==null) {scheduled=false;return}
                pending=null;active=next;next
            }
            try {process(slot)} catch(error:Exception) {
                if(synchronized(lock){!closed&&enabled&&generation==slot.generation})onError(error)
            } finally {synchronized(lock){active=null}}
        }
    }
    /** 模型始终在同一worker创建/调用/关闭；待处理槽可在推理期间更新。 */
    private fun process(slot:InputSlot) {
        if(synchronized(lock){closed||!enabled||slot.generation!=generation})return
        val queueNs=System.nanoTime()-slot.enqueuedNs
        val preparing=System.nanoTime()
        val detector=segmenter ?: createSegmenter().also {segmenter=it}
        val image=ByteBufferImageBuilder(slot.buffer,slot.rgb.width,slot.rgb.height,MPImage.IMAGE_FORMAT_RGB).build()
        try {
            val inferStarted=System.nanoTime()
            val masks=detector.segment(image).confidenceMasks().orElseThrow{IllegalStateException("Missing foreground mask")}
            val inferred=System.nanoTime()
            try {
                check(masks.size==1){"Expected single person confidence channel"}
                val mask=masks[0]
                val extracted=ByteBufferExtractor.extract(mask)
                val extractionCompleted=System.nanoTime()
                val info=slot.info
                val swap=info.rotationDegrees==90||info.rotationDegrees==270
                val w=if(swap)mask.height else mask.width;val h=if(swap)mask.width else mask.height
                var alignedTime=info.timestampNs
                var correction:FloatArray?=null
                val alignmentStarted=System.nanoTime()
                if(enableMotionAlignment && mask.width==slot.rgb.width && mask.height==slot.rgb.height && extracted.isDirect) {
                    // 只在锁内批量复制最新同几何小图；匹配在锁外执行，不让采集等待推理或运动搜索。
                    val target=synchronized(lock) {
                        val latest=pending
                        if(latest!=null && latest.generation==slot.generation &&
                            latest.info.copy(timestampNs=info.timestampNs)==info &&
                            latest.info.timestampNs-info.timestampNs in 1..120_000_000L &&
                            latest.rgb.width==slot.rgb.width && latest.rgb.height==slot.rgb.height) {
                            val buffer=motionInput?.takeIf{it.capacity()==latest.buffer.remaining()}
                                ?:ByteBuffer.allocateDirect(latest.buffer.remaining()).also{motionInput=it}
                            buffer.clear();buffer.put(latest.buffer.duplicate());buffer.flip()
                            buffer to latest.info.timestampNs
                        }else null
                    }
                    if(target!=null) {
                        correction=NativeMaskMotion.estimate(slot.buffer,target.first,extracted.slice(),mask.width,mask.height,slot.rgb.rowStrideBytes)
                        if(correction!=null)alignedTime=target.second
                    }
                }
                val alignmentCompleted=System.nanoTime()
                // 在masks关闭前完成同步原生读取；转到原始采样空间，保留模型真实采样时间。
                val converted=maskConverter.prepare(extracted,mask.width,mask.height,info.rotationDegrees,info.mirrored)
                val conversionCompleted=System.nanoTime()
                // 校正留给GPU采样，避免CPU再重采样整张浮点图；位移先从模型正向坐标逆变换。
                val dx=(correction?.get(0)?:0f)*(if(info.mirrored)-1f else 1f)
                val dy=correction?.get(1)?:0f
                val rawX=when(info.rotationDegrees){90->dy;180->-dx;270->-dy;else->dx}
                val rawY=when(info.rotationDegrees){90->-dx;180->-dy;270->dx;else->dy}
                val next=ForegroundMask(info,w,h,converted,alignedTime,rawX/w,rawY/h)
                val completed=System.nanoTime()
                val timing=synchronized(lock) {
                    if(closed||!enabled||generation!=slot.generation)null else {
                        result=next
                        unalignedResult=next.withoutMotionAlignment()
                        ForegroundMaskTiming(info.timestampNs,slot.copyNs,queueNs,inferStarted-preparing,inferred-inferStarted,
                            completed-inferred,lastCompletedNs?.let{completed-it},effectiveExecution,
                            extractionCompleted-inferred,conversionCompleted-alignmentCompleted,completed-conversionCompleted,
                            alignmentCompleted-alignmentStarted,correction?.get(2)?:0f,alignedTime).also{lastCompletedNs=completed}
                    }
                }
                timing?.let{onTiming(it)}
            } finally{masks.forEach{it.close()}}
        } finally{image.close()}
    }
    /** GPU仅在所属worker创建。优先模式初始化失败回退CPU，显式GPU模式仍报告错误。 */
    private fun createSegmenter():ImageSegmenter {
        fun create(delegate:Execution):ImageSegmenter {
            val detector=ImageSegmenter.createFromOptions(app,ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite")
                    .setDelegate(if(delegate==Execution.GPU)Delegate.GPU else Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build())
            effectiveExecution=delegate;return detector
        }
        return if(execution==Execution.GPU_PREFERRED)try{create(Execution.GPU)}catch(error:Exception){create(Execution.CPU)}else create(execution)
    }
    /** 不等待在途推理；释放任务在同一worker最后执行，停止后结果不再可见。 */
    override fun close() {
        synchronized(lock) { if(closed) return;closed=true;enabled=false;invalidate()
            worker.execute { segmenter?.close();segmenter=null;maskConverter.clear();motionInput=null;slots.forEach{it.rgb.clear();it.oriented=ByteArray(0)} }
            worker.shutdown()
        }
    }
}
