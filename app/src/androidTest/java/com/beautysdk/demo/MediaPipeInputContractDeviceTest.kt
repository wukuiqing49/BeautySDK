package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** 复现全量回归暴露的 RGB 行对齐问题；使用生成的空白像素，不采集人脸。 */
class MediaPipeInputContractDeviceTest {
    @Test fun compactRgbSupportsAlignedAndNonAlignedWidths() {
        MediaPipeFaceAnalyzer(InstrumentationRegistry.getInstrumentation().targetContext).use { analyzer ->
            for((index,width) in listOf(320,317,318,319,320).withIndex()) {
                val info=FrameInfo(width,240,(index+1)*33_333_333L)
                assertTrue(analyzer.analyze(AnalysisImage(info,width,240,ByteArray(width*240*3))).isEmpty())
            }
        }
    }
    @Test fun directRgbaSupportsAlignedAndNonAlignedWidths() {
        MediaPipeFaceAnalyzer(InstrumentationRegistry.getInstrumentation().targetContext).use { analyzer ->
            for((index,width) in listOf(320,317,318,319,320).withIndex()) {
                val info=FrameInfo(width,240,(index+1)*33_333_333L)
                val pixels=ByteBuffer.allocateDirect(width*240*4)
                assertTrue(analyzer.analyzeRgba(RgbaFrame(info,pixels,width*4)).isEmpty())
            }
        }
    }

    @Test fun alignedInputMatchesReferencePixelsAndReusesBuffers() {
        // 仅测试反射读取internal输入器，不扩大SDK公开API。
        val type=Class.forName("com.beautysdk.mediapipe.MediaPipeRgbInput")
        val input=type.getDeclaredConstructor().newInstance()
        val prepare=type.getMethod("prepare",RgbaFrame::class.java,Int::class.javaPrimitiveType)
        val rgb=type.getMethod("prepare",ByteArray::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        val getWidth=type.getMethod("getWidth")
        val getHeight=type.getMethod("getHeight")
        val getStride=type.getMethod("getRowStrideBytes")
        val reference=ReusableRgbInput(16)
        try {
            for(direct in listOf(true,false)) for(w in listOf(35,37,39,41,1)) {
                val h=23;val sourceStride=w*4+12;val offset=7
                val source=if(direct) ByteBuffer.allocateDirect(sourceStride*h+offset) else ByteBuffer.allocate(sourceStride*h+offset)
                for(i in 0 until source.capacity()) source.put(i,(i*31).toByte())
                source.position(offset)
                for(rotation in listOf(0,90,180,270)) for(mirror in listOf(false,true)) {
                    val frame=RgbaFrame(FrameInfo(w,h,1,rotation,mirror),source,sourceStride)
                    val compact=reference.prepare(frame)
                    val actual=prepare.invoke(input,frame,16) as ByteBuffer
                    val width=getWidth.invoke(input) as Int;val height=getHeight.invoke(input) as Int
                    val stride=getStride.invoke(input) as Int
                    assertEquals(reference.width,width);assertEquals(reference.height,height)
                    assertEquals((width*3+3)/4*4,stride)
                    assertEquals(stride*height,actual.capacity())
                    for(y in 0 until height) {
                        for(x in 0 until width*3) assertEquals(compact.get(y*width*3+x),actual.get(y*stride+x))
                        for(x in width*3 until stride) assertEquals(0,actual.get(y*stride+x).toInt())
                    }
                    assertEquals(offset,source.position())
                    assertSame(actual,prepare.invoke(input,frame,16))
                    // 两种入口交替且复用同容量，验证ByteArray布局与原生/堆缓冲结果一致。
                    val pixels=ByteArray(compact.remaining());compact.get(pixels)
                    val packed=rgb.invoke(input,pixels,width,height) as ByteBuffer
                    assertSame(actual,packed)
                    for(y in 0 until height) for(x in 0 until width*3) assertEquals(pixels[y*width*3+x],packed.get(y*stride+x))
                }
            }
        } finally { type.getMethod("clear").invoke(input);reference.clear() }
    }

    @Test fun cpuGpuDetectOddPortraitOnBothEntrypoints() {
        val inst=InstrumentationRegistry.getInstrumentation()
        // Bitmap只用于构造公开测试夹具；SDK实时路径不创建Bitmap。
        val original=inst.context.assets.open("portrait.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        val fixture=android.graphics.Bitmap.createScaledBitmap(original,239,299,true)
        val pixels=IntArray(239*299)
        fixture.getPixels(pixels,0,239,0,0,239,299)
        if(fixture!==original) fixture.recycle()
        original.recycle()
        val rgb=ByteArray(pixels.size*3)
        val rgba=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEachIndexed { i,c ->
            rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte()
            rgba.put(rgb[i*3]).put(rgb[i*3+1]).put(rgb[i*3+2]).put(255.toByte())
        }
        rgba.flip()
        for(execution in MediaPipeFaceAnalyzer.Execution.entries) {
            MediaPipeFaceAnalyzer(inst.targetContext,execution=execution).use { analyzer ->
                val a=analyzer.analyze(AnalysisImage(FrameInfo(239,299,33_333_333),239,299,rgb))
                val b=analyzer.analyzeRgba(RgbaFrame(FrameInfo(239,299,66_666_666),rgba,239*4))
                assertEquals(1,a.size);assertEquals(1,b.size)
                assertEquals(478,a.single().landmarks.size);assertEquals(478,b.single().landmarks.size)
                val p=a.single().anchors.getValue(FaceAnchor.NOSE);val q=b.single().anchors.getValue(FaceAnchor.NOSE)
                assertEquals(p.x,q.x,.03f);assertEquals(p.y,q.y,.03f)
            }
        }
    }
}
