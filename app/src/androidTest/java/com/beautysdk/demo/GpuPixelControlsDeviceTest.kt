package com.beautysdk.demo

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import com.beautysdk.demo.feature.app.DemoBeautyBackend
import org.junit.Assert.*
import org.junit.Test

/** 检查页面控制确实进入GPUPixel处理，不保存相机图像或修改用户贴纸选择。 */
class GpuPixelControlsDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    @Test fun extendedControlUpdatesAndCameraUsesGpuPixel() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val savedFile=java.io.File(inst.targetContext.filesDir,"beauty_resources/user-state.json")
        val savedState=savedFile.takeIf{it.exists()}?.readBytes()
        val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var controls:View
        fun parameter(index:Int) {
            val category=when(index){5,6,9,10,11->1;7,8,12->2;else->3}
            controls.findViewById<android.widget.LinearLayout>(R.id.effect_categories).getChildAt(category).performClick()
            controls.findViewWithTag<View>("parameter_$index").performClick()
        }
        val ready=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<String?>()
        try {
            lateinit var model:ResourceCenterModel
            inst.runOnMainSync {model=androidx.lifecycle.ViewModelProvider(activity)[ResourceCenterModel::class.java]}
            fun awaitModel(check:(ResourceCenterModel.Ui)->Boolean){val end=System.nanoTime()+10_000_000_000L;while(System.nanoTime()<end){var ok=false;inst.runOnMainSync {model.ui.value?.let{ok=!it.busy&&check(it)}};if(ok)return;Thread.sleep(20)};error("Resource model did not settle")}
            awaitModel{it.settings!=null}
            inst.runOnMainSync {model.resetDefaults()}
            awaitModel{it.settings!=null&&it.error==null}
            inst.runOnMainSync {
                controls=activity.findViewById(R.id.camera_controls_panel)
                val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
                val original=preview.onImage
                preview.onImage={ stats ->
                    original(stats)
                    if(stats.beautyBackend==DemoBeautyBackend.GPUPIXEL) {
                        stats.analysis.error?.let(failure::set)
                        ready.countDown()
                    }
                }
                activity.findViewById<View>(R.id.camera_settings).performClick()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals(View.GONE,controls.findViewById<View>(R.id.camera_beauty_backend).visibility)
                assertEquals(View.GONE,controls.findViewById<View>(R.id.camera_extended_section).visibility)
                assertEquals(1,controls.findViewById<Spinner>(R.id.camera_beauty_backend).selectedItemPosition)
                val entry=controls.findViewById<View>(R.id.camera_makeup_entry)
                assertFalse(entry.isShown)
                controls.findViewById<android.widget.LinearLayout>(R.id.effect_categories).getChildAt(2).performClick()
                controls.findViewById<View>(R.id.camera_adjust).performClick()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals(1,controls.findViewById<Spinner>(R.id.camera_beauty_backend).selectedItemPosition)
                assertEquals(7,controls.findViewById<Spinner>(R.id.camera_extended_parameter).selectedItemPosition)
                assertEquals(13,controls.findViewById<Spinner>(R.id.camera_extended_parameter).count)
                assertEquals(View.VISIBLE,controls.findViewById<View>(R.id.camera_extended_section).visibility)
                parameter(1)
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val strength=controls.findViewById<SeekBar>(R.id.camera_extended_strength)
                assertEquals(100,strength.progress)
                strength.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                assertTrue(strength.progress>100)
                assertFalse(controls.findViewById<TextView>(R.id.camera_extended_value).text.toString().isBlank())
                parameter(5)
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val strength=controls.findViewById<SeekBar>(R.id.camera_extended_strength)
                strength.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                assertTrue(strength.progress>0)
                assertEquals(1,controls.findViewById<Spinner>(R.id.camera_backend).selectedItemPosition)
            }
            for(index in listOf(7,8,9,10,11,12)) {
                inst.runOnMainSync { parameter(index) }
                inst.waitForIdleSync()
                inst.runOnMainSync {
                    val strength=controls.findViewById<SeekBar>(R.id.camera_extended_strength)
                    assertEquals(if(index in 10..11) 200 else 100,strength.max)
                    strength.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                    assertTrue(strength.progress>0)
                    assertEquals(1,controls.findViewById<Spinner>(R.id.camera_backend).selectedItemPosition)
                }
            }
            assertTrue("No GPUPixel frame",ready.await(20,java.util.concurrent.TimeUnit.SECONDS))
            assertNull(failure.get())
            inst.runOnMainSync {
                controls.findViewById<View>(R.id.camera_reset).performClick()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val dialog=CameraTestActivity::class.java.getDeclaredField("resetDialog").apply{isAccessible=true}.get(activity) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                assertTrue(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick())
            }
            val deadline=System.nanoTime()+5_000_000_000L
            var reset=false
            while(!reset&&System.nanoTime()<deadline){inst.runOnMainSync {reset=controls.findViewById<SeekBar>(R.id.camera_extended_strength).progress==0};if(!reset)Thread.sleep(20)}
            assertTrue("Makeup reset must finish through resource IO queue",reset)
        } finally { inst.runOnMainSync { activity.finish() };inst.waitForIdleSync();if(savedState==null)savedFile.delete()else savedFile.writeBytes(savedState) }
    }
}
