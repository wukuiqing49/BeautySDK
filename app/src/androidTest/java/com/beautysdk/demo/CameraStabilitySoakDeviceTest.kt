package com.beautysdk.demo

import android.content.Intent
import android.os.Debug
import android.os.PowerManager
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.demo.feature.app.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 可重复运行三组稳定性测量；只保存数值，不保存相机图像。CPU提交不冒充GPU耗时。 */
class CameraStabilitySoakDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    @Test fun compareOriginalDetectionAndBeauty() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val arguments=InstrumentationRegistry.getArguments()
        val seconds=(arguments.getString("soakSeconds")?.toIntOrNull()?:30).coerceIn(5,7200)
        val qualification=arguments.getString("qualification")=="true"
        require(!qualification||seconds>=3600){"Qualification requires at least 3600 seconds per mode"}
        val minimumFps=arguments.getString("minimumPreviewFps")?.toDoubleOrNull()
        val maximumThermal=arguments.getString("maximumThermalStatus")?.toIntOrNull()
        val maximumGrowth=arguments.getString("maximumPssGrowthKiB")?.toLongOrNull()
        require(!qualification||(minimumFps!=null&&minimumFps.isFinite()&&minimumFps>0&&maximumThermal!=null&&maximumThermal in 0..6&&maximumGrowth!=null&&maximumGrowth>=0)){"Qualification requires explicit FPS, thermal and memory budgets"}
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var preview:GpuBeautyPreview;lateinit var model:ResourceCenterModel
        val latest=AtomicReference<GpuDemoStats>();val failure=AtomicReference<Exception?>()
        val rows=JSONArray();val power=context.getSystemService(PowerManager::class.java)
        try {
            inst.runOnMainSync{activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);preview=activity.findViewById(R.id.camera_gpu_preview);model=ViewModelProvider(activity)[ResourceCenterModel::class.java]}
            val timeout=System.nanoTime()+20_000_000_000L
            while(true){var ready=false;inst.runOnMainSync{ready=model.ui.value?.settings!=null&&model.ui.value?.busy==false};if(ready&&preview.renderedFrames>5)break;check(System.nanoTime()<timeout){"Camera startup timeout"};Thread.sleep(50)}
            inst.runOnMainSync{val old=preview.onImage;preview.onImage={latest.set(it);old(it)};val oldError=preview.onError;preview.onError={failure.set(it);oldError(it)}}
            for(mode in listOf("original","face_and_gesture","face_and_gesture_beauty")) {
                inst.runOnMainSync {
                    preview.showOriginal=mode=="original"
                    val settings=if(mode.endsWith("beauty"))BeautySettings(smoothing=.35f,whitening=.2f,adjustments=BeautyAdjustments(slimFace=.15f,lipstick=.2f))else BeautySettings()
                    preview.options=preview.options.copy(backend=DemoBackend.MEDIAPIPE,beautyBackend=DemoBeautyBackend.GPUPIXEL,gestureEnabled=mode!="original",observeFaces=mode!="original",faceAnalysisEnabled=true,frameTracking=false,effects=preview.options.effects.copy(themeSettings=settings))
                    preview.setGestureEffects(null)
                }
                Thread.sleep(2000)
                latest.set(null)
                val frames=preview.renderedFrames;val start=System.nanoTime();val pss=Debug.getPss();val thermalStart=power.currentThermalStatus
                val cpu=mutableListOf<Long>();var maxPss=pss;var detectedSamples=0
                var peakThermal=thermalStart;var previousFrames=frames;var stalledIntervals=0;var consecutiveStalls=0;var maxConsecutiveStalls=0
                val memorySamples=JSONArray();val frameDeltas=JSONArray()
                repeat(seconds){
                    Thread.sleep(1000);failure.get()?.let{throw it};assertFalse(activity.isFinishing)
                    val memory=Debug.getPss();maxPss=maxOf(maxPss,memory);memorySamples.put(memory)
                    peakThermal=maxOf(peakThermal,power.currentThermalStatus)
                    val nextFrames=preview.renderedFrames;val delta=nextFrames-previousFrames;previousFrames=nextFrames;frameDeltas.put(delta)
                    if(delta==0L){stalledIntervals++;consecutiveStalls++}else consecutiveStalls=0
                    maxConsecutiveStalls=maxOf(maxConsecutiveStalls,consecutiveStalls)
                    latest.getAndSet(null)?.let{s->if(s.faceCount>0)detectedSamples++;if(mode!="original")s.performance?.let{cpu+=it.cpuSubmissionNs}}
                }
                val elapsed=(System.nanoTime()-start)/1e9;val count=preview.renderedFrames-frames
                assertTrue("Camera stalled in $mode",count>0)
                val sorted=cpu.sorted();val row=JSONObject().put("mode",mode).put("seconds",elapsed).put("previewFrames",count).put("previewFps",count/elapsed).put("startPssKiB",pss).put("maxPssKiB",maxPss).put("endPssKiB",Debug.getPss()).put("thermalStart",thermalStart).put("thermalEnd",power.currentThermalStatus).put("facePresentSamples",detectedSamples).put("cpuSampleCount",cpu.size)
                if(sorted.isNotEmpty())row.put("cpuSubmissionP50Ms",sorted[sorted.size/2]/1e6).put("cpuSubmissionP95Ms",sorted[((sorted.size-1)*.95).toInt()]/1e6)
                rows.put(row)
                row.put("samplingIntervals",seconds).put("faceCoverage",detectedSamples.toDouble()/seconds)
                row.put("peakThermalStatus",peakThermal).put("pssSamplesKiB",memorySamples).put("previewFrameDeltas",frameDeltas).put("stalledIntervals",stalledIntervals).put("maxConsecutiveStalledIntervals",maxConsecutiveStalls)
                if(qualification){
                    assertTrue("Preview FPS below explicit budget",count/elapsed>=requireNotNull(minimumFps))
                    assertTrue("Thermal budget exceeded",peakThermal<=requireNotNull(maximumThermal))
                    assertTrue("PSS growth budget exceeded",row.getLong("endPssKiB")-pss<=requireNotNull(maximumGrowth))
                    assertTrue("Preview stalled for multiple sample intervals",maxConsecutiveStalls<2)
                }
                if(qualification&&mode!="original")assertTrue("Insufficient fresh face observations; human-dependent load is not qualified",detectedSamples.toDouble()/seconds>=.8)
            }
        }finally{
            File(context.cacheDir,"beauty-soak.json").writeText(JSONObject().put("measurementVersion",2).put("qualificationRequested",qualification).put("deviceModel",android.os.Build.MODEL).put("apiLevel",android.os.Build.VERSION.SDK_INT).put("results",rows).put("limits","Sampled CPU submission, not GPU/remote latency; uncontrolled camera scene; no image saved. Passing face coverage does not qualify visual quality or overall performance.").toString(2))
            inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()
        }
    }
}
