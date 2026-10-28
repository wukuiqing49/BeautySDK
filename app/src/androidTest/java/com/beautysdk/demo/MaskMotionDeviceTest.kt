package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 固定位移与不可信输入验证，避免仅因运动分支有执行就声称补偿有效。 */
class MaskMotionDeviceTest {
    private val type=Class.forName("com.beautysdk.mediapipe.NativeMaskMotion")
    private fun estimate(a:ByteBuffer,b:ByteBuffer,m:ByteBuffer,w:Int,h:Int,stride:Int):FloatArray? =
        type.getDeclaredMethod("estimate",ByteBuffer::class.java,ByteBuffer::class.java,ByteBuffer::class.java,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
            .invoke(type.getField("INSTANCE").get(null),a,b,m,w,h,stride) as FloatArray?
    @Test fun knownPublicPortraitTranslationsHaveCorrectDirection() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val w=192;val h=240;val image=Bitmap.createScaledBitmap(original,w,h,true)
        val colors=IntArray(w*h);image.getPixels(colors,0,w,0,0,w,h)
        if(image!==original)image.recycle();original.recycle()
        fun rgb(dx:Int)=ByteBuffer.allocateDirect(w*h*3).also{out->
            for(y in 0 until h)for(x in 0 until w){val sx=x-dx;val c=if(sx in 0 until w)colors[y*w+sx]else 0xff808080.toInt()
                out.put((c ushr 16).toByte()).put((c ushr 8).toByte()).put(c.toByte())};out.flip()}
        val mask=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());repeat(w*h){mask.putFloat(1f)};mask.flip()
        val a=rgb(0)
        for(shift in listOf(0,-12,12,-24,24)) {
            val result=estimate(a,rgb(shift),mask,w,h,w*3)
            android.util.Log.i("MaskMotionFixture","shift=$shift result=${result?.toList()}")
            assertNotNull("shift=$shift",result)
            assertEquals(shift.toFloat(),result!![0],2f);assertEquals(0f,result[1],2f)
        }
    }
    @Test fun flatOrNoForegroundImagesRejectCorrection() {
        val a=ByteBuffer.allocateDirect(96*96*3);val b=ByteBuffer.allocateDirect(96*96*3)
        val mask=ByteBuffer.allocateDirect(96*96*4).order(ByteOrder.nativeOrder())
        repeat(96*96){mask.putFloat(1f)};mask.flip()
        assertNull(estimate(a,b,mask,96,96,96*3))
        mask.clear();repeat(96*96){mask.putFloat(0f)};mask.flip()
        assertNull(estimate(a,b,mask,96,96,96*3))
    }

    @Test fun incompatibleForegroundMotionsAndUnrelatedScenesRejectCorrection() {
        val w=96;val h=96;val random=java.util.Random(42)
        val values=ByteArray(w*h){random.nextInt(256).toByte()}
        fun rgb(split:Boolean)=ByteBuffer.allocateDirect(w*h*3).also{out->
            for(y in 0 until h)for(x in 0 until w){
                val sx=if(split)x-(if(y<h/2)6 else -6)else x
                val v=if(sx in 0 until w)values[y*w+sx]else 0.toByte()
                out.put(v).put(v).put(v)
            };out.flip()
        }
        val mask=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder())
        repeat(w*h){mask.putFloat(1f)};mask.flip()
        assertNull("Do not shift whole person when parts move in opposite directions",estimate(rgb(false),rgb(true),mask,w,h,w*3))
        val unrelated=ByteBuffer.allocateDirect(w*h*3);repeat(w*h*3){unrelated.put(random.nextInt(256).toByte())};unrelated.flip()
        assertNull("Scene change must not become a reliable translation",estimate(rgb(false),unrelated,mask,w,h,w*3))
        mask.clear();repeat(w*h){mask.putFloat(0f)};mask.flip()
        assertNull("Background texture must not drive the foreground",estimate(rgb(false),rgb(false),mask,w,h,w*3))
    }
}
