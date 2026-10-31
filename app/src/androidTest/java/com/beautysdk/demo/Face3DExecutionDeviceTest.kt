package com.beautysdk.demo

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** 同一公开夹具的执行后端短测；仅记录耗时，不采集相机画面。 */
class Face3DExecutionDeviceTest {
    @Test fun compareCpuAndGpuReal3DOnSameFixture(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val bitmap=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,256,320,true)
        val pixels=IntArray(256*320);scaled.getPixels(pixels,0,256,0,0,256,320)
        val rgba=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEach{rgba.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte())};rgba.flip()
        if(scaled!==bitmap)scaled.recycle();bitmap.recycle()
        val result=org.json.JSONObject()
        for(execution in MediaPipeFaceAnalyzer.Execution.entries){
            val samples=mutableListOf<Double>()
            MediaPipeFaceAnalyzer.factory(inst.targetContext,execution=execution,output3D=true).create().use{analyzer->
                repeat(25){i->
                    val frame=RgbaFrame(FrameInfo(256,320,1_000_000_000L+i*40_000_000L),rgba,256*4)
                    val start=System.nanoTime();val face=analyzer.analyzeRgba(frame).single()
                    val elapsed=(System.nanoTime()-start)/1e6
                    assertEquals(frame.info.timestampNs,face.geometry3D!!.timestampNs)
                    assertEquals(478,face.geometry3D!!.points.size)
                    if(i>=5)samples+=elapsed
                }
            }
            result.put(execution.name,org.json.JSONObject().put("samplesMs",org.json.JSONArray(samples)).put("meanMs",samples.average()).put("p95Ms",samples.sorted()[18]))
        }
        java.io.File(inst.targetContext.cacheDir,"face3d-execution.json").writeText(result.toString(2))
        android.util.Log.i("Face3DExecution",result.toString())
    }
}
