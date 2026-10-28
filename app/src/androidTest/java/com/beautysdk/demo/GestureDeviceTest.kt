package com.beautysdk.demo

import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.CheckBox
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import com.beautysdk.mediapipe.MediaPipeGestureAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 只使用官方公开手势图片及空白帧，不读取/保存用户图像，不清应用数据。 */
class GestureDeviceTest {
    private fun fixture(name: String,time: Long=0): RgbaFrame {
        val context=InstrumentationRegistry.getInstrumentation().context
        val bitmap=context.assets.open("gesture_$name.jpg").use {BitmapFactory.decodeStream(it)}!!
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val buffer=ByteBuffer.allocateDirect(pixels.size*4)
            pixels.forEach {c ->buffer.put((c ushr 16).toByte()).put((c ushr 8).toByte()).put(c.toByte()).put(255.toByte())}
            buffer.flip()
            return RgbaFrame(FrameInfo(bitmap.width,bitmap.height,time),buffer,bitmap.width*4)
        } finally {bitmap.recycle()}
    }
    @Test fun officialFixturesRecognizeFourGesturesAndReturnReal21Points() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cases=listOf("thumbs_up" to GestureKind.THUMB_UP,"victory" to GestureKind.VICTORY,
            "pointing_up" to GestureKind.POINTING_UP,"thumbs_down" to GestureKind.THUMB_DOWN)
        for((name,kind) in cases) {
            MediaPipeGestureAnalyzer(context).use {analyzer ->
                val hands=analyzer.analyzeRgba(fixture(name),640)
                assertTrue("No hand detected in $name",hands.isNotEmpty())
                val hand=hands.first {it.modelGesture==kind}
                assertEquals(21,hand.landmarks.size);assertTrue(hand.modelScore>.5f)
                assertTrue(hand.landmarks.map {it.x}.max()-hand.landmarks.map {it.x}.min()>.05f)
            }
        }
    }
    @Test fun blankInputHasNoHandsAndCloseIsIdempotent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val analyzer=MediaPipeGestureAnalyzer(context)
        try {
            val buffer=ByteBuffer.allocateDirect(128*128*4)
            assertTrue(analyzer.analyzeRgba(RgbaFrame(FrameInfo(128,128,0),buffer,128*4),384).isEmpty())
        } finally {analyzer.close();analyzer.close()}
    }
    @Test fun allRotationsAndMirrorsMapRealLandmarksBackToRawPixels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=fixture("victory")
        val base=MediaPipeGestureAnalyzer(context).use {it.analyzeRgba(original,640).first {h ->h.modelGesture==GestureKind.VICTORY}}
        for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
            val swapped=rotation%180!=0
            val w=if(swapped)original.info.height else original.info.width
            val h=if(swapped)original.info.width else original.info.height
            val pixels=ByteBuffer.allocateDirect(w*h*4)
            val source=original.readOnlyPixels()
            for(y in 0 until h)for(x in 0 until w) {
                val rx=(x+.5f)/w;val ry=(y+.5f)/h
                val upright=when(rotation) {
                    90 -> FacePoint(1f-ry,rx);180 ->FacePoint(1f-rx,1f-ry)
                    270 ->FacePoint(ry,1f-rx);else->FacePoint(rx,ry)
                }
                val sx=((if(mirror)1f-upright.x else upright.x)*original.info.width).toInt().coerceIn(0,original.info.width-1)
                val sy=(upright.y*original.info.height).toInt().coerceIn(0,original.info.height-1)
                val offset=sy*original.rowStrideBytes+sx*4
                for(c in 0..3)pixels.put(source.get(offset+c))
            }
            pixels.flip()
            val info=FrameInfo(w,h,0,rotation,mirror)
            MediaPipeGestureAnalyzer(context).use {analyzer ->
                val hand=analyzer.analyzeRgba(RgbaFrame(info,pixels,w*4),640).first {it.modelGesture==GestureKind.VICTORY}
                val mapping=AnalysisCoordinates(info)
                for(i in 0..20) {
                    val expected=mapping.toRaw(base.landmarks[i])
                    assertEquals("x rotation=$rotation mirror=$mirror",expected.x,hand.landmarks[i].x,.035f)
                    assertEquals("y rotation=$rotation mirror=$mirror",expected.y,hand.landmarks[i].y,.035f)
                }
            }
        }
    }
    @Test fun sharedRuntimeTriggersOnceAndDisableClearsResults() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=GestureAnalysisRuntime(MediaPipeGestureAnalyzer.factory(context),
            GestureConfig(minModelScore=.5f,experimentalRules=false),maxSide=640,intervalNs=0)
        try {
            runtime.setEnabled(true)
            val original=fixture("victory")
            val events=mutableListOf<GestureEvent>()
            for(i in 0..4) {
                var sampleTime=i*100_000_000L
                var input=RgbaFrame(original.info.copy(timestampNs=sampleTime),original.readOnlyPixels(),original.rowStrideBytes)
                val end=System.nanoTime()+8_000_000_000L
                while(!runtime.submit(input) && System.nanoTime()<end) {
                    Thread.sleep(5)
                    // 忙时的帧已丢弃，下一次代表新采样，不能重交相同时间戳。
                    sampleTime+=1_000_000L
                    input=RgbaFrame(original.info.copy(timestampNs=sampleTime),original.readOnlyPixels(),original.rowStrideBytes)
                }
                while(System.nanoTime()<end) {
                    val snapshot=runtime.snapshot(input.info)
                    if(snapshot.result?.info?.timestampNs==input.info.timestampNs)break
                    if(snapshot.state==GestureAnalysisState.ERROR)error(snapshot.error.orEmpty())
                    Thread.sleep(5)
                }
                assertEquals(input.info.timestampNs,runtime.snapshot(input.info).result?.info?.timestampNs)
                events+=runtime.pollEvents(input.info)
            }
            assertEquals(1,events.count {it.kind==GestureKind.VICTORY && it.phase==GestureEventPhase.TRIGGER})
            runtime.setEnabled(false)
            assertEquals(GestureAnalysisState.DISABLED,runtime.snapshot(original.info).state)
            assertTrue(runtime.pollEvents(original.info).isEmpty())
        } finally {runtime.close()}
    }
    @Test fun demoSwitchControlsIndependentBranchAndCameraLifecycle() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val received=CountDownLatch(1)
        lateinit var preview: GpuBeautyPreview
        try {
            inst.runOnMainSync {
                preview=activity.findViewById(R.id.camera_gpu_preview)
                assertFalse(preview.options.gestureEnabled)
                val original=preview.onGestures
                preview.onGestures={snapshot,events ->original(snapshot,events)
                    if(snapshot.state in listOf(GestureAnalysisState.READY,GestureAnalysisState.NO_HANDS))received.countDown()}
                activity.findViewById<CheckBox>(R.id.camera_gesture_enabled).isChecked=true
                assertTrue(preview.options.gestureEnabled)
            }
            assertTrue("No hand branch result from camera",received.await(20,TimeUnit.SECONDS))
            inst.runOnMainSync {
                activity.findViewById<CheckBox>(R.id.camera_gesture_enabled).isChecked=false
                assertFalse(preview.options.gestureEnabled)
                activity.findViewById<View>(R.id.camera_compare).performClick()
            }
        } finally {inst.runOnMainSync {activity.finish()}}
    }
}
