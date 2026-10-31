package com.beautysdk.demo

import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.demo.feature.app.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class MakeupCameraDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    @Test fun makeupUsesGpuAndStrengthChangesKeepAnalysisGeneration() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
        val stats=AtomicReference<GpuDemoStats?>();val failure=AtomicReference<Exception?>()
        fun field(name:String)=GpuBeautyPreview::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun await(message:String,check:()->Boolean) {
            val end=System.nanoTime()+20_000_000_000L
            while(System.nanoTime()<end){failure.get()?.let{throw it};var ready=false;inst.runOnMainSync{ready=check()};if(ready)return;Thread.sleep(40)}
            fail(message)
        }
        try {
            inst.runOnMainSync{
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val old=preview.onImage;preview.onImage={stats.set(it);old(it)}
                val oldError=preview.onError;preview.onError={failure.set(it);oldError(it)}
            }
            await("Camera resources must finish initial load") {ViewModelProvider(activity)[ResourceCenterModel::class.java].ui.value?.let{!it.busy&&it.settings!=null}==true&&preview.renderedFrames>4}
            inst.runOnMainSync {
                stats.set(null)
                preview.showOriginal=false
                preview.options=preview.options.copy(backend=DemoBackend.MEDIAPIPE,beautyBackend=DemoBeautyBackend.GPUPIXEL,sticker3D=null,
                    mediaPipeGpu=false,frameTracking=true,observeFaces=true,faceAnalysisEnabled=true,gestureEnabled=false,
                    effects=preview.options.effects.copy(themeSettings=BeautySettings(adjustments=BeautyAdjustments(lipstick=.2f))))
            }
            await("Automatic makeup GPU must produce valid observations"){
                field("analysisExecution").get(preview)=="GPU"&&stats.get()?.analysis?.state in listOf(DemoTrackingState.READY,DemoTrackingState.NO_FACE)
            }
            val runtime=field("analysis").get(preview) as BeautyAnalysisRuntime
            val generation=runtime.generationToken();val frames=preview.renderedFrames
            inst.runOnMainSync {
                repeat(20){i->preview.options=preview.options.copy(effects=preview.options.effects.copy(themeSettings=BeautySettings(adjustments=BeautyAdjustments(lipstick=.2f+i*.03f))))}
                assertEquals("Positive lipstick strength changes must not reset analysis",generation,runtime.generationToken())
            }
            await("Camera must keep rendering after rapid makeup edits"){preview.renderedFrames>=frames+5}
            assertEquals(generation,runtime.generationToken());assertEquals("GPU",field("analysisExecution").get(preview))
        } finally {inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
}
