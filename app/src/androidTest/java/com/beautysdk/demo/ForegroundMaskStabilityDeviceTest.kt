package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ForegroundMaskStabilityDeviceTest {
    private val type=Class.forName("com.beautysdk.mediapipe.NativeMaskConverter")
    private val integer=Int::class.javaPrimitiveType!!
    private val method=type.getDeclaredMethod("prepareStable",ByteBuffer::class.java,integer,integer,
        FrameInfo::class.java,Long::class.javaPrimitiveType,ByteBuffer::class.java,integer,integer,integer)
    private fun sample(converter:Any,alpha:Int,color:Int,time:Long,generation:Long=1,mirror:Boolean=false):Int {
        val source=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply{putFloat(0,alpha/255f)}
        val rgb=ByteBuffer.allocateDirect(3).apply{repeat(3){put(it,color.toByte())}}
        val out=method.invoke(converter,source,1,1,FrameInfo(1,1,time,0,mirror),generation,rgb,1,1,3) as ByteBuffer
        return out.get(0).toInt() and 255
    }

    @Test fun staticConfidenceJitterFallsButChangesAndResetsAreImmediate() {
        val converter=type.getDeclaredConstructor().newInstance()
        var rawDelta=0;var filteredDelta=0;var previousRaw=124;var previous=124
        repeat(30){i->
            val raw=if(i%2==0)124 else 132
            val current=sample(converter,raw,90,1L+i*33_000_000L)
            if(i>0){rawDelta+=kotlin.math.abs(raw-previousRaw);filteredDelta+=kotlin.math.abs(current-previous)}
            assertTrue("Correction bounded relative to current observation",kotlin.math.abs(current-raw)<=8)
            previousRaw=raw;previous=current
        }
        assertTrue("Static noise should drop at least 40%",filteredDelta<rawDelta*.6)
        // 明确前景/背景、画面颜色变化与代次/时间/方向改变立即采用当前值。
        assertEquals(0,sample(converter,0,90,1_000_000_001L))
        assertEquals(255,sample(converter,255,90,1_033_000_001L))
        assertEquals(128,sample(converter,128,90,1_066_000_001L))
        assertEquals(136,sample(converter,136,120,1_099_000_001L))
        assertEquals(128,sample(converter,128,120,1_132_000_001L,generation=2))
        assertEquals(136,sample(converter,136,120,1_400_000_001L,generation=2))
        assertEquals(128,sample(converter,128,120,1_433_000_001L,generation=2,mirror=true))
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","\nSTATIC_MASK rawTotalDelta=$rawDelta stabilizedTotalDelta=$filteredDelta\n")
        })
    }

    @Test fun stableConversionKeepsOrientationAndPublishedPixelsIndependent() {
        val converter=type.getDeclaredConstructor().newInstance()
        val legacy=type.getDeclaredMethod("prepare",ByteBuffer::class.java,integer,integer,integer,Boolean::class.javaPrimitiveType)
        val w=7;val h=5
        val source=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder()).apply{
            repeat(w*h){putFloat(it*4,(it%13)/12f)}
        }
        val rgb=ByteBuffer.allocateDirect(w*h*3).apply{repeat(capacity()){put(it,90.toByte())}}
        for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
            val expected=ByteArray(w*h)
            (legacy.invoke(converter,source,w,h,rotation,mirror) as ByteBuffer).duplicate().get(expected)
            val info=FrameInfo(70,50,1,rotation,mirror)
            val result=method.invoke(converter,source,w,h,info,1L,rgb,w,h,w*3) as ByteBuffer
            val actual=ByteArray(w*h);result.duplicate().get(actual)
            assertArrayEquals(expected,actual)
            val mask=ForegroundMask(info,if(rotation%180==0)w else h,if(rotation%180==0)h else w,result)
            repeat(w*h){source.putFloat(it*4,0f)}
            method.invoke(converter,source,w,h,info.copy(timestampNs=2),1L,rgb,w,h,w*3)
            val retained=ByteArray(w*h);mask.pixels().get(retained);assertArrayEquals(expected,retained)
            repeat(w*h){source.putFloat(it*4,(it%13)/12f)}
        }
    }

    @Test fun newerFutureMaskCannotHidePreviousValidMask() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        MediaPipeForegroundMasks(app).use{masks->
            masks.setEnabled(true)
            val publish=masks.javaClass.getDeclaredMethod("publishMask",ForegroundMask::class.java).apply{isAccessible=true}
            fun info(t:Long)=FrameInfo(20,20,t)
            fun add(t:Long,alignment:Long=t)=publish.invoke(masks,ForegroundMask(info(t),2,2,ByteBuffer.allocateDirect(4),alignment))
            add(100);add(120)
            assertEquals(100L,masks.snapshot(info(110))!!.info.timestampNs)
            assertNull(masks.snapshot(info(99)))
            add(140,160)
            assertEquals(140L,masks.snapshot(info(150))!!.alignmentTimestampNs)
            assertEquals(160L,masks.snapshot(info(165))!!.alignmentTimestampNs)
            assertNull(masks.snapshot(info(250_000_141)))
            add(180);assertNull("History is bounded to three actual observations",masks.snapshot(info(110)))
            masks.reset();assertNull(masks.snapshot(info(190)))
        }
    }
}
