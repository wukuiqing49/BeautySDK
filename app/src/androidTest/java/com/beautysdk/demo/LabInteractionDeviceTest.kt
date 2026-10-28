package com.beautysdk.demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.ResourceCategory
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 真实资源状态与面板操作闭环；只绘制控件面板，不保存相机画面。 */
class LabInteractionDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    @Test fun presetsAdjustCancelAndReopenUseOneClearPanel() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val stateFile=File(context.filesDir,"beauty_resources/user-state.json");val previous=stateFile.takeIf{it.exists()}?.readBytes()
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel;lateinit var panel:View
        fun ready():ResourceCenterModel.Ui {
            val end=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<end){var value:ResourceCenterModel.Ui?=null;inst.runOnMainSync{value=model.ui.value}
                value?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);return it}};Thread.sleep(25)}
            error("Lab resource state did not settle")
        }
        fun main(action:()->Unit){inst.runOnMainSync(action);inst.waitForIdleSync()}
        fun save(name:String)=main {
            val image=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(image));File(context.cacheDir,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
        try {
            main{model=ViewModelProvider(activity)[ResourceCenterModel::class.java];panel=activity.findViewById(R.id.camera_controls_panel)}
            ready()
            main{activity.findViewById<View>(R.id.camera_settings).performClick()}
            main {
                assertTrue(panel.findViewById<View>(R.id.camera_adjust).isSelected)
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.beauty_section).visibility)
                panel.findViewById<View>(R.id.camera_presets).performClick()
                assertTrue(panel.findViewById<View>(R.id.camera_presets).isSelected)
                assertEquals(View.GONE,panel.findViewById<View>(R.id.beauty_section).visibility)
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.resource_center).visibility)
                val tabs=panel.findViewById<View>(R.id.effect_category_scroll)
                val before=IntArray(2);tabs.getLocationInWindow(before)
                panel.findViewById<ScrollView>(R.id.controls_scroll).fullScroll(View.FOCUS_DOWN)
                val after=IntArray(2);tabs.getLocationInWindow(after);assertArrayEquals(before,after)
            }
            val beauty=ready().installed.first{it.descriptor.id=="beauty_clear"}
            main{model.disable(beauty.descriptor.slot);model.enabled(false)};ready()
            main{panel.findViewWithTag<View>("resource_${beauty.hash}").performClick()}
            assertEquals(beauty.hash,ready().selection[beauty.descriptor.slot]);assertTrue(ready().enabled)
            main{panel.findViewWithTag<View>("resource_${beauty.hash}").performClick()}
            assertNull(ready().selection[beauty.descriptor.slot])
            save("lab-presets-panel.png")
            main {
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(2).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                assertEquals(View.GONE,panel.findViewById<View>(R.id.resource_center).visibility)
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.beauty_section).visibility)
                val parameter=panel.findViewWithTag<View>("parameter_7");parameter.performClick()
                assertTrue(parameter.isSelected)
                panel.findViewById<View>(R.id.camera_presets).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                assertSame(parameter,panel.findViewWithTag<View>("parameter_7"))
            }
            save("lab-adjust-panel.png")
            val selections=ready().selection
            main{panel.findViewById<View>(R.id.camera_reset).performClick()}
            inst.runOnMainSync {
                val dialog=CameraTestActivity::class.java.getDeclaredField("resetDialog").apply{isAccessible=true}.get(activity) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                assertTrue(dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick())
            }
            inst.waitForIdleSync();assertEquals(selections,ready().selection)
            main{panel.findViewById<View>(R.id.camera_settings_close).performClick()}
            val closed=System.nanoTime()+3_000_000_000L
            while(panel.isShown&&System.nanoTime()<closed)Thread.sleep(25)
            main{activity.findViewById<View>(R.id.camera_settings).performClick();assertTrue(panel.findViewById<View>(R.id.camera_adjust).isSelected);assertTrue(panel.findViewWithTag<View>("parameter_7").isSelected)}
            val theme=ready().installed.first{it.descriptor.category==ResourceCategory.THEME}
            main{panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(7).performClick()}
            // 确定先处于未选中状态，再通过可见卡片启用/取消整套主题。
            main{model.disable("theme")};ready()
            main{panel.findViewWithTag<View>("resource_${theme.hash}").performClick()};assertEquals(theme.hash,ready().selection["theme"])
            main{panel.findViewWithTag<View>("resource_${theme.hash}").performClick()}
            val cancelled=ready();assertNull(cancelled.selection["theme"]);assertTrue(cancelled.selection.values.all{it==null})
            assertEquals(0f,cancelled.settings!!.smoothing,0f);assertTrue(cancelled.settings.adjustments.isIdentity)
        } finally {
            main{activity.finish()}
            if(previous==null)stateFile.delete()else stateFile.writeBytes(previous)
        }
    }
}
