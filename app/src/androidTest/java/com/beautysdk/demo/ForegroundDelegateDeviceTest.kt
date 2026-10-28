package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 公开固定人像，同规格比较delegate；只记录耗时，不保存相机或人像图像。 */
class ForegroundDelegateDeviceTest {
    @Test fun compareCpuAndGpuOnSamePortraitWithWarmup() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val bitmap=inst.context.assets.open("portrait.jpg").use{android.graphics.BitmapFactory.decodeStream(it)}!!
        val image=android.graphics.Bitmap.createScaledBitmap(bitmap,192,240,true)
        val colors=IntArray(192*240);image.getPixels(colors,0,192,0,0,192,240)
        if(image!==bitmap)image.recycle();bitmap.recycle()
        val rgb=ByteArray(colors.size*3);colors.forEachIndexed{i,c->rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte()}
        val results=JSONArray()
        for(execution in listOf(MediaPipeForegroundMasks.Execution.CPU,MediaPipeForegroundMasks.Execution.GPU)) {
            val errors=AtomicReference<Exception?>();val duration=AtomicReference<Long?>();val times=mutableListOf<Long>()
            val entry=JSONObject().put("execution",execution.name);results.put(entry)
            try {
                MediaPipeForegroundMasks(app,execution){errors.set(it)}.use {masks->
                    masks.setEnabled(true);masks.onTiming={duration.set(it.inferenceNs)}
                    repeat(25){i->
                        duration.set(null);val info=FrameInfo(192,240,(i+1)*40_000_000L)
                        assertTrue(masks.submit(AnalysisImage(info,192,240,rgb)))
                        val end=System.nanoTime()+15_000_000_000L;var mask:ForegroundMask?=null
                        while(System.nanoTime()<end) {
                            errors.get()?.let{throw it};mask=masks.snapshot(info)
                            if(mask?.info?.timestampNs==info.timestampNs&&duration.get()!=null)break
                            Thread.sleep(1)
                        }
                        requireNotNull(mask);assertEquals(info,mask!!.info)
                        if(i==0){val p=mask!!.pixels();var low=0;var high=0;while(p.hasRemaining()){val v=p.get().toInt() and 255;if(v<64)low++;if(v>192)high++};assertTrue(low>100&&high>100)}
                        if(i>=5)times+=requireNotNull(duration.get())
                    }
                }
                val sorted=times.sorted();entry.put("supported",true).put("samples",times.size)
                    .put("medianInferenceMs",sorted[sorted.size/2]/1e6).put("p95InferenceMs",sorted[18]/1e6)
            } catch(e:Exception) {
                entry.put("supported",false).put("error",e.javaClass.simpleName+": "+e.message)
                if(execution==MediaPipeForegroundMasks.Execution.CPU)throw e
            }
        }
        File(app.cacheDir,"foreground-delegates.json").writeText(JSONObject().put("input","public portrait RGB 192x240").put("warmup",5).put("results",results).toString(2))
    }
}
