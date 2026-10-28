package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/** 公开人像的固定快速横移序列。只保存夹具图，不读取摄像头，不修改用户配置。 */
class ForegroundMotionDeviceTest {
    @Test fun rapidTranslationMeasuresMaskLagAndExportsPublicFixture() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val original=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val w=192;val h=240
        val scaled=Bitmap.createScaledBitmap(original,w,h,true)
        val base=IntArray(w*h);scaled.getPixels(base,0,w,0,0,w,h)
        if(scaled!==original)scaled.recycle();original.recycle()
        fun colors(offset:Int)=IntArray(w*h){i->val x=i%w-offset;if(x in 0 until w)base[i/w*w+x]else 0xff808080.toInt()}
        fun rgb(p:IntArray)=ByteArray(p.size*3).also{b->p.forEachIndexed{i,c->b[i*3]=(c ushr 16).toByte();b[i*3+1]=(c ushr 8).toByte();b[i*3+2]=c.toByte()}}
        val errors=AtomicReference<Exception?>();val timings=java.util.Collections.synchronizedList(mutableListOf<com.beautysdk.mediapipe.ForegroundMaskTiming>())
        val offsets=listOf(-36,-24,-12,0,12,24,36)
        val inputs=offsets.associateWith{rgb(colors(it))}
        val reference=mutableMapOf<Int,ByteArray>()
        val alignedAges=mutableListOf<Double>();val ages=mutableListOf<Double>();val staleIou=mutableListOf<Double>();val selfIou=mutableListOf<Double>()
        val sheet=Bitmap.createBitmap(w*4,h*2,Bitmap.Config.ARGB_8888)
        data class Sample(val index:Int,val offset:Int,val mask:ForegroundMask)
        val samples=mutableListOf<Sample>()
        val positionHistory=mutableMapOf<Long,Int>()
        val enabled=InstrumentationRegistry.getArguments().getString("maskMotionAlignment","true").toBoolean()
        var captures=0;var missing=0
        fun bytes(mask:ForegroundMask):ByteArray {
            val b=mask.pixels();val original=ByteArray(b.remaining()).also{b.get(it)}
            val ox=(mask.offsetX*mask.width).roundToInt();val oy=(mask.offsetY*mask.height).roundToInt()
            if(ox==0&&oy==0)return original
            return ByteArray(original.size){i->val x=i%mask.width-ox;val y=i/mask.width-oy
                if(x in 0 until mask.width && y in 0 until mask.height)original[y*mask.width+x]else 0}
        }

