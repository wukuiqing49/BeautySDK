package com.beautysdk.demo

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import com.beautysdk.resources.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 关闭ViewModel后从磁盘创建新实例，再由相机消费，不能靠旧页面字段冒充恢复。 */
class ScenePersistenceDeviceTest {
    @get:Rule val sceneBackup=LabSceneBackup()
    @Test fun manualSceneParametersAndOffSurviveReopenAndReachCamera() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext.applicationContext as Application
        val file=File(app.filesDir,"beauty_resources/user-state.json");val previous=file.takeIf{it.exists()}?.readBytes()
        var store=ViewModelStore();lateinit var model:ResourceCenterModel
        fun main(action:()->Unit){inst.runOnMainSync(action);inst.waitForIdleSync()}
        fun ready():ResourceCenterModel.Ui {
            val end=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<end){var value:ResourceCenterModel.Ui?=null;main{value=model.ui.value}
                value?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);return it}};Thread.sleep(20)}
            error("Persisted settings did not load")
        }
        fun open(){main{model=ResourceCenterModel(app);store.put("settings",model)};ready()}
        fun reopen(){main{store.clear();store=ViewModelStore()};open()}
        try {
            open()
            val image=StickerImage(2,2,intArrayOf(0xff123456.toInt(),0xffabcdef.toInt(),0xff998877.toInt(),0xff102030.toInt()))
            val scene=BeautyScene(background=BackgroundMode.IMAGE,backgroundImage=image,blurRadius=9f,
                backgroundFocusX=.3f,backgroundFocusY=.7f,motion=CameraMotion.PAN,zoom=1.43f,periodMs=3500)
            main{model.scene(scene,true,true);model.parameters(mapOf("beauty.whitening" to .63f,"reshape.slimFace" to .42f));model.enabled(false)}
            // 不等待保存完成就退出；新页面的初始化必须排在已接受的配置写入之后。
            reopen()
            var ui=ready();var s=ui.settings!!
            assertFalse(ui.enabled);assertEquals(BackgroundMode.IMAGE,s.scene.background)
            assertArrayEquals(image.copyArgb(),s.scene.backgroundImage!!.copyArgb())
            assertEquals(.3f,s.scene.backgroundFocusX);assertEquals(.7f,s.scene.backgroundFocusY)
            assertEquals(CameraMotion.PAN,s.scene.motion);assertEquals(1.43f,s.scene.zoom);assertEquals(3500L,s.scene.periodMs)
            main{model.enabled(true)};s=ready().settings!!;assertEquals(.63f,s.whitening);assertEquals(.42f,s.adjustments.slimFace)
            val timeline=MotionTimeline(listOf(MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(1000,.6f,.4f,1.3f),MotionKeyframe(2000,.5f,.5f,1f)))
            main{model.scene(s.scene.copy(motion=CameraMotion.KEYFRAMES,timeline=timeline),false,true)}
            reopen();assertEquals(timeline.frames,ready().settings!!.scene.timeline!!.frames)
            main{model.disableCategory(ResourceCategory.BACKGROUND);model.disableCategory(ResourceCategory.MOTION)}
            reopen();s=ready().settings!!;assertEquals(BackgroundMode.NONE,s.scene.background);assertEquals(CameraMotion.NONE,s.scene.motion)
            // 资源选择应清除该类别的手动覆盖，而且关闭/重开后仍以新资源为准。
            val bg=ready().installed.first{it.descriptor.category==ResourceCategory.BACKGROUND}
            main{model.select(bg)};reopen();ui=ready();assertEquals(bg.hash,ui.selection[bg.descriptor.slot]);assertNotEquals(BackgroundMode.NONE,ui.settings!!.scene.background)
            main{store.clear()}
            val activity=inst.startActivitySync(Intent(app,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
            try {
                val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
                val end=System.nanoTime()+15_000_000_000L;var applied=false
                while(System.nanoTime()<end){main{applied=preview.options.effects.themeSettings?.scene?.background!=null&&preview.options.effects.themeSettings?.scene?.background!=BackgroundMode.NONE};if(applied)break;Thread.sleep(20)}
                assertTrue("Restored background must reach actual camera renderer",applied)
            } finally{main{activity.finish()}}
        } finally{main{store.clear()};if(previous==null)file.delete()else file.writeBytes(previous)}
    }
}
