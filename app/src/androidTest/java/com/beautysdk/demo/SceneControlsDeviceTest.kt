package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.Spinner
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.demo.feature.app.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 读取渲染使用的最终设置，而非已被覆盖的旧控件快照。 */
class SceneControlsDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    @Test fun controlsAndRapidEditsReachFinalSettingsAndResetOnlyCurrentCategory() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val file=File(context.filesDir,"beauty_resources/user-state.json");val previous=file.takeIf{it.exists()}?.readBytes()
        val a=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel
        lateinit var preview:GpuBeautyPreview
        val backgroundReady=java.util.concurrent.CountDownLatch(1)
        lateinit var panel:View
        fun waitFor(check:()->Boolean) {
            val end=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<end) {var ok=false;inst.runOnMainSync {ok=check()};if(ok)return;Thread.sleep(25)}
            fail("Final settings did not reach expected state")
        }
        try {
            inst.runOnMainSync {model=ViewModelProvider(a)[ResourceCenterModel::class.java];preview=a.findViewById(R.id.camera_gpu_preview)}
            inst.runOnMainSync {val old=preview.onSceneStatus;preview.onSceneStatus={enabled,ready->old(enabled,ready);if(enabled&&ready)backgroundReady.countDown()}}
            waitFor {model.ui.value?.settings!=null && model.ui.value?.busy==false}
            inst.runOnMainSync {
                a.findViewById<View>(R.id.camera_settings).performClick()
                val dialog=CameraTestActivity::class.java.getDeclaredMethod("getSettingsDialog").apply{isAccessible=true}.invoke(a) as android.app.Dialog
                panel=dialog.findViewById(R.id.camera_controls_panel)
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(5).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                panel.findViewById<Spinner>(R.id.camera_background).setSelection(3)
            }
            waitFor {preview.options.effects.themeSettings?.scene?.background==BackgroundMode.IMAGE}
            inst.runOnMainSync {
                panel.findViewById<LinearLayout>(R.id.effect_categories).getChildAt(6).performClick()
                panel.findViewById<View>(R.id.camera_adjust).performClick()
                panel.findViewById<Spinner>(R.id.camera_motion).setSelection(2)
            }
            waitFor {preview.options.effects.themeSettings?.scene?.motion==CameraMotion.PAN}
            inst.runOnMainSync {
                model.parameters(mapOf("beauty.whitening" to .8f))
                model.parameters(mapOf("reshape.slimFace" to .7f))
                model.enabled(false);model.enabled(true)
            }
            waitFor {val s=preview.options.effects.themeSettings;s?.whitening==.8f && s.adjustments.slimFace==.7f && model.ui.value?.busy==false}
            inst.runOnMainSync {panel.findViewById<View>(R.id.camera_reset).performClick()}
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val dialog=CameraTestActivity::class.java.getDeclaredField("resetDialog").apply{isAccessible=true}.get(a) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                assertTrue(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick())
            }
            waitFor {val s=preview.options.effects.themeSettings?.scene;s?.motion==CameraMotion.NONE && s.background==BackgroundMode.IMAGE}
            // 独立资源页改运镜后返回，只更新该槽位，不丢相机手动设置的背景。
            inst.runOnMainSync {
                val motion=model.ui.value!!.installed.first {it.descriptor.category==com.beautysdk.resources.ResourceCategory.MOTION}
                val store=com.beautysdk.resources.BeautyResourceStateStore(file)
                val saved=requireNotNull(store.read())
                store.write(saved.copy(selections=saved.selections+(motion.descriptor.slot to motion.hash)))
                model.refresh()
            }
            waitFor {val scene=preview.options.effects.themeSettings?.scene;scene?.background==BackgroundMode.IMAGE && scene.motion==CameraMotion.KEYFRAMES && model.ui.value?.busy==false}
            val frame=java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync {val old=preview.onImage;preview.onImage={old(it);frame.countDown()}}
            assertTrue("Expected live processed frame",frame.await(15,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Camera small-frame mask must map to live output",backgroundReady.await(15,java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            inst.runOnMainSync {a.finish()};inst.waitForIdleSync()
            if(previous==null)file.delete()else file.writeBytes(previous)
        }
    }
}

