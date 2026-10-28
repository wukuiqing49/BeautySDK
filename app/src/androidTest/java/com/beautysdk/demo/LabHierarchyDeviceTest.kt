package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.camera.CameraLens
import com.beautysdk.demo.feature.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 精简入口必须保留真实菜单路由、回调详情以及分类关闭的隔离语义。 */
class LabHierarchyDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun launch()=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
    private fun main(action:()->Unit){inst.runOnMainSync(action);inst.waitForIdleSync()}
    private fun more(a:CameraTestActivity,id:Int) {
        a.findViewById<View>(R.id.camera_more).performClick()
        val popup=CameraTestActivity::class.java.getDeclaredField("moreMenu").apply{isAccessible=true}.get(a) as androidx.appcompat.widget.PopupMenu
        assertNotNull(popup.menu.findItem(id));assertTrue(popup.menu.performIdentifierAction(id,0));popup.dismiss()
    }

    @Test fun singleEntryKeepsCallbacksAndOptionalInsetAccessible() {
        val a=launch()
        try {
            main {
                val preview=a.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview);preview.stop()
                assertTrue(a.findViewById<View>(R.id.camera_settings).isShown)
                for(id in listOf(R.id.camera_stickers,R.id.camera_workshop,R.id.camera_debug,R.id.camera_effect_container))
                    assertFalse(a.findViewById<View>(id).isShown)
                assertTrue(a.findViewById<View>(R.id.camera_face_summary).isShown)
                assertTrue(a.findViewById<View>(R.id.camera_hand_summary).isShown)
                val details=a.findViewById<View>(R.id.camera_callback_details)
                more(a,R.id.camera_callback_details);assertTrue(details.isShown)
                repeat(16) { i->preview.onImage(GpuDemoStats(640,480,CameraLens.FRONT,DemoBackend.MEDIAPIPE,
                    if(i%2==0)1 else 0,0,1,DemoFaceStatus(if(i%2==0)DemoTrackingState.READY else DemoTrackingState.NO_FACE,0,1,0,null),i.toLong())) }
                val history=details.findViewById<android.widget.TextView>(R.id.camera_event_history).text.lines()
                assertEquals(12,history.size)
                assertTrue(history.first().contains(a.getString(R.string.camera_callback_face_leave,16)))
                val dialog=CameraTestActivity::class.java.getDeclaredField("callbackDialog").apply{isAccessible=true}.get(a) as android.app.Dialog
                dialog.dismiss()
            }
            main { enableOriginalInset(a) }
            main {
                assertTrue(a.findViewById<View>(R.id.camera_effect_container).isShown)
                more(a,R.id.camera_effect_container)
                assertFalse(a.findViewById<View>(R.id.camera_effect_container).isShown)
                more(a,R.id.camera_debug)
                val dialog=CameraTestActivity::class.java.getDeclaredField("settingsDialog").apply{isAccessible=true}.get(a) as android.app.Dialog
                assertTrue(dialog.findViewById<View>(R.id.debug_section).isShown)
                assertEquals(View.GONE,dialog.findViewById<View>(R.id.effect_category_scroll).visibility)
            }
        } finally {main{a.finish()}}
    }

    @Test fun categoryOffPreservesOtherEffectsAndBeautyUsesOneSlider() {
        val file=File(inst.targetContext.filesDir,"beauty_resources/user-state.json");val previous=file.takeIf{it.exists()}?.readBytes()
        val a=launch();lateinit var model:ResourceCenterModel;lateinit var panel:View
        fun ready():ResourceCenterModel.Ui {
            val end=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<end){var ui:ResourceCenterModel.Ui?=null;main{ui=model.ui.value}
                ui?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);return it}};Thread.sleep(25)}
            error("Resource model did not settle")
        }
        fun category(index:Int)=main {panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(index).performClick()}
        fun off() {main{panel.findViewById<View>(R.id.resource_none).performClick()};ready()}
        try {
            main{model=ViewModelProvider(a)[ResourceCenterModel::class.java];panel=a.findViewById(R.id.camera_controls_panel)};ready()
            main {
                a.findViewById<View>(R.id.camera_settings).performClick()
                assertTrue(panel.findViewById<View>(R.id.camera_adjust).isSelected)
                panel.findViewById<View>(R.id.camera_whitening_choice).performClick()
                assertEquals(View.GONE,panel.findViewById<View>(R.id.camera_smoothing_control).visibility)
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.camera_whitening_control).visibility)
                model.parameters(mapOf("beauty.whitening" to .6f,"reshape.slimFace" to .5f,"makeup.lipstick" to .4f))
            };ready()
            main{panel.findViewById<View>(R.id.camera_presets).performClick()};off()
            var settings=ready().settings!!
            assertEquals(0f,settings.whitening,0f);assertEquals(.5f,settings.adjustments.slimFace,0f);assertEquals(.4f,settings.adjustments.lipstick,0f)
            category(2);main{panel.findViewById<View>(R.id.camera_presets).performClick()};off()
            settings=ready().settings!!;assertEquals(0f,settings.adjustments.lipstick,0f);assertEquals(.5f,settings.adjustments.slimFace,0f)
            main{model.scene(BeautyScene(background=BackgroundMode.COLOR,motion=CameraMotion.PAN),true,true)};ready()
            category(5);off();settings=ready().settings!!
            assertEquals(BackgroundMode.NONE,settings.scene.background);assertEquals(CameraMotion.PAN,settings.scene.motion)
            category(6);off();assertEquals(CameraMotion.NONE,ready().settings!!.scene.motion)
        } finally {
            main{a.finish()}
            if(previous==null)file.delete()else file.writeBytes(previous)
        }
    }
}
