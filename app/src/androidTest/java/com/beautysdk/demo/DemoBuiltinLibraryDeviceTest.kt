package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 不手动导入任何资源，验证实际Demo默认接到APK中的完整库。 */
class DemoBuiltinLibraryDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    @Test fun cameraUsesLatestBuiltinJsonAndAppliesControlsWithoutManualImport(){
        File(context.filesDir,"beauty_resources/library.json").delete()
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel
        lateinit var panel:View
        fun ready(check:(ResourceCenterModel.Ui)->Boolean={true}):ResourceCenterModel.Ui{
            val end=System.nanoTime()+20_000_000_000L
            while(System.nanoTime()<end){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);if(check(it))return it}};Thread.sleep(25)}
            error("Builtin camera configuration timeout")
        }
        try{
            inst.runOnMainSync{
                model=ViewModelProvider(activity)[ResourceCenterModel::class.java]
                panel=activity.findViewById(R.id.camera_controls_panel)
                activity.findViewById<View>(R.id.camera_settings).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
            }
            val ui=ready{it.library!=null}
            assertEquals(50,ui.library!!.resources.size);assertEquals(4,ui.library!!.gestureResources.size)
            assertEquals(15,ui.library!!.categories.sumOf{it.effects.size})
            inst.waitForIdleSync()
            inst.runOnMainSync{
                assertTrue(panel.findViewWithTag<SeekBar>("library_parameter_beauty.smoothing").isShown)
                assertFalse(panel.findViewById<View>(R.id.beauty_section).isShown)
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(2).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                assertTrue(panel.findViewWithTag<SeekBar>("library_parameter_makeup.lipstick").isShown)
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(1).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                panel.findViewWithTag<View>("reshape.slimNose").performClick()
                assertTrue(panel.findViewWithTag<SeekBar>("library_parameter_reshape.slimNose").isShown)
                model.parameters(mapOf("reshape.slimNose" to .31f))
            }
            ready{it.settings!!.adjustments.slimNose==.31f}
            inst.runOnMainSync{
                val preview=activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                assertEquals(.31f,preview.options.effects.themeSettings!!.adjustments.slimNose)
            }
            val gesture=ready().gestureResources.first()
            inst.runOnMainSync{
                val tab=panel.findViewWithTag<View>("camera_gesture_category")
                assertNotNull("Camera must expose gesture category",tab);tab.performClick()
            }
            inst.waitForIdleSync();Thread.sleep(250)
            inst.runOnMainSync{
                val card=panel.findViewWithTag<View>("resource_${gesture.hash}")
                assertNotNull("Gesture resource card missing",card);assertTrue(card.isShown)
                if(gesture.hash !in model.ui.value!!.gestureSelection)card.performClick()
            }
            ready{gesture.hash in it.gestureSelection&&it.gestureEffects!=null}
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
}
