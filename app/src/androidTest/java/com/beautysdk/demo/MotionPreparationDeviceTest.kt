package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.NativeFaceMotion
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 生成RGBA夹具；对照原单级JNI，不使用或保存相机图像。 */
class MotionPreparationDeviceTest {
    private fun frame(w:Int,h:Int,offset:Int=0):RgbaFrame {
        val stride=w*4+12
        val pixels=ByteBuffer.allocateDirect(stride*h+7)
        pixels.position(7)
        for(y in 0 until h)for(x in 0 until w) {
            val i=7+y*stride+x*4
            pixels.put(i,((x*17+y*3+offset)and 255).toByte())
            pixels.put(i+1,((y*11+x*5+offset)and 255).toByte())
            pixels.put(i+2,((x*7+y*13+offset)and 255).toByte());pixels.put(i+3,173.toByte())
        }
        return RgbaFrame(FrameInfo(w,h,1,90,true),pixels,stride)
    }
    private val intType=Int::class.javaPrimitiveType!!
    private fun gray()=NativeFaceMotion::class.java.getDeclaredMethod("gray",ByteBuffer::class.java,
        intType,intType,intType,ByteBuffer::class.java,intType,intType).apply{isAccessible=true}
    private fun pair()=NativeFaceMotion::class.java.getDeclaredMethod("grayPair",ByteBuffer::class.java,
        intType,intType,intType,ByteBuffer::class.java,intType,intType,ByteBuffer::class.java,intType,intType).apply{isAccessible=true}
    private fun bytes(buffer:ByteBuffer,size:Int)=ByteArray(size).also { buffer.duplicate().apply{clear();limit(size)}.get(it) }
    private fun detail(image:MotionImage)=image.javaClass.getDeclaredField("detail").apply{isAccessible=true}.get(image) as MotionImage

    @Test fun pairedPreparationIsByteExactAndReusePreservesOwnership() {
        val motion=NativeFaceMotion(true);val legacy=gray()
        for((w,h) in listOf(640 to 480,320 to 240,319 to 241,241 to 319,120 to 80,1 to 1)) {
            val source=frame(w,h);val before=bytes(source.readOnlyPixels(),source.readOnlyPixels().remaining())
            val image=motion.prepare(source);val fine=detail(image)
            for(level in listOf(image,fine)) {
                val expected=ByteBuffer.allocateDirect(level.width*level.height)
                assertEquals(true,legacy.invoke(motion,source.readOnlyPixels(),w,h,source.rowStrideBytes,expected,level.width,level.height))
                assertArrayEquals("$w x $h -> ${level.width} x ${level.height}",bytes(expected,expected.capacity()),bytes(level.pixels,expected.capacity()))
            }
            assertArrayEquals(before,bytes(source.readOnlyPixels(),before.size))
            if(w<=160 && h<=160)assertSame("Equal levels share one owned gray buffer",image.pixels,fine.pixels)
            val original=bytes(image.pixels,image.width*image.height)
            val independent=motion.prepare(frame(w,h,71))
            assertArrayEquals("Another frame must not overwrite this reference",original,bytes(image.pixels,original.size))
            val reused=motion.prepare(source,independent)
            assertSame(independent.pixels,reused.pixels)
            assertSame(detail(independent).pixels,detail(reused).pixels)
            assertArrayEquals(original,bytes(reused.pixels,original.size))
        }
        // 同一个估计器允许检测与跟踪两线程prepare，各自持有独占帧。
        val pool=Executors.newFixedThreadPool(2)
        try {
            val jobs=(0..1).map { n->pool.submit<ByteArray>{
                val source=frame(320,240,n*71);var reuse:MotionImage?=null
                repeat(30){reuse=motion.prepare(source,reuse)}
                bytes(reuse!!.pixels,reuse!!.width*reuse!!.height)
            } }
            for(n in 0..1) {
                val expected=NativeFaceMotion(false).prepare(frame(320,240,n*71))
                assertArrayEquals(bytes(expected.pixels,expected.width*expected.height),jobs[n].get(5,TimeUnit.SECONDS))
            }
        } finally {pool.shutdownNow()}
    }

    @Test fun invalidPairDoesNotPartiallyOverwriteDestinations() {
        val motion=NativeFaceMotion(true);val method=pair();val source=frame(320,240)
        val coarse=ByteBuffer.allocateDirect(160*120).apply{repeat(capacity()){put(23.toByte())};clear()}
        val fine=ByteBuffer.allocateDirect(2)
        assertEquals(false,method.invoke(motion,source.readOnlyPixels(),320,240,source.rowStrideBytes,coarse,160,120,fine,320,240))
        assertTrue(bytes(coarse,coarse.capacity()).all{it==23.toByte()})
        assertEquals(false,method.invoke(motion,ByteBuffer.allocate(320*240*4),320,240,1280,coarse,160,120,fine,1,1))
        assertTrue(bytes(coarse,coarse.capacity()).all{it==23.toByte()})
    }

    @Test fun pairedNativeConversionShortBenchmark() {
        val motion=NativeFaceMotion(true);val legacy=gray();val fused=pair();val source=frame(640,480)
        val input=source.readOnlyPixels();val coarse=ByteBuffer.allocateDirect(160*120);val fine=ByteBuffer.allocateDirect(320*240)
        fun old(){check(legacy.invoke(motion,input,640,480,source.rowStrideBytes,coarse,160,120)==true);check(legacy.invoke(motion,input,640,480,source.rowStrideBytes,fine,320,240)==true)}
        fun paired(){check(fused.invoke(motion,input,640,480,source.rowStrideBytes,coarse,160,120,fine,320,240)==true)}
        repeat(100){old();paired()}
        val oldTimes=mutableListOf<Double>();val pairTimes=mutableListOf<Double>()
        fun measure(block:()->Unit):Double {val start=System.nanoTime();repeat(200){block()};return (System.nanoTime()-start)/200_000.0}
        repeat(9){n->if(n%2==0){oldTimes+=measure(::old);pairTimes+=measure(::paired)}else{pairTimes+=measure(::paired);oldTimes+=measure(::old)}}
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","\nMOTION_PREPARATION oldMedianUs=${oldTimes.sorted()[4]} pairedMedianUs=${pairTimes.sorted()[4]} oldRoundsUs=$oldTimes pairedRoundsUs=$pairTimes\n")
        })
        // 不以短测耗时波动决定功能通过；速度记录独立于逐字节等价和动态跟踪验收。
    }
}
