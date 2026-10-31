package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import org.junit.Assert.*
import org.junit.Test

/** 独立页面实例恢复真实保存配置；不使用savedInstanceState假装冷启动。 */
class CameraConfigurationDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private var activity:CameraTestActivity?=null
    private lateinit var model:ResourceCenterModel
    private fun waitFor(check:()->Boolean){
        val end=System.nanoTime()+20_000_000_000L
        while(System.nanoTime()<end){var ready=false;inst.runOnMainSync{ready=check()};if(ready){inst.waitForIdleSync();return};Thread.sleep(30)}
        error("Camera configuration timeout")
    }
    private fun open(){
        activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).putExtra("initial_section",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        inst.runOnMainSync{model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java]}
        waitFor{model.ui.value?.let{assertNull(it.error);!it.busy&&it.settings!=null}==true}
        waitFor{(CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}.invoke(activity) as? android.app.Dialog)?.isShowing==true}
    }
    private fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
    private fun panel()=CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}.invoke(activity) as android.app.Dialog
    @Test fun makeupColorsPersistAndRemainIndependentOfStrengthAndLashes() {
        val lip=com.beautysdk.MakeupColor.fromRgb(0xA04455)
        val cheek=com.beautysdk.MakeupColor.fromRgb(0xC77060)
        try {
            open()
            inst.runOnMainSync {
                model.parameters(mapOf("makeup.lipstick" to .6f,"makeup.blusher" to .3f,"makeup.eyelashes" to .4f))
                model.makeupColor("lipstick",lip);model.makeupColor("blusher",cheek)
            }
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings!!.adjustments.makeupColors.blusher==cheek}
            inst.runOnMainSync{model.parameters(mapOf("makeup.eyelashes" to .7f))}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings!!.adjustments.eyelashes==.7f}
            val controller=CameraTestActivity::class.java.getDeclaredField("resourceCenter").apply{isAccessible=true}.get(activity) as ResourceCenterController
            inst.runOnMainSync{controller.category(com.beautysdk.resources.ResourceCategory.MAKEUP);controller.parameterMode(true)}
            waitFor{panel().findViewById<View>(R.id.resource_parameter_host).findViewWithTag<View>("makeup.lipstick")!=null}
            inst.runOnMainSync{
                panel().findViewById<View>(R.id.resource_parameter_host).findViewWithTag<View>("makeup.lipstick").performClick()
                val colorButton=panel().findViewById<View>(R.id.resource_parameter_host).findViewWithTag<View>("makeup_color")
                assertEquals(View.VISIBLE,colorButton.visibility);colorButton.performClick()
            }
            inst.waitForIdleSync()
            val palette=context.resources.getStringArray(R.array.makeup_color_names)
            val choice=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(palette[0]).first()
            assertTrue(choice.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            val chosen=com.beautysdk.MakeupColor.fromRgb(0xC45060)
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings!!.adjustments.makeupColors.lipstick==chosen}
            close();open()
            inst.runOnMainSync {
                val a=model.ui.value!!.settings!!.adjustments
                assertEquals(chosen,a.makeupColors.lipstick);assertEquals(cheek,a.makeupColors.blusher)
                assertEquals(.6f,a.lipstick,0f);assertEquals(.3f,a.blusher,0f);assertEquals(.7f,a.eyelashes,0f)
                assertEquals(a.makeupColors,activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.effects.themeSettings!!.adjustments.makeupColors)
            }
        }finally {close()}
    }

    @Test fun makeupPanelEditsOnlyNamedFieldAndPresetFocusMatches() {
        try {
            open()
            val controller=CameraTestActivity::class.java.getDeclaredField("resourceCenter").apply{isAccessible=true}.get(activity) as ResourceCenterController
            val lash=model.ui.value!!.installed.first{it.descriptor.id=="lash_dense"}
            val lip=model.ui.value!!.installed.first{it.descriptor.id=="lipstick_daily"}
            inst.runOnMainSync {
                model.select(lash,enableBeauty=true)
                model.parameters(mapOf("makeup.lipstick" to .17f,"makeup.blusher" to .29f,"makeup.eyelashes" to .61f))
                controller.category(com.beautysdk.resources.ResourceCategory.MAKEUP)
                controller.parameterMode(true)
            }
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.adjustments?.eyelashes==.61f}
            val host=panel().findViewById<android.widget.FrameLayout>(R.id.resource_parameter_host)
            val expected=mutableMapOf("makeup.lipstick" to .17f,"makeup.blusher" to .29f,"makeup.eyelashes" to .61f)
            for((id,value) in listOf("makeup.eyelashes" to .43f,"makeup.lipstick" to .73f,"makeup.blusher" to .38f,"makeup.lipstick" to 0f)) {
                inst.runOnMainSync {
                    host.findViewWithTag<View>(id).performClick()
                    val slider=host.findViewWithTag<android.widget.SeekBar>("library_parameter_$id")
                    assertNotNull("Named chip must bind the corresponding slider",slider)
                    val definition=model.ui.value!!.library!!.parameters(com.beautysdk.resources.ResourceCategory.MAKEUP).first{it.id==id}
                    val step=kotlin.math.round((value-definition.min)/definition.step).toInt()
                    val args=android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,step.toFloat())}
                    assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                }
                expected[id]=value
                waitFor{!model.ui.value!!.busy&&kotlin.math.abs(com.beautysdk.resources.BeautyParameters.read(model.ui.value!!.settings!!).getValue(id)-value)<.0001f}
                inst.runOnMainSync {
                    val settings=model.ui.value!!.settings!!
                    val preview=activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.effects.themeSettings!!
                    for((key,v) in expected){
                        assertEquals("Editing $id must preserve $key",v,com.beautysdk.resources.BeautyParameters.read(settings).getValue(key),.0001f)
                        assertEquals("Camera must receive the same $key",v,com.beautysdk.resources.BeautyParameters.read(preview).getValue(key),.0001f)
                    }
                    assertNotNull(settings.eyeMakeup)
                }
            }
            // 选了睫毛后再点口红预设，微调必须跟随这次点选，不停在旧的睫毛滑杆。
            inst.runOnMainSync{host.findViewWithTag<View>("makeup.eyelashes").performClick();controller.parameterMode(false)}
            val grid=panel().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.resource_grid)
            waitFor{grid.findViewWithTag<View>("resource_${lip.hash}")!=null}
            inst.runOnMainSync{grid.findViewWithTag<View>("resource_${lip.hash}").performClick()}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.selection[lip.descriptor.slot]==lip.hash}
            inst.runOnMainSync {
                assertEquals(.43f,model.ui.value!!.settings!!.adjustments.eyelashes,.0001f)
                controller.parameterMode(true)
                val slider=host.findViewWithTag<android.widget.SeekBar>("library_parameter_makeup.lipstick")
                assertNotNull("Lipstick preset must focus lipstick, not the previous eyelash slider",slider)
                val args=android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,66f)}
                assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
            }
            waitFor{!model.ui.value!!.busy&&kotlin.math.abs(model.ui.value!!.settings!!.adjustments.lipstick-.66f)<.0001f}
            inst.runOnMainSync {
                val settings=model.ui.value!!.settings!!
                assertEquals(.43f,settings.adjustments.eyelashes,.0001f)
                assertEquals(.38f,settings.adjustments.blusher,.0001f)
                assertEquals(lash.hash,model.ui.value!!.selection[lash.descriptor.slot])
                assertNotNull(settings.eyeMakeup)
            }
            close();open()
            inst.runOnMainSync {
                val settings=model.ui.value!!.settings!!
                assertEquals(.66f,settings.adjustments.lipstick,.0001f)
                assertEquals(.43f,settings.adjustments.eyelashes,.0001f)
                assertEquals(.38f,settings.adjustments.blusher,.0001f)
                assertNotNull(settings.eyeMakeup)
            }
        } finally {close()}
    }
    @Test fun makeupToggleAndDisablePreserveOtherEffectAcrossReopen() {
        try {
            open()
            val lash=model.ui.value!!.installed.first{it.descriptor.id=="lash_dense"}
            val lip=model.ui.value!!.installed.first{it.descriptor.category==com.beautysdk.resources.ResourceCategory.MAKEUP&&it.descriptor.subcategory=="LIPSTICK"}
            inst.runOnMainSync{model.select(lip,enableBeauty=true);model.select(lash,enableBeauty=true);model.parameters(mapOf("makeup.lipstick" to .73f,"makeup.eyelashes" to .64f))}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.adjustments?.eyelashes==.64f}
            inst.runOnMainSync{model.select(lash,toggleMakeup=true)}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.adjustments?.eyelashes==0f}
            inst.runOnMainSync {
                assertNull(model.ui.value!!.settings!!.eyeMakeup)
                assertEquals(.73f,model.ui.value!!.settings!!.adjustments.lipstick,0.0001f)
                assertEquals(lip.hash,model.ui.value!!.selection[lip.descriptor.slot])
            }
            close();open()
            inst.runOnMainSync {
                assertNull(model.ui.value!!.settings!!.eyeMakeup)
                assertEquals(0f,model.ui.value!!.settings!!.adjustments.eyelashes,0f)
                assertEquals(.73f,model.ui.value!!.settings!!.adjustments.lipstick,0.0001f)
                model.select(lash,enableBeauty=true)
            }
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.eyeMakeup!=null}
            inst.runOnMainSync{model.disable(lip.descriptor.slot)}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.adjustments?.lipstick==0f}
            inst.runOnMainSync{assertNotNull(model.ui.value!!.settings!!.eyeMakeup);assertTrue(model.ui.value!!.settings!!.adjustments.eyelashes>0f)}
            close();open()
            inst.runOnMainSync{assertNotNull(model.ui.value!!.settings!!.eyeMakeup);assertEquals(0f,model.ui.value!!.settings!!.adjustments.lipstick,0f)}
        } finally {close()}
    }
    @Test fun parameterBatchesRespectPresetSelectionOrder() {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        try {
            open()
            val lash=model.ui.value!!.installed.first{it.descriptor.id=="lash_dense"}
            val executor=ResourceCenterModel::class.java.getDeclaredField("io").apply{isAccessible=true}.get(model) as java.util.concurrent.ExecutorService
            executor.execute{entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            inst.runOnMainSync {
                model.parameters(mapOf("makeup.eyelashes" to .11f))
                model.select(lash,enableBeauty=true)
                model.parameters(mapOf("makeup.eyelashes" to .62f))
                model.parameters(mapOf("makeup.lipstick" to .71f))
            }
            release.countDown()
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.settings?.adjustments?.eyelashes==.62f}
            inst.runOnMainSync {
                assertEquals(lash.hash,model.ui.value!!.selection[lash.descriptor.slot])
                assertEquals(.71f,model.ui.value!!.settings!!.adjustments.lipstick,0.0001f)
            }
        } finally {release.countDown();close()}
    }
    @Test fun rapidMakeupEditsCoalesceAndPersistAcrossImmediateExit() {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val busyEvents=java.util.concurrent.atomic.AtomicInteger()
        val observer=androidx.lifecycle.Observer<ResourceCenterModel.Ui>{if(it.busy)busyEvents.incrementAndGet()}
        try {
            open()
            inst.runOnMainSync{model.enabled(true)}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.enabled}
            val executor=ResourceCenterModel::class.java.getDeclaredField("io").apply{isAccessible=true}.get(model) as java.util.concurrent.ExecutorService
            executor.execute {entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            inst.runOnMainSync {
                model.ui.observeForever(observer)
                repeat(80){i->model.parameters(mapOf("makeup.lipstick" to (i+1)/100f))}
                model.parameters(mapOf("makeup.eyelashes" to .61f))
                model.ui.removeObserver(observer)
            }
            assertEquals("One blocked save batch must publish loading only once",1,busyEvents.get())
            // 页面退出时IO仍阻塞；释放后新页面必须等已接受的最终值保存。
            close();release.countDown();open()
            inst.runOnMainSync {
                assertEquals(.8f,model.ui.value!!.settings!!.adjustments.lipstick,0.0001f)
                assertEquals(.61f,model.ui.value!!.settings!!.adjustments.eyelashes,0.0001f)
            }
        } finally {
            release.countDown()
            if(::model.isInitialized)inst.runOnMainSync{model.ui.removeObserver(observer)}
            close()
        }
    }
    @Test fun eyelashSelectionAndMakeupStrengthSurviveNewCamera() {
        try {
            open()
            val lash=model.ui.value!!.installed.first{it.descriptor.id=="lash_dense"}
            inst.runOnMainSync { model.select(lash,enableBeauty=true) }
            waitFor { !model.ui.value!!.busy && model.ui.value!!.settings?.eyeMakeup!=null }
            inst.runOnMainSync {
                val controller=CameraTestActivity::class.java.getDeclaredField("resourceCenter").apply{isAccessible=true}.get(activity) as ResourceCenterController
                controller.category(com.beautysdk.resources.ResourceCategory.MAKEUP)
                controller.parameterMode(true)
                val root=panel().findViewById<android.view.ViewGroup>(R.id.camera_controls_panel)
                root.findViewWithTag<View>("makeup.eyelashes").performClick()
                val slider=root.findViewWithTag<android.widget.SeekBar>("library_parameter_makeup.eyelashes")
                // 使用真实fromUser键盘输入，覆盖UI→Model→磁盘路径。
                slider.progress=62
                slider.keyProgressIncrement=1
                slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            waitFor { !model.ui.value!!.busy && model.ui.value!!.settings?.adjustments?.eyelashes==.63f }
            inst.runOnMainSync { model.parameters(mapOf("makeup.lipstick" to .72f)) }
            waitFor { !model.ui.value!!.busy && model.ui.value!!.settings?.adjustments?.lipstick==.72f }
            fun verify() {
                val ui=model.ui.value!!
                assertEquals(lash.hash,ui.selection[lash.descriptor.slot])
                assertTrue(ui.enabled);assertNotNull(ui.settings!!.eyeMakeup)
                assertEquals(.63f,ui.settings.adjustments.eyelashes,0.0001f)
                assertEquals(.72f,ui.settings.adjustments.lipstick,0.0001f)
                val preview=activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
                val applied=preview.options.effects.themeSettings!!
                assertNotNull(applied.eyeMakeup)
                assertEquals(.63f,applied.adjustments.eyelashes,0.0001f)
                assertEquals(.72f,applied.adjustments.lipstick,0.0001f)
                assertEquals(com.beautysdk.demo.feature.app.DemoBeautyBackend.GPUPIXEL,preview.options.beautyBackend)
                assertTrue(preview.options.faceAnalysisEnabled)
            }
            inst.runOnMainSync { verify() }
            close();open()
            inst.runOnMainSync { verify() }
        } finally { close() }
    }
    @Test fun selectedGestureAndCapSurviveNewCameraAndRepeatedTapDoesNotDisable(){
        val prefs=context.getSharedPreferences("camera_stickers",0);val saved=prefs.all.toMap()
        try{
            open()
            inst.runOnMainSync{model.clearGestureEffects()}
            waitFor{!model.ui.value!!.busy&&model.ui.value!!.gestureEffects==null}
            val resource=model.ui.value!!.gestureResources.first()
            inst.runOnMainSync{
                activity!!.findViewById<CheckBox>(R.id.camera_gesture_enabled).isChecked=false
                panel().findViewById<RadioGroup>(R.id.camera_sticker_3d).check(R.id.camera_sticker_3d_cap)
                panel().findViewById<View>(R.id.camera_controls_panel).findViewWithTag<View>("camera_gesture_category").performClick()
            }
            waitFor{panel().findViewById<View>(R.id.resource_grid).findViewWithTag<View>("resource_${resource.hash}")!=null}
            fun tap(){inst.runOnMainSync{panel().findViewById<View>(R.id.resource_grid).findViewWithTag<View>("resource_${resource.hash}").performClick()}}
            tap();waitFor{resource.hash in model.ui.value!!.gestureSelection&&!model.ui.value!!.busy}
            tap();inst.waitForIdleSync()
            inst.runOnMainSync{
                assertEquals(setOf(resource.hash),model.ui.value!!.gestureSelection)
                assertTrue(activity!!.findViewById<CheckBox>(R.id.camera_gesture_enabled).isChecked)
                val card=panel().findViewById<View>(R.id.resource_grid).findViewWithTag<View>("resource_${resource.hash}")
                card.performLongClick()
                val field=ResourceCenterController::class.java.getDeclaredField("detail").apply{isAccessible=true}
                val controller=CameraTestActivity::class.java.getDeclaredField("resourceCenter").apply{isAccessible=true}.get(activity)
                val detail=field.get(controller) as androidx.appcompat.app.AlertDialog
                assertFalse(detail.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).isShown);detail.dismiss()
            }
            close();open()
            inst.runOnMainSync{
                assertEquals(setOf(resource.hash),model.ui.value!!.gestureSelection)
                assertNotNull(model.ui.value!!.gestureEffects)
                val preview=activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
                assertSame(com.beautysdk.BuiltInStickers3D.baseballCap(),preview.options.sticker3D)
                assertTrue(preview.options.gestureEnabled)
                val effects=GpuBeautyPreview::class.java.getDeclaredField("gestureEffects").apply{isAccessible=true}.get(preview) as com.beautysdk.GestureEffectPack
                assertEquals(model.ui.value!!.gestureEffects!!.rules.map{it.id},effects.rules.map{it.id})
                activity!!.findViewById<CheckBox>(R.id.camera_gesture_enabled).isChecked=false
                if(preview.options.faceAnalysisEnabled){
                    activity!!.findViewById<View>(R.id.camera_more).performClick()
                    val menu=CameraTestActivity::class.java.getDeclaredField("moreMenu").apply{isAccessible=true}.get(activity) as androidx.appcompat.widget.PopupMenu
                    assertTrue(menu.menu.performIdentifierAction(R.id.camera_face_summary,0));menu.dismiss()
                }
            }
            close();open()
            inst.runOnMainSync{val options=activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options;assertFalse(options.gestureEnabled);assertFalse(options.faceAnalysisEnabled);assertEquals(setOf(resource.hash),model.ui.value!!.gestureSelection)}
        }finally{
            close();prefs.edit().clear().apply{saved.forEach{(k,v)->when(v){is Int->putInt(k,v);is Boolean->putBoolean(k,v);is String->putString(k,v);is Long->putLong(k,v);is Float->putFloat(k,v)}}}.commit()
        }
    }
}
