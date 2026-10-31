package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.ForegroundMaskTiming
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** 仅使用公开夹具；验证原生崩溃设备的分割后端路由以及人脸/分割组合。 */
class ForegroundMaskCompatibilityDeviceTest {
    @Test fun throwingTimingAndErrorCallbacksDoNotStopNextFrameOrClose() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val failures=java.util.concurrent.atomic.AtomicInteger()
        val completed=java.util.concurrent.atomic.AtomicInteger()
        val masks=MediaPipeForegroundMasks(app,MediaPipeForegroundMasks.Execution.CPU) {
            failures.incrementAndGet();throw IllegalStateException("Injected error callback failure")
        }
        try {
            masks.onTiming={completed.incrementAndGet();throw IllegalStateException("Injected timing callback failure")}
            masks.setEnabled(true)
            val rgba=fixture()
            repeat(3) { index ->
                val info=FrameInfo(192,240,1_000_000_000L+index*100_000_000L)
                assertTrue(masks.submit(RgbaFrame(info,rgba,192*4)))
                val deadline=System.nanoTime()+15_000_000_000L
                while(failures.get()<=index && System.nanoTime()<deadline)Thread.sleep(10)
                assertEquals(index+1,failures.get())
                assertEquals(index+1,completed.get())
                assertNotNull(masks.snapshot(info))
            }
        } finally { masks.close();masks.close() }
        assertFalse(masks.submit(RgbaFrame(FrameInfo(192,240,2_000_000_000L),fixture(),192*4)))
    }
    private val unsafeGpu get() = Build.MANUFACTURER.equals("OPPO",true) &&
        Build.DEVICE.equals("OP46B1",true) && Build.VERSION.SDK_INT==30

    private fun fixture():ByteBuffer {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val image=Bitmap.createScaledBitmap(original,192,240,true)
        val pixels=IntArray(192*240);image.getPixels(pixels,0,192,0,0,192,240)
        val bytes=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEach { bytes.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte()) }
        bytes.flip();if(image!==original)image.recycle();original.recycle()
        return bytes
    }

    @Test fun preferredBackendKeepsSoftMasksAcrossResetsWithReal3DFaceAnalysis() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val error=AtomicReference<Exception?>()
        val timing=AtomicReference<ForegroundMaskTiming?>()
        val rgba=fixture()
        MediaPipeFaceAnalyzer(app,execution=MediaPipeFaceAnalyzer.Execution.GPU,output3D=true).use { faces ->
            MediaPipeForegroundMasks(app,MediaPipeForegroundMasks.Execution.GPU_PREFERRED) { error.set(it) }.use { masks ->
                masks.onTiming={timing.set(it)}
                masks.setEnabled(true)
                repeat(12) { index ->
                    val info=FrameInfo(192,240,1_000_000_000L+index*100_000_000L)
                    val frame=RgbaFrame(info,rgba,192*4)
                    if(index%4==0) { masks.reset(); assertNull(masks.snapshot(info)) }
                    assertTrue(masks.submit(frame))
                    val face=faces.analyzeRgba(frame).single()
                    assertEquals(info.timestampNs,face.geometry3D!!.timestampNs)
                    val deadline=System.nanoTime()+15_000_000_000L
                    var mask=masks.snapshot(info)
                    while((mask==null || timing.get()?.timestampNs!=info.timestampNs) && error.get()==null && System.nanoTime()<deadline) {
                        Thread.sleep(10);mask=masks.snapshot(info)
                    }
                    error.get()?.let { throw it }
                    assertNotNull("Mask sample $index",mask)
                    assertEquals(info.timestampNs,mask!!.info.timestampNs)
                    assertEquals(if(unsafeGpu) MediaPipeForegroundMasks.Execution.CPU else MediaPipeForegroundMasks.Execution.GPU,timing.get()!!.execution)
                    val values=mask.pixels();var low=0;var high=0;var soft=0
                    while(values.hasRemaining()) { val v=values.get().toInt() and 255;if(v<64)low++;if(v>192)high++;if(v in 65..191)soft++ }
                    assertTrue("Foreground/background/soft edges retained",low>100 && high>100 && soft>0)
                    assertNull(masks.snapshot(info.copy(timestampNs=info.timestampNs+250_000_001L)))
                }
                masks.setEnabled(false)
                assertNull(masks.snapshot(FrameInfo(192,240,2_100_000_000L)))
            }
        }
    }

    @Test fun explicitGpuReportsKnownIncompatibilityWithoutNativeAbort() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val error=AtomicReference<Exception?>()
        val info=FrameInfo(192,240,1_000_000_000L)
        MediaPipeForegroundMasks(app,MediaPipeForegroundMasks.Execution.GPU) { error.set(it) }.use { masks ->
            masks.setEnabled(true)
            assertTrue(masks.submit(RgbaFrame(info,fixture(),192*4)))
            val deadline=System.nanoTime()+15_000_000_000L
            while(error.get()==null && masks.snapshot(info)==null && System.nanoTime()<deadline)Thread.sleep(10)
            if(unsafeGpu) {
                assertTrue(error.get() is IllegalStateException)
                assertTrue(error.get()!!.message!!.contains("unsupported"))
                assertNull(masks.snapshot(info))
            } else {
                error.get()?.let { throw it }
                assertNotNull(masks.snapshot(info))
            }
        }
    }
}
