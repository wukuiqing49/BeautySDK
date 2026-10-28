package com.beautysdk.demo
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** 七类效果共用同一弹框；分类仅切视图，不重启视频管线。 */
class EffectPanelDeviceTest {
    @Test fun categoriesKeepSinglePanelAndResourceEditorIsSecondary() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var panel:View
        try {
            inst.runOnMainSync { panel=activity.findViewById(R.id.camera_controls_panel);activity.findViewById<View>(R.id.camera_settings).performClick() }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val tabs=panel.findViewById<LinearLayout>(R.id.effect_categories)
                assertEquals(8,tabs.childCount)
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.beauty_section).visibility)
                assertEquals(View.GONE,panel.findViewById<View>(R.id.resource_center).visibility)
                assertFalse(panel.findViewById<View>(R.id.camera_makeup_entry).isShown)
                val parent=panel.parent
                for((index,target) in listOf(0 to R.id.basic_beauty_controls,1 to R.id.camera_extended_section,2 to R.id.camera_extended_section,3 to R.id.camera_extended_section,4 to R.id.sticker_section,5 to R.id.background_controls,6 to R.id.motion_controls)) {
                    tabs.getChildAt(index).performClick()
                    if(index!=4)panel.findViewById<View>(R.id.camera_adjust).performClick()
                    assertSame(parent,panel.parent);assertEquals(View.VISIBLE,panel.findViewById<View>(target).visibility)
                    if(index!=4)assertEquals(View.GONE,panel.findViewById<View>(R.id.resource_center).visibility)
                }
                assertEquals(View.GONE,panel.findViewById<View>(R.id.resource_management).visibility)
                assertEquals(View.GONE,panel.findViewById<View>(R.id.workshop_section).visibility)
                assertTrue(activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview).effectIsMain)
                assertTrue(activity.findViewById<View>(R.id.camera_performance).isShown)
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                // 截图仅绘制面板，不包含相机；默认预设页与微调页各留一份。
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(0).performClick()
                panel.findViewById<View>(R.id.camera_presets).performClick()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val art=android.graphics.Bitmap.createBitmap(panel.width,panel.height,android.graphics.Bitmap.Config.ARGB_8888)
                panel.draw(android.graphics.Canvas(art))
                java.io.File(activity.cacheDir,"camera-controls-panel.png").outputStream().use {art.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                art.recycle()
            }
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
