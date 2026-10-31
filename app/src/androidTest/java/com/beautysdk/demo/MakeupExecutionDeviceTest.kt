package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** 公开固定人像上测2D美妆所需478点，不保存相机帧；不代表真人运动质量。 */
class MakeupExecutionDeviceTest {
    @Test fun compareCpuAndGpuDenseMakeupLandmarks() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val source=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val scaled=Bitmap.createScaledBitmap(source,256,320,true)
        val pixels=IntArray(256*320);scaled.getPixels(pixels,0,256,0,0,256,320)
        val rgba=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEach{rgba.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte())};rgba.flip()
        if(scaled!==source)scaled.recycle();source.recycle()
        for(execution in MediaPipeFaceAnalyzer.Execution.entries) {
            val samples=mutableListOf<Double>()
            MediaPipeFaceAnalyzer.factory(inst.targetContext,execution=execution,output3D=false).create().use{analyzer->
                repeat(25){i->
                    val frame=RgbaFrame(FrameInfo(256,320,1_000_000_000L+i*40_000_000L),rgba,256*4)
                    val start=System.nanoTime();val face=analyzer.analyzeRgba(frame).single()
                    val elapsed=(System.nanoTime()-start)/1e6
                    assertEquals(478,face.landmarks.size);assertNull(face.geometry3D)
                    assertTrue(face.landmarks[61].x in 0f..1f&&face.landmarks[291].y in 0f..1f)
                    if(i>=5)samples+=elapsed
                }
            }
            val sorted=samples.sorted()
            inst.sendStatus(0,android.os.Bundle().apply{putString("stream","\nMAKEUP_EXECUTION backend=$execution samples=${samples.size} meanMs=${samples.average()} p50Ms=${sorted[10]} p95Ms=${sorted[18]}\n")})
        }
    }
}
