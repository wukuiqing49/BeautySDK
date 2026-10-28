package com.beautysdk.demo

import android.content.Intent
import android.widget.CheckBox
import android.widget.Spinner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** 验证真正退出后新页面恢复，而非仅依赖Activity savedInstanceState；结束还原用户设置。 */
class CameraStickerPersistenceDeviceTest {
    @Test fun reopenRestoresStylesAndClearRemainsCleared() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val preferences=context.getSharedPreferences("camera_stickers",0)
        val original=preferences.all.toMap()
        val workshopPreferences=context.getSharedPreferences("sticker_workshop_state",0)
        val hadWorkshopFlag=workshopPreferences.contains("draft.zip.enabled")
        val workshopEnabled=workshopPreferences.getBoolean("draft.zip.enabled",false)
        var activity:CameraTestActivity?=null
        fun open():CameraTestActivity {
            val result=instrumentation.startActivitySync(Intent(context,CameraTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
            activity=result
            instrumentation.waitForIdleSync()
            return result
        }
        fun close() {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync();activity=null
        }
        try {
            preferences.edit().clear().commit()
            val first=open()
            instrumentation.runOnMainSync {
                first.findViewById<Spinner>(R.id.camera_glasses_style).setSelection(2)
                first.findViewById<Spinner>(R.id.camera_head_style).setSelection(3)
                first.findViewById<CheckBox>(R.id.camera_glasses).isChecked=true
                first.findViewById<CheckBox>(R.id.camera_moustache).isChecked=true
                first.findViewById<CheckBox>(R.id.camera_hat).isChecked=true
            }
            instrumentation.waitForIdleSync();close()
            val second=open()
            instrumentation.runOnMainSync {
                assertEquals(2,second.findViewById<Spinner>(R.id.camera_glasses_style).selectedItemPosition)
                assertEquals(3,second.findViewById<Spinner>(R.id.camera_head_style).selectedItemPosition)
                listOf(R.id.camera_glasses,R.id.camera_moustache,R.id.camera_hat).forEach {
                    assertTrue(second.findViewById<CheckBox>(it).isChecked)
                }
                second.findViewById<android.view.View>(R.id.camera_clear_stickers).performClick()
            }
            close()
            val third=open()
            instrumentation.runOnMainSync {
                listOf(R.id.camera_glasses,R.id.camera_moustache,R.id.camera_hat).forEach {
                    assertFalse(third.findViewById<CheckBox>(it).isChecked)
                }
            }
        } finally {
            close()
            workshopPreferences.edit().apply {
                if(hadWorkshopFlag) putBoolean("draft.zip.enabled",workshopEnabled) else remove("draft.zip.enabled")
            }.commit()
            preferences.edit().clear().apply {
                original.forEach { (key,value) -> when(value) {
                    is Boolean -> putBoolean(key,value)
                    is Int -> putInt(key,value)
                } }
            }.commit()
        }
    }
}
