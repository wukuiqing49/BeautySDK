package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** 使用实际模型和确定输入，验证两槽背压、输入所有权、重置与非对齐RGB行。 */
class ForegroundSchedulingDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(masks:MediaPipeForegroundMasks,info:FrameInfo):ForegroundMask {
        val end=System.nanoTime()+15_000_000_000L
        while(System.nanoTime()<end){masks.snapshot(info)?.let{if(it.info.timestampNs==info.timestampNs)return it};Thread.sleep(10)}
        error("Newest foreground mask was not published")
    }
    @Test fun busyWorkerProcessesNewestPendingAndResetCancelsOldPending() {
        val first=CountDownLatch(1);val release=CountDownLatch(1)
        val samples=CopyOnWriteArrayList<Long>();val errors=CopyOnWriteArrayList<Exception>()
        MediaPipeForegroundMasks(app){errors+=it}.use {masks->
            masks.onTiming={timing->samples+=timing.timestampNs;if(samples.size==1){first.countDown();check(release.await(10,TimeUnit.SECONDS))}}
            masks.setEnabled(true)
            val pixels=ByteBuffer.allocateDirect(64*48*4)
            repeat(64*48){pixels.put(90).put(80).put(70).put(255.toByte())};pixels.flip()
            val initial=FrameInfo(64,48,100_000_000)
            try {
                assertTrue(masks.submit(RgbaFrame(initial,pixels,64*4)))
                assertTrue(first.await(15,TimeUnit.SECONDS))
                repeat(20){i->assertTrue(masks.submit(RgbaFrame(initial.copy(timestampNs=110_000_000L+i*1_000_000),pixels,64*4)))}
                release.countDown()
                val latest=initial.copy(timestampNs=129_000_000)
                await(masks,latest)
                // active推理期间的20次输入只留下最后一个；不逐帧排队补做旧分割。
                val deadline=System.nanoTime()+1_000_000_000L
                while(samples.size<2&&System.nanoTime()<deadline)Thread.sleep(5)
                assertEquals(listOf(initial.timestampNs,latest.timestampNs),samples.toList())
                assertFalse(masks.submit(RgbaFrame(latest,pixels,64*4)))
                masks.reset();assertNull(masks.snapshot(latest))
                val restarted=initial.copy(timestampNs=1_000_000)
                assertTrue(masks.submit(RgbaFrame(restarted,pixels,64*4)));await(masks,restarted)
                masks.setEnabled(false);assertNull(masks.snapshot(restarted));assertFalse(masks.submit(RgbaFrame(latest,pixels,64*4)))
                assertTrue(errors.toString(),errors.isEmpty())
            } finally{release.countDown()}
        }
    }
    @Test fun resetWhileWorkerBusyDiscardsPendingAndAllowsNewTimeline() {
        val first=CountDownLatch(1);val release=CountDownLatch(1);val samples=CopyOnWriteArrayList<Long>()
        MediaPipeForegroundMasks(app).use {masks->
            masks.onTiming={t->samples+=t.timestampNs;if(samples.size==1){first.countDown();check(release.await(10,TimeUnit.SECONDS))}}
            val rgb=ByteArray(64*48*3){80};val initial=FrameInfo(64,48,100_000_000)
            masks.setEnabled(true)
            try {
                masks.submit(AnalysisImage(initial,64,48,rgb));assertTrue(first.await(15,TimeUnit.SECONDS))
                masks.submit(AnalysisImage(initial.copy(timestampNs=110_000_000),64,48,rgb))
                masks.reset();assertNull(masks.snapshot(initial))
                val fresh=initial.copy(timestampNs=1_000_000)
                assertTrue(masks.submit(AnalysisImage(fresh,64,48,rgb)))
                release.countDown();await(masks,fresh)
                assertFalse(samples.contains(110_000_000L))
            } finally{release.countDown()}
        }
    }
    @Test fun directRgbaAndRgbAgreeForOddRowsAllOrientationsAndKeepSourceTime() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use{android.graphics.BitmapFactory.decodeStream(it)}!!
        val image=android.graphics.Bitmap.createScaledBitmap(original,131,97,true)
        val colors=IntArray(131*97);image.getPixels(colors,0,131,0,0,131,97)
        if(image!==original)image.recycle();original.recycle()
        val rgb=ByteArray(colors.size*3);val rgba=ByteBuffer.allocateDirect(colors.size*4)
        colors.forEachIndexed{i,c->rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte()
            rgba.put(rgb[i*3]).put(rgb[i*3+1]).put(rgb[i*3+2]).put(255.toByte())};rgba.flip()
        MediaPipeForegroundMasks(app).use {masks->
            masks.setEnabled(true);var time=0L
            for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
                time+=20_000_000;val info=FrameInfo(131,97,time,rotation,mirror)
                assertTrue(masks.submit(AnalysisImage(info,131,97,rgb)));val expected=await(masks,info)
                time+=20_000_000;val direct=info.copy(timestampNs=time)
                assertTrue(masks.submit(RgbaFrame(direct,rgba,131*4)));val actual=await(masks,direct)
                assertEquals(131,actual.width);assertEquals(97,actual.height);assertEquals(direct,actual.info)
                val a=expected.pixels();val b=actual.pixels()
                while(a.hasRemaining())assertTrue(kotlin.math.abs((a.get().toInt() and 255)-(b.get().toInt() and 255))<=2)
                assertNull(masks.snapshot(direct.copy(timestampNs=time+250_000_001)))
            }
        }
    }
}
