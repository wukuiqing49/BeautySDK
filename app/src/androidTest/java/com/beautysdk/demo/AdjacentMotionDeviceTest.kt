package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import com.beautysdk.mediapipe.NativeFaceMotion
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 公开固定人像的连续已知位移；仅内存生成，不读取或保存用户画面。 */
class AdjacentMotionDeviceTest {
    @Test fun fastPortraitTranslationMustNotLoseTracking() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val bitmap=Bitmap.createScaledBitmap(original,240,300,true)
        val pixels=IntArray(240*300);bitmap.getPixels(pixels,0,240,0,0,240,300)
        if(bitmap!==original)bitmap.recycle();original.recycle()
        fun frame(dx:Int,time:Long):RgbaFrame {
            val rgba=ByteBuffer.allocateDirect(240*300*4)
            for(y in 0 until 300)for(x in 0 until 240) {
                val c=if(x-dx in 0 until 240)pixels[y*240+x-dx] else 0
                rgba.put((c ushr 16).toByte()).put((c ushr 8).toByte()).put(c.toByte()).put(255.toByte())
            }
            rgba.flip();return RgbaFrame(FrameInfo(240,300,time),rgba,960)
        }
        val first=frame(0,1)
        val seed=MediaPipeFaceAnalyzer(inst.targetContext).use{it.analyzeRgba(first).single()}
        val motion=NativeFaceMotion(true)
        val reference=motion.prepare(first)
        for(dx in listOf(12,24,36)) {
            val next=motion.prepare(frame(dx,33_333_334))
            val start=System.nanoTime();val result=motion.estimate(reference,next,seed)
            val cost=(System.nanoTime()-start)/1e6
            inst.sendStatus(0,android.os.Bundle().apply{putString("stream","\nFAST_PORTRAIT dx=$dx tracked=${result!=null} estimateMs=$cost\n")})
            assertNotNull("Fast translation dx=$dx lost tracking",result)
            val expected=seed.anchors.getValue(FaceAnchor.NOSE).x+dx/240f
            assertEquals("Fast translation dx=$dx nose drift",expected,result!!.anchors.getValue(FaceAnchor.NOSE).x,2f/240)
        }
        assertNull("Textureless/covered frame must not produce invented movement",
            motion.estimate(reference,motion.prepare(frame(500,66_666_667)),seed))
    }
    @Test fun portraitRotationAndScaleStayAligned() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val bitmap=Bitmap.createScaledBitmap(original,240,300,true)
        val pixels=IntArray(240*300);bitmap.getPixels(pixels,0,240,0,0,240,300)
        if(bitmap!==original)bitmap.recycle();original.recycle()
        fun frame(angle:Double,scale:Double,time:Long):RgbaFrame {
            val c=kotlin.math.cos(angle);val si=kotlin.math.sin(angle)
            val data=ByteBuffer.allocateDirect(240*300*4)
            for(y in 0 until 300)for(x in 0 until 240) {
                val u=(x-120-12)/scale;val v=(y-150+6)/scale
                val sx=c*u+si*v+120;val sy=-si*u+c*v+150
                val ix=kotlin.math.floor(sx).toInt();val iy=kotlin.math.floor(sy).toInt()
                val fx=sx-ix;val fy=sy-iy
                for(shift in listOf(16,8,0)) {
                    fun channel(xx:Int,yy:Int)=if(xx in 0 until 240&&yy in 0 until 300)(pixels[yy*240+xx] ushr shift) and 255 else 0
                    val value=(channel(ix,iy)*(1-fx)+channel(ix+1,iy)*fx)*(1-fy)+(channel(ix,iy+1)*(1-fx)+channel(ix+1,iy+1)*fx)*fy
                    data.put(value.toInt().coerceIn(0,255).toByte())
                }
                data.put(255.toByte())
            }
            data.flip();return RgbaFrame(FrameInfo(240,300,time),data,960)
        }
        val raw=ByteBuffer.allocateDirect(240*300*4)
        for(c in pixels)raw.put((c ushr 16).toByte()).put((c ushr 8).toByte()).put(c.toByte()).put(255.toByte())
        raw.flip();val first=RgbaFrame(FrameInfo(240,300,1),raw,960)
        val seed=MediaPipeFaceAnalyzer(inst.targetContext).use{it.analyzeRgba(first).single()}
        val motion=NativeFaceMotion(true);val reference=motion.prepare(first)
        val lips=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
        for(angle in listOf(-.10,.10)) {
            val scale=1.04;val result=motion.estimate(reference,motion.prepare(frame(angle,scale,33_333_334)),seed)
            assertNotNull("Plane rotation $angle lost tracking",result)
            val error=lips.map {index->
                val p=seed.landmarks[index];val x=p.x*240-120;val y=p.y*300-150
                val expectedX=scale*(kotlin.math.cos(angle)*x-kotlin.math.sin(angle)*y)+132
                val expectedY=scale*(kotlin.math.sin(angle)*x+kotlin.math.cos(angle)*y)+144
                val q=result!!.landmarks[index]
                kotlin.math.hypot(q.x*240-expectedX,q.y*300-expectedY)
            }.average()
            inst.sendStatus(0,android.os.Bundle().apply{putString("stream","\nPORTRAIT_AFFINE angleRad=$angle scale=$scale meanLipErrorPx=$error\n")})
            assertTrue("Plane rotation lip error $error",error<3.0)
        }
    }
    @Test fun continuousPortraitMotionExceedsSingleReferenceRangeWithoutFreezing() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val bitmap=Bitmap.createScaledBitmap(original,240,300,true)
        val pixels=IntArray(240*300);bitmap.getPixels(pixels,0,240,0,0,240,300)
        if(bitmap!==original) bitmap.recycle();original.recycle()
        fun frame(step:Int):RgbaFrame {
            val rgba=ByteBuffer.allocateDirect(240*300*4)
            for(y in 0 until 300) for(x in 0 until 240) {
                val color=if(x>=step*4) pixels[y*240+x-step*4] else 0
                rgba.put((color ushr 16).toByte()).put((color ushr 8).toByte()).put(color.toByte()).put(255.toByte())
            }
            rgba.flip();return RgbaFrame(FrameInfo(240,300,1+step*30_000_000L),rgba,960)
        }
        val detected=LinkedBlockingQueue<Long>();val tracked=LinkedBlockingQueue<Long>()
        val first=frame(0)
        val seed=MediaPipeFaceAnalyzer(inst.targetContext).use { it.analyzeRgba(first).single() }
        // 定位只执行一次真实模型；随后8帧仅走公共有界跟踪支路。
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="fixed-public-portrait";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=listOf(seed)
            override fun close()=Unit
        }},NativeFaceMotion(enableLipTracking=true),onTiming={detected.add(it.frameTimestampNs)},
            onTrackingTiming={tracked.add(it.frameTimestampNs)})
        try {
            runtime.setTrackingEnabled(true);runtime.submit(first)
            assertEquals(1L,detected.poll(5,TimeUnit.SECONDS));assertEquals(1L,tracked.poll(5,TimeUnit.SECONDS))
            var maxError=0f
            for(step in 1..8) {
                val current=frame(step);runtime.submit(current,runDetection=false)
                assertEquals(current.info.timestampNs,tracked.poll(5,TimeUnit.SECONDS))
                val snapshot=runtime.snapshot(current.info,150_000_000L)
                assertTrue("Frame $step lost tracking",snapshot.tracked)
                assertEquals(current.info.timestampNs,snapshot.result!!.info.timestampNs)
                assertEquals("Chaining must not refresh real model timestamp",1L,snapshot.detectionTimestampNs)
                val expected=seed.anchors.getValue(FaceAnchor.NOSE).x+step*4/240f
                val actual=snapshot.result!!.faces.single().anchors.getValue(FaceAnchor.NOSE).x
                maxError=maxOf(maxError,kotlin.math.abs(expected-actual)*240)
                assertEquals("Frame $step horizontal drift",expected,actual,.015f)
            }
            inst.sendStatus(0,android.os.Bundle().apply {putString("stream",
                "\nADJACENT_MOTION frames=8 totalShiftPx=32 maxNoseErrorPx=$maxError referenceAgeMs=240\n")})
        } finally {runtime.close()}
    }
}
