package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.RadioGroup
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import com.beautysdk.resources.ResourceCategory
import org.junit.Assert.*
import org.junit.Test

/** 点击真正的卡片/RadioButton，验证异步选择、渲染配置和冷启动记忆；不保存相机像素。 */
class StickerToggleDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private var activity:CameraTestActivity?=null
    private val current get()=checkNotNull(activity)
    private val model get()=ViewModelProvider(current)[ResourceCenterModel::class.java]
    private fun panel()=CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}.invoke(current) as? android.app.Dialog
    private fun await(message:String,check:()->Boolean) {
        val end=System.nanoTime()+15_000_000_000L
        while(System.nanoTime()<end){var ready=false;inst.runOnMainSync{ready=check()};if(ready){inst.waitForIdleSync();return};Thread.sleep(30)}
        fail(message)
    }
    private fun settled()=await("Resource operation must settle without error") {model.ui.value?.let{!it.busy&&it.settings!=null&&it.error==null}==true}
    private fun open() {
        val automation=inst.uiAutomation
        for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
        activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java).putExtra("initial_section",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        inst.runOnMainSync{current.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        await("Sticker panel must be visible"){panel()?.isShowing==true};settled()
    }
    private fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
    private fun preserve(block:()->Unit) {
        val prefs=inst.targetContext.getSharedPreferences("camera_stickers",0);val saved=prefs.all.toMap()
        try{block()}finally{
            close()
            prefs.edit().clear().apply{saved.forEach{(key,value)->when(value){
                is Boolean->putBoolean(key,value);is Int->putInt(key,value);is Long->putLong(key,value)
                is Float->putFloat(key,value);is String->putString(key,value);is Set<*>->putStringSet(key,value.filterIsInstance<String>().toSet())
            }}}.commit()
        }
    }
    @Test fun twoDTapAgainCancelsRapidDoubleTapAndPersistsOff()=preserve {
        open()
        inst.runOnMainSync{panel()!!.findViewById<View>(R.id.camera_sticker_mode_2d).performClick()}
        await("Off card must be bound"){panel()!!.findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("resource_none")!=null}
        inst.runOnMainSync{panel()!!.findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("resource_none").performClick()};settled()
        var hash="";var slot="";var smoothing=0f;var whitening=0f
        await("2D sticker cards must be bound"){
            val grid=panel()!!.findViewById<RecyclerView>(R.id.resource_grid)
            val tag=grid.getChildAt(1)?.tag as? String
            val resource=model.ui.value!!.installed.firstOrNull{"resource_${it.hash}"==tag&&it.descriptor.category==ResourceCategory.STICKER}
            if(resource!=null){hash=resource.hash;slot=resource.descriptor.slot;true}else false
        }
        fun click()=panel()!!.findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("resource_$hash").performClick()
        inst.runOnMainSync{smoothing=model.ui.value!!.settings!!.smoothing;whitening=model.ui.value!!.settings!!.whitening;click()};settled()
        await("First tap selects sticker and checkmark"){model.ui.value!!.selection[slot]==hash&&panel()!!.findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("resource_$hash").isSelected}
        inst.runOnMainSync{click()};settled()
        await("Second tap clears sticker and checkmark"){model.ui.value!!.selection[slot]==null&&!panel()!!.findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("resource_$hash").isSelected}
        inst.runOnMainSync{click();click()};settled()
        inst.runOnMainSync{
            assertNull("Queued double tap must finish disabled",model.ui.value!!.selection[slot])
            assertEquals(smoothing,model.ui.value!!.settings!!.smoothing,0f)
            assertEquals(whitening,model.ui.value!!.settings!!.whitening,0f)
        }
        close();open()
        inst.runOnMainSync{assertNull("Reopening must not restore cancelled sticker",model.ui.value!!.selection[slot]);assertNull(current.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)}
    }
    @Test fun moustacheSurvivesMakeupEditsDeletionAndReopen()=preserve {
        open()
        inst.runOnMainSync{panel()!!.findViewById<View>(R.id.camera_sticker_mode_2d).performClick();model.clearStickers()};settled()
        val moustache=model.ui.value!!.installed.first {it.descriptor.slot=="sticker.moustache"}
        val lash=model.ui.value!!.installed.first {it.descriptor.category==ResourceCategory.MAKEUP&&it.descriptor.subcategory=="EYELASH"}
        inst.runOnMainSync{model.select(moustache);model.select(lash);model.parameters(mapOf("makeup.eyelashes" to .7f))};settled()
        val image=model.ui.value!!.settings!!.stickerPack!!.layers.single().image.copyArgb()
        fun assertMoustache() {
            val settings=model.ui.value!!.settings!!
            assertEquals(moustache.hash,model.ui.value!!.selection[moustache.descriptor.slot])
            assertArrayEquals(image,settings.stickerPack!!.layers.single().image.copyArgb())
            assertNotNull(current.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.effects.themeSettings!!.stickerPack)
        }
        for((key,value) in mapOf("makeup.lipstick" to .6f,"makeup.blusher" to .3f,"beauty.whitening" to .4f)) {
            inst.runOnMainSync{model.parameters(mapOf(key to value))};settled()
            inst.runOnMainSync{assertMoustache();assertEquals(.7f,model.ui.value!!.settings!!.adjustments.eyelashes,0f)}
        }
        inst.runOnMainSync{model.uninstall(lash)};settled()
        inst.runOnMainSync{
            assertMoustache();assertNull(model.ui.value!!.settings!!.eyeMakeup)
            assertEquals(0f,model.ui.value!!.settings!!.adjustments.eyelashes,0f)
            assertEquals(.6f,model.ui.value!!.settings!!.adjustments.lipstick,0f)
        }
        close();open()
        inst.runOnMainSync{assertMoustache();assertEquals(0f,model.ui.value!!.settings!!.adjustments.eyelashes,0f)}
    }
    @Test fun everyThreeDChoiceCanBeDeselectedByClickAndAccessibility()=preserve {
        open()
        val ids=listOf(R.id.camera_sticker_3d_glasses,R.id.camera_sticker_3d_hat,R.id.camera_sticker_3d_crown,R.id.camera_sticker_3d_cat,R.id.camera_sticker_3d_headphones,R.id.camera_sticker_3d_cap)
        for((index,id) in ids.withIndex()) {
            inst.runOnMainSync{
                panel()!!.findViewById<View>(R.id.camera_sticker_mode_3d).performClick()
                val group=panel()!!.findViewById<RadioGroup>(R.id.camera_sticker_3d)
                if(group.checkedRadioButtonId==id)group.findViewById<View>(R.id.camera_sticker_3d_hat).performClick()
                val button=group.findViewById<View>(id)
                button.performClick()
                assertEquals(id,group.checkedRadioButtonId)
                assertNotNull(current.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                if(index%2==0)button.performClick()else assertTrue(button.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null))
                assertEquals(R.id.camera_sticker_3d_none,group.checkedRadioButtonId)
                assertNull(current.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
                assertTrue(panel()!!.findViewById<View>(R.id.camera_sticker_mode_2d).isSelected)
            }
            settled()
            inst.runOnMainSync{assertTrue(model.ui.value!!.selection.filterKeys{it.startsWith("sticker.")}.values.all{it==null})}
        }
        close();open()
        inst.runOnMainSync{
            assertEquals(R.id.camera_sticker_3d_none,panel()!!.findViewById<RadioGroup>(R.id.camera_sticker_3d).checkedRadioButtonId)
            assertNull(current.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.sticker3D)
        }
    }
}
