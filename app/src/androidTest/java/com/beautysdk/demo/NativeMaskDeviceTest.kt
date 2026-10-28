package com.beautysdk.demo

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI内存与坐标契约：用独立Kotlin参考逐像素比对，覆盖非对齐切片和非有限置信度。 */
class NativeMaskDeviceTest {
    private val type=Class.forName("com.beautysdk.mediapipe.NativeMaskConverter")
    private fun converter()=type.getDeclaredConstructor().newInstance()
    private fun prepare(instance:Any,source:ByteBuffer,w:Int,h:Int,rotation:Int,mirror:Boolean):ByteBuffer =
        type.getDeclaredMethod("prepare",ByteBuffer::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).invoke(instance,source,w,h,rotation,mirror) as ByteBuffer

    @Test fun nativeMatchesReferenceForAllDirectionsOddSizesAndSpecialValues() {
        val instance=converter()
        val special=floatArrayOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1f,-0f,0f,.1f,.5f,1f,2f)
        for((w,h) in listOf(1 to 1,7 to 5,131 to 97))for(direct in listOf(true,false)) {
            val count=w*h
            // 起点偏移1字节，验证原生读取不要求浮点地址四字节对齐。
            val source=(if(direct)ByteBuffer.allocateDirect(count*4+3)else ByteBuffer.allocate(count*4+3)).order(ByteOrder.nativeOrder())
            val values=FloatArray(count){if(it<special.size)special[it]else ((it*37)%1024-200)/700f}
            values.forEachIndexed{i,v->source.putFloat(1+i*4,v)};source.position(1);source.limit(1+count*4)
            for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
                val result=prepare(instance,source,w,h,rotation,mirror)
                val rw=if(rotation==90||rotation==270)h else w;val rh=count/rw
                val expected=ByteArray(count)
                for(y in 0 until rh)for(x in 0 until rw) {
                    val rx:Int;val ry:Int
                    when(rotation){90->{rx=rh-1-y;ry=x};180->{rx=rw-1-x;ry=rh-1-y};270->{rx=y;ry=rw-1-x};else->{rx=x;ry=y}}
                    val dx=if(mirror)w-1-rx else rx;val v=values[ry*w+dx]
                    expected[y*rw+x]=(if(v.isFinite())(v.coerceIn(0f,1f)*255+.5f).toInt()else 0).toByte()
                }
                val actual=ByteArray(count);result.duplicate().get(actual)
                assertArrayEquals("$w x $h rotation=$rotation mirror=$mirror direct=$direct",expected,actual)
                assertEquals(1,source.position());assertEquals(1+count*4,source.limit())
            }
        }
    }

    @Test fun reusedNativeOutputCannotOverwritePublishedSnapshot() {
        val instance=converter();val input=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        repeat(4){input.putFloat(it*4,1f)}
        val first=prepare(instance,input,2,2,0,false)
        val published=ForegroundMask(FrameInfo(2,2,0),2,2,first)
        repeat(4){input.putFloat(it*4,0f)}
        assertSame(first,prepare(instance,input,2,2,0,false))
        val pixels=ByteArray(4);published.pixels().get(pixels)
        assertArrayEquals(ByteArray(4){255.toByte()},pixels)
        assertEquals(0,first.get(0).toInt())
    }

    @Test fun nativeRejectsHeapShortAndInvalidGeometryBeforeWriting() {
        val instance=converter()
        val method=type.getDeclaredMethod("convert",ByteBuffer::class.java,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,ByteBuffer::class.java).apply{isAccessible=true}
        val source=ByteBuffer.allocateDirect(16);val destination=ByteBuffer.allocateDirect(8)
        repeat(8){destination.put(it,91.toByte())}
        fun rejected(input:ByteBuffer,w:Int,h:Int,rotation:Int,output:ByteBuffer) {
            assertEquals(false,method.invoke(instance,input,w,h,rotation,false,output))
            repeat(8){assertEquals(91,destination.get(it).toInt())}
        }
        rejected(ByteBuffer.allocate(16),2,2,0,destination)
        rejected(source,3,2,0,destination)
        rejected(source,2,2,45,destination)
        rejected(source,Int.MAX_VALUE,2,0,destination)
        rejected(source,2,2,0,ByteBuffer.allocateDirect(3))
    }
}
