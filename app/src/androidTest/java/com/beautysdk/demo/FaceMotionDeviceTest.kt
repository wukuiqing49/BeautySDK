package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import com.beautysdk.mediapipe.NativeFaceMotion
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FaceMotionDeviceTest {
    /** 公开固定人像的已知平移和遮挡，不保存用户帧；测试真实模型关键点到native匹配的完整接口。 */
    @Test fun nativeMotionTracksPortraitTranslationAndRejectsBlankFrame() = checkTranslation(3,2)
    private fun checkTranslation(dx:Int,dy:Int) {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val bitmap=Bitmap.createScaledBitmap(original,240,300,true)
        val pixels=IntArray(240*300);bitmap.getPixels(pixels,0,240,0,0,240,300)
        val rgba=ByteBuffer.allocateDirect(240*300*4)
        pixels.forEach { rgba.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte()) };rgba.flip()
        if(bitmap!==original) bitmap.recycle();original.recycle()
        val frame=RgbaFrame(FrameInfo(240,300,1),rgba,960)
        val face=MediaPipeFaceAnalyzer(inst.targetContext).use { it.analyzeRgba(frame).single() }
        val estimator=NativeFaceMotion();val reference=estimator.prepare(frame)
        val shifted=ByteBuffer.allocateDirect(reference.width*reference.height)
        for(y in 0 until reference.height) for(x in 0 until reference.width) {
            shifted.put(if(x>=dx && y>=dy) reference.pixels.get((y-dy)*reference.width+x-dx) else 0.toByte())
        }
        shifted.flip()
        val current=MotionImage(frame.info.copy(timestampNs=200_000_000),reference.width,reference.height,shifted)
        val times=mutableListOf<Double>()
        repeat(15) {
            val start=System.nanoTime();val result=estimator.estimate(reference,current,face)
            times.add((System.nanoTime()-start)/1e6)
            assertNotNull("native match rejected known translation",result)
            val nose=result!!.anchors.getValue(FaceAnchor.NOSE);val before=face.anchors.getValue(FaceAnchor.NOSE)
            assertEquals(before.x+dx.toFloat()/reference.width,nose.x,.015f)
            assertEquals(before.y+dy.toFloat()/reference.height,nose.y,.015f)
        }
        val blank=MotionImage(current.info,reference.width,reference.height,ByteBuffer.allocateDirect(reference.width*reference.height))
        assertNull(estimator.estimate(reference,blank,face))
        inst.sendStatus(0,android.os.Bundle().apply {putString("stream","\nMOTION samples=${times.size} meanMs=${times.average()} maxMs=${times.maxOrNull()}\n")})
    }
}