        fun iou(a:ByteArray,b:ByteArray):Double {var intersection=0;var union=0;for(i in a.indices){val x=(a[i].toInt() and 255)>127;val y=(b[i].toInt() and 255)>127;if(x&&y)intersection++;if(x||y)union++};return intersection.toDouble()/max(1,union)}
        MediaPipeForegroundMasks(app,MediaPipeForegroundMasks.Execution.GPU){errors.set(it)}.use{masks->
            masks.setEnabled(true);masks.enableMotionAlignment=enabled;masks.onTiming={timings.add(it)}
            // 同一模型对每个位置单独等待完成，只作为时间对齐参考，不是人工真值。
            for((index,offset) in offsets.withIndex()) {
                val info=FrameInfo(w,h,(index+1)*1_000_000_000L)
                masks.submit(AnalysisImage(info,w,h,inputs.getValue(offset)))
                val deadline=System.nanoTime()+15_000_000_000L
                var mask:ForegroundMask?=null
                while(System.nanoTime()<deadline){errors.get()?.let{throw it};mask=masks.snapshot(info);if(mask?.info?.timestampNs==info.timestampNs)break;Thread.sleep(2)}
                assertNotNull(mask);assertEquals(info,mask!!.info);reference[offset]=bytes(mask!!)
            }
            masks.reset()
            val started=System.nanoTime();val frameNs=33_333_333L
            timings.clear()
            // 约2.7秒，30Hz输入；往返横移72像素，约0.4秒横穿画宽的37.5%。
            repeat(84){index->
                val target=started+index*frameNs
                while(System.nanoTime()<target){val left=target-System.nanoTime();if(left>2_000_000)Thread.sleep(1)else Thread.yield()}
                val phase=index%12;val position=if(phase<=6)phase else 12-phase;val offset=offsets[position]
                val info=FrameInfo(w,h,System.nanoTime());positionHistory[info.timestampNs]=offset
                masks.submit(AnalysisImage(info,w,h,inputs.getValue(offset)))
                errors.get()?.let{throw it}
                val mask=masks.snapshot(info)
                if(mask==null){missing++;return@repeat}
                val lag=(info.timestampNs-mask.info.timestampNs)/1e6
                alignedAges+=(info.timestampNs-mask.alignmentTimestampNs)/1e6
                ages+=lag;samples+=Sample(index,offset,mask)

            }
        }
        assertTrue("Expected usable masks in moving sequence",ages.size>30)
        // 采样结束后才计算IoU/诊断图片，避免Kotlin对照合成干扰30Hz输入调度。
        for(sample in samples) {
            val mask=sample.mask;val a=bytes(mask);val offset=sample.offset
            staleIou+=iou(a,reference.getValue(offset))
            selfIou+=iou(a,reference.getValue(positionHistory.getValue(mask.alignmentTimestampNs)))
            if(sample.index in listOf(15,27,39,51)&&captures<4){
                val current=colors(offset)
                fun composite(alpha:ByteArray)=IntArray(w*h){i->val v=alpha[i].toInt() and 255;val c=current[i]
                    val r=(c ushr 16 and 255)*v/255;val g=((c ushr 8 and 255)*v+255*(255-v))/255;val b=(c and 255)*v/255
                    0xff000000.toInt() or(r shl 16)or(g shl 8)or b}
                sheet.setPixels(composite(a),0,w,captures*w,0,w,h)
                sheet.setPixels(composite(reference.getValue(offset)),0,w,captures*w,h,w,h);captures++
            }
        }
        val sorted=ages.sorted();val out=JSONObject().put("fixture","public portrait, translated RGB, 192x240, GPU; no live camera")
            .put("motionAlignment",enabled).put("frames",84).put("usable",ages.size).put("missing",missing).put("captures",captures)
            .put("maskAgeMedianMs",sorted[sorted.size/2]).put("maskAgeP95Ms",sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)])
            .put("currentFrameVsStaleMaskMeanIoU",staleIou.average()).put("sampleAlignedMaskMeanIoU",selfIou.average())
            .put("inferenceMeanMs",timings.map{it.inferenceNs/1e6}.average())
            .put("queueMeanMs",timings.map{it.queueNs/1e6}.average())
            .put("copyMeanMs",timings.map{it.copyNs/1e6}.average())
            .put("preparationMeanMs",timings.map{it.preparationNs/1e6}.average())
            .put("postprocessMeanMs",timings.map{it.postprocessNs/1e6}.average())
            .put("completedIntervalMeanMs",timings.mapNotNull{it.completedIntervalNs?.div(1e6)}.average())
            .put("extractionMeanMs",timings.map{it.extractionNs/1e6}.average())
            .put("conversionMeanMs",timings.map{it.conversionNs/1e6}.average())
            .put("packagingMeanMs",timings.map{it.packagingNs/1e6}.average())
            .put("alignmentMeanMs",timings.map{it.alignmentNs/1e6}.average())
            .put("alignedResults",timings.count{it.alignmentTimestampNs>it.timestampNs})
            .put("alignmentAgeMedianMs",alignedAges.sorted()[alignedAges.size/2])
            .put("completedMasks",timings.size)
            .put("reference","same model at current position, not ground truth; composite is CPU diagnostic, not production GL")
        File(app.cacheDir,"foreground-motion.json").writeText(out.toString(2))
        File(app.cacheDir,"foreground-motion-public.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle()
    }
}
