package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.ceil

/** 固定公开测试人像的 CPU/GPU 对照；只在准备夹具时解码 Bitmap，实时路径仍为 DirectByteBuffer。
 * 不包含摄像头、渲染争用和运动，不能据此宣称直播性能达标。
 */
class MediaPipeDelegateDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    /** 真机相机 + 基础GL + GPU检测的启动冒烟；不要求测试者出镜，不作为跟随效果验收。 */
    @Test fun cameraGpuOptionProducesAnalysisWhileRendering() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val savedFile=java.io.File(instrumentation.targetContext.filesDir,"beauty_resources/user-state.json")
        val saved=savedFile.takeIf{it.exists()}?.readBytes()
        val activity = instrumentation.startActivitySync(android.content.Intent(
            instrumentation.targetContext,CameraTestActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val done = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Exception?>()
        try {
            selectCameraTestSticker(activity)
            instrumentation.runOnMainSync {
                val preview = activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                preview.onError = { error.set(it); done.countDown() }
                preview.onImage = { image ->
                    if (image.analysis.state == com.beautysdk.demo.feature.app.DemoTrackingState.READY ||
                        image.analysis.state == com.beautysdk.demo.feature.app.DemoTrackingState.NO_FACE) done.countDown()
                }
                activity.findViewById<android.widget.CheckBox>(R.id.camera_mediapipe_gpu).isChecked = true
                activity.findViewById<android.widget.CheckBox>(R.id.camera_glasses).isChecked = true
                assertTrue(preview.options.mediaPipeGpu)
            }
            assertTrue("GPU camera analysis timeout",done.await(30,java.util.concurrent.TimeUnit.SECONDS))
            assertNull(error.get()?.toString(),error.get())
        } finally {
            instrumentation.runOnMainSync { activity.finish() };instrumentation.waitForIdleSync()
            if(saved==null)savedFile.delete()else savedFile.writeBytes(saved)
        }
    }

    @Test fun compareCpuGpuOnSameDirectInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = instrumentation.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val fixture = Bitmap.createScaledBitmap(original,240,300,true)
        val pixels = IntArray(240*300)
        fixture.getPixels(pixels,0,240,0,0,240,300)
        val buffer = ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEach { buffer.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte()) }
        buffer.flip()
        if (fixture !== original) fixture.recycle()
        original.recycle()
        val cpu = MediaPipeFaceAnalyzer.Execution.CPU
        val gpu = MediaPipeFaceAnalyzer.Execution.GPU
        val centers = mutableMapOf<MediaPipeFaceAnalyzer.Execution,FacePoint>()
        // ABBA 顺序减小单向预热偏差；每轮重新创建，并单独记录初始化成本。
        listOf(cpu,gpu,gpu,cpu).forEachIndexed { round, execution ->
            val initialized = System.nanoTime()
            MediaPipeFaceAnalyzer(instrumentation.targetContext,execution=execution).use { analyzer ->
                val initMs = (System.nanoTime()-initialized)/1e6
                val total = mutableListOf<Double>()
                val inference = mutableListOf<Double>()
                val prep = mutableListOf<Double>()
                repeat(45) { index ->
                    val started = System.nanoTime()
                    val faces = analyzer.analyzeRgba(RgbaFrame(FrameInfo(240,300,(index+1)*33_333_333L),buffer,960))
                    val elapsed = (System.nanoTime()-started)/1e6
                    assertEquals("$execution frame $index",1,faces.size)
                    assertEquals(478,faces.single().landmarks.size)
                    assertTrue(faces.single().anchors.containsKey(FaceAnchor.UPPER_LIP))
                    centers[execution] = faces.single().anchors.getValue(FaceAnchor.NOSE)
                    if (index >= 5) {
                        total.add(elapsed); inference.add(analyzer.lastInferenceNs/1e6)
                        prep.add(analyzer.lastPreparationNs/1e6)
                    }
                }
                fun stats(values: List<Double>): String {
                    val ordered=values.sorted()
                    return "mean=${values.average()},p50=${ordered[ordered.size/2]},p95=${ordered[ceil(ordered.size*.95).toInt()-1]},max=${ordered.last()}"
                }
                instrumentation.sendStatus(0,android.os.Bundle().apply {
                    putString("stream","\nDELEGATE round=$round execution=$execution n=${total.size} initMs=$initMs total[${stats(total)}] infer[${stats(inference)}] prep[${stats(prep)}]\n")
                })
            }
        }
        val a=centers.getValue(cpu);val b=centers.getValue(gpu)
        assertTrue("CPU/GPU nose drift",kotlin.math.abs(a.x-b.x)<.03f && kotlin.math.abs(a.y-b.y)<.03f)
    }
}
