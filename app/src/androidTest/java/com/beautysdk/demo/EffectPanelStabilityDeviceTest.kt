package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EffectPanelStabilityDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    @Test fun defaultsToPresetsAndKeepsGeometryAcrossTabsAdjustmentsAndReopen(){
        val context=inst.targetContext
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel;lateinit var panel:View
        fun ready(){val end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){var ok=false;inst.runOnMainSync{val ui=model.ui.value;assertNull(ui?.error);ok=ui?.settings!=null&&!ui.busy};if(ok)return;Thread.sleep(25)};error("Effect panel timeout")}
        fun bounds():List<Int>{val pos=IntArray(2);panel.getLocationOnScreen(pos);return listOf(pos[0],pos[1],panel.width,panel.height)}
        lateinit var expected:List<Int>
        fun stable(action:()->Unit){
            val latch=CountDownLatch(12);val samples=mutableListOf<List<Int>>()
            lateinit var listener:ViewTreeObserver.OnPreDrawListener
            inst.runOnMainSync{
                listener=ViewTreeObserver.OnPreDrawListener{
                    samples+=bounds();latch.countDown()
                    if(latch.count==0L)panel.viewTreeObserver.removeOnPreDrawListener(listener)else panel.postInvalidateOnAnimation()
                    true
                }
                panel.viewTreeObserver.addOnPreDrawListener(listener);action();panel.postInvalidateOnAnimation()
            }
            try{assertTrue("No layout samples",latch.await(8,TimeUnit.SECONDS));assertTrue("Panel moved: expected=$expected actual=$samples",samples.all{it==expected})}
            finally{inst.runOnMainSync{panel.viewTreeObserver.removeOnPreDrawListener(listener)}}
            ready()
        }
        try{
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java];panel=activity.findViewById(R.id.camera_controls_panel)};ready()
            inst.runOnMainSync{activity.findViewById<View>(R.id.camera_settings).performClick()};inst.waitForIdleSync();Thread.sleep(350)
            inst.runOnMainSync{assertTrue(panel.findViewById<View>(R.id.camera_presets).isSelected);assertFalse(panel.findViewById<View>(R.id.camera_adjust).isSelected);expected=bounds()}
            // 仅绘制面板 View，不截取窗口或相机 Surface，不保存摄像头图像。
            if(InstrumentationRegistry.getArguments().getString("capturePreset")=="true"){
                inst.runOnMainSync{
                    val bitmap=android.graphics.Bitmap.createBitmap(panel.width,panel.height,android.graphics.Bitmap.Config.ARGB_8888)
                    panel.draw(android.graphics.Canvas(bitmap))
                    java.io.File(context.cacheDir,"preset-panel.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
            stable{panel.findViewById<View>(R.id.camera_adjust).performClick()}
            lateinit var bar:SeekBar
            inst.runOnMainSync{bar=panel.findViewWithTag("library_parameter_beauty.smoothing");assertTrue(bar.isShown)}
            stable{for(i in 1..12)model.parameters(mapOf("beauty.smoothing" to i/100f))}
            inst.runOnMainSync{assertSame(bar,panel.findViewWithTag<SeekBar>("library_parameter_beauty.smoothing"))}
            stable{panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(1).performClick()}
            inst.runOnMainSync{assertTrue(panel.findViewById<View>(R.id.camera_presets).isSelected)}
            stable{panel.findViewById<View>(R.id.camera_adjust).performClick()}
            stable{panel.findViewById<View>(R.id.camera_presets).performClick()}
            inst.runOnMainSync{panel.findViewById<View>(R.id.camera_adjust).performClick();panel.findViewById<View>(R.id.camera_settings_close).performClick()};inst.waitForIdleSync()
            inst.runOnMainSync{activity.findViewById<View>(R.id.camera_settings).performClick()};inst.waitForIdleSync()
            inst.runOnMainSync{assertTrue(panel.findViewById<View>(R.id.camera_presets).isSelected)}
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
}
