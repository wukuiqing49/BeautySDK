package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** 验证真实页面的布局、详情边界和3D状态；只绘制UI View，不截取相机图像。 */
class ResourceExperienceDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    @get:org.junit.Rule val configuration=UiPreviewConfigurationRule()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private fun settled(model:ResourceCenterModel){
        val until=System.nanoTime()+25_000_000_000L
        while(System.nanoTime()<until){var ready=false;inst.runOnMainSync{val ui=model.ui.value;assertNull(ui?.error);ready=ui?.settings!=null&&!ui.busy};if(ready){inst.waitForIdleSync();Thread.sleep(200);return};Thread.sleep(40)}
        error("Resource UI timeout")
    }
    private fun capture(view:View,name:String){
        inst.runOnMainSync{
            assertTrue(view.width>0&&view.height>0)
            InstrumentationRegistry.getArguments().getString("uiExpectedFont")?.let{assertEquals(it.toFloat(),view.resources.configuration.fontScale,0f)}
            InstrumentationRegistry.getArguments().getString("uiRotation")?.let{assertEquals(if(it=="1")android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT,view.resources.configuration.orientation)}
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(context.cacheDir,"ui-refresh-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun resourceDetailsDoNotApplyUntilExplicitUseAndLastCardIsReachable(){
        val activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity
        lateinit var model:ResourceCenterModel
        try{
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java];activity.findViewById<View>(R.id.resource_local_tab).performClick()};settled(model)
            lateinit var before:Map<String,String?>;lateinit var grid:RecyclerView
            inst.runOnMainSync{
                before=model.ui.value!!.selection.toMap();grid=activity.findViewById(R.id.resource_grid)
                assertNull(activity.findViewById<View>(R.id.camera_gpu_preview))
                assertEquals(View.GONE,activity.findViewById<View>(R.id.resource_official).visibility)
                assertTrue((grid.layoutManager as GridLayoutManager).spanCount>=2)
                assertTrue(grid.adapter!!.itemCount>=3);assertTrue(grid.getChildAt(0).height>0)
                grid.getChildAt(0).performClick()
                assertEquals(before,model.ui.value!!.selection)
                val center=ResourceCenterActivity::class.java.getDeclaredField("center").apply{isAccessible=true}.get(activity)
                val dialog=ResourceCenterController::class.java.getDeclaredField("detail").apply{isAccessible=true}.get(center) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing);dialog.getButton(-1).performClick()
            };settled(model)
            inst.runOnMainSync{assertTrue(model.ui.value!!.selection.values.any{it!=null});grid.scrollToPosition(grid.adapter!!.itemCount-1)}
            inst.waitForIdleSync();Thread.sleep(250)
            inst.runOnMainSync{
                val last=grid.layoutManager!!.findViewByPosition(grid.adapter!!.itemCount-1)
                assertNotNull(last);val rect=android.graphics.Rect();assertTrue(last!!.getGlobalVisibleRect(rect));assertEquals(last.height,rect.height())
            }
            capture(activity.findViewById(R.id.root),"resources")
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
    @Test fun parameterSelectionUsesOneStableSliderAndPanelCanExpand(){
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel;lateinit var panel:ViewGroup
        try{
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java];panel=activity.findViewById(R.id.camera_controls_panel)};settled(model)
            inst.runOnMainSync{activity.findViewById<View>(R.id.camera_settings).performClick()};inst.waitForIdleSync();Thread.sleep(250)
            var height=0;lateinit var slider:SeekBar
            inst.runOnMainSync{
                height=panel.height;panel.findViewById<View>(R.id.camera_adjust).performClick()
                slider=panel.findViewWithTag("library_parameter_beauty.smoothing");assertTrue(slider.isShown)
                val choice=panel.findViewWithTag<View>("beauty.whitening")
                assertTrue("Parameter choices must not stretch to fill the sheet",choice.height<=56*choice.resources.displayMetrics.density)
                choice.performClick()
                assertSame(slider,panel.findViewWithTag<SeekBar>("library_parameter_beauty.whitening"))
                slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            };settled(model)
            val sliderBefore=android.graphics.Rect()
            inst.runOnMainSync{
                assertTrue(slider.getGlobalVisibleRect(sliderBefore))
                assertEquals("Slider including its thumb must be fully visible",slider.height,sliderBefore.height())
                assertEquals(View.GONE,panel.findViewById<View>(R.id.resource_status).visibility)
                model.parameters(mapOf("beauty.whitening" to .63f))
            };settled(model)
            inst.runOnMainSync{
                val sliderAfter=android.graphics.Rect();assertTrue(slider.getGlobalVisibleRect(sliderAfter))
                assertEquals("Applying parameters must not shift the slider",sliderBefore,sliderAfter)
                panel.findViewWithTag<View>("beauty.smoothing").performClick()
                panel.findViewWithTag<View>("beauty.whitening").performClick()
                assertEquals("Parameter switch must read the latest values",63,slider.progress)
                assertSame(slider,panel.findViewWithTag<SeekBar>("library_parameter_beauty.whitening"))
                assertEquals(height,panel.height)
                panel.findViewById<View>(R.id.effect_panel_handle).performClick()
            };inst.waitForIdleSync();Thread.sleep(250)
            inst.runOnMainSync{assertTrue(panel.height>=height);panel.findViewById<View>(R.id.effect_panel_handle).performClick()};inst.waitForIdleSync()
            capture(panel,"adjust")
            inst.runOnMainSync{panel.findViewById<View>(R.id.camera_settings_close).performClick();activity.findViewById<View>(R.id.camera_settings).performClick()};inst.waitForIdleSync()
            inst.runOnMainSync{assertTrue(panel.findViewById<View>(R.id.camera_presets).isSelected)}
            capture(panel,"presets")
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
    @Test fun threeDShowsPausedStateAndRestoresNormalResourceBrowser(){
        val prefs=context.getSharedPreferences("camera_stickers",0);val previous=prefs.getInt("sticker_3d",0)
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).putExtra("initial_section",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel
        try{
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java]};settled(model)
            val getter=CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}
            var opened:android.app.Dialog?=null
            val deadline=System.nanoTime()+5_000_000_000L
            while(opened==null&&System.nanoTime()<deadline){
                inst.runOnMainSync{opened=(getter.invoke(activity) as? android.app.Dialog)?.takeIf{it.isShowing}}
                if(opened==null)Thread.sleep(25)
            }
            val dialog=checkNotNull(opened){"Initial sticker sheet must open after layout"}
            inst.runOnMainSync{
                dialog.findViewById<View>(R.id.camera_sticker_mode_3d).performClick()
                assertFalse(dialog.findViewById<View>(R.id.camera_panel_hint).isShown)
                assertTrue(dialog.findViewById<View>(R.id.camera_sticker_mode_2d).isShown)
                assertFalse(dialog.findViewById<View>(R.id.resource_center).isShown)
                dialog.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(1).performClick()
                assertFalse(dialog.findViewById<View>(R.id.camera_panel_hint).isShown)
                dialog.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(4).performClick()
            };inst.waitForIdleSync();capture(dialog.findViewById(R.id.camera_controls_panel),"3d")
            inst.runOnMainSync{
                dialog.findViewById<RadioGroup>(R.id.camera_sticker_3d).check(R.id.camera_sticker_3d_headphones)
                val back=dialog.findViewById<View>(R.id.camera_sticker_mode_2d)
                val rect=android.graphics.Rect();assertTrue(back.getGlobalVisibleRect(rect));assertEquals(back.width,rect.width())
                back.performClick()
                assertEquals(R.id.camera_sticker_3d_none,dialog.findViewById<RadioGroup>(R.id.camera_sticker_3d).checkedRadioButtonId)
                assertNull(activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                assertFalse(dialog.findViewById<View>(R.id.camera_panel_hint).isShown)
                assertTrue(dialog.findViewById<View>(R.id.resource_center).isShown)
            }
            inst.waitForIdleSync();capture(dialog.findViewById(R.id.camera_controls_panel),"2d")
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync();prefs.edit().putInt("sticker_3d",previous).commit()}
    }
}
