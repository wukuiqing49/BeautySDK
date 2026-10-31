package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.RadioGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import org.junit.Assert.*
import org.junit.Test

/** 不清空资源中心或用户草稿；仅改变3D选择并在finally恢复原偏好。 */
class Sticker3DUiDeviceTest {
    @Test fun stickerPanelSelectsPersistsAndDisables3D() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val prefs=context.getSharedPreferences("camera_stickers",0);val original=prefs.all.toMap()
        var activity:CameraTestActivity?=null
        val getter=CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}
        fun awaitVisible(message:String,check:()->Boolean){
            val deadline=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<deadline){var ready=false;inst.runOnMainSync{ready=check()};if(ready){inst.waitForIdleSync();return};Thread.sleep(25)}
            error(message)
        }
        fun open():CameraTestActivity {
            val result=inst.startActivitySync(Intent(context,CameraTestActivity::class.java)
                .putExtra("initial_section",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
            activity=result
            awaitVisible("Sticker panel must open and finish layout"){
                (getter.invoke(result) as? android.app.Dialog)?.let{it.isShowing&&it.findViewById<View>(R.id.camera_sticker_mode_3d).isLaidOut}==true
            }
            return result
        }
        fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
        fun panel(a:CameraTestActivity)=getter.invoke(a) as android.app.Dialog
        try {
            val first=open()
            inst.runOnMainSync {panel(first).findViewById<View>(R.id.camera_sticker_mode_3d).performClick()}
            awaitVisible("3D choices must finish layout") {panel(first).findViewById<View>(R.id.camera_sticker_3d).getGlobalVisibleRect(android.graphics.Rect())}
            inst.runOnMainSync {
                val group=panel(first).findViewById<RadioGroup>(R.id.camera_sticker_3d)
                assertTrue("3D controls must be reachable from sticker panel",group.isShown)
                assertTrue("3D choices must be visible before the resource list",group.getGlobalVisibleRect(android.graphics.Rect()))
                group.check(R.id.camera_sticker_3d_cap)
                assertSame(com.beautysdk.BuiltInStickers3D.baseballCap(),first.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                assertEquals(View.GONE,panel(first).findViewById<View>(R.id.camera_panel_hint).visibility)
            }
            inst.waitForIdleSync();close()
            val second=open()
            inst.runOnMainSync {
                val group=panel(second).findViewById<RadioGroup>(R.id.camera_sticker_3d)
                assertEquals(R.id.camera_sticker_3d_cap,group.checkedRadioButtonId)
                group.check(R.id.camera_sticker_3d_hat)
                assertNotNull(second.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                for(id in listOf(R.id.camera_sticker_3d_crown,R.id.camera_sticker_3d_cat,R.id.camera_sticker_3d_headphones,R.id.camera_sticker_3d_cap)){
                    group.check(id)
                    assertNotNull(second.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                    assertTrue(group.findViewById<android.widget.RadioButton>(id).text.contains("3D"))
                }
                group.check(R.id.camera_sticker_3d_none)
                assertNull(second.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                assertEquals(View.GONE,panel(second).findViewById<View>(R.id.camera_panel_hint).visibility)
            }
            close()
            val third=open()
            inst.runOnMainSync {assertEquals(R.id.camera_sticker_3d_none,panel(third).findViewById<RadioGroup>(R.id.camera_sticker_3d).checkedRadioButtonId)}
        }finally {
            close()
            prefs.edit().clear().apply {original.forEach{(key,value)->when(value){
                is Boolean->putBoolean(key,value);is Int->putInt(key,value);is Long->putLong(key,value)
                is Float->putFloat(key,value);is String->putString(key,value)
            }}}.commit()
        }
    }
}
