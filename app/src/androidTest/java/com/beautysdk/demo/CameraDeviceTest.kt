package com.beautysdk.demo

import android.content.Intent
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import android.widget.Spinner
import android.widget.CheckBox
import android.view.View
import androidx.camera.view.PreviewView
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.camera.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** 只在内存检查相机帧，不录制、不截图或保存设备人脸图像。由宿主测试命令预先授权 CAMERA。 */
class CameraDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun activity(): CameraTestActivity {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        instrumentation.runOnMainSync {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return activity
    }

    @Test fun frontBackFramesRestartAndStop() {
        val activity = activity()
        val frames = LinkedBlockingQueue<CameraFrame>(2)
        val errors = LinkedBlockingQueue<Exception>()
        val count = AtomicInteger()
        var stopped = CountDownLatch(1)
        lateinit var camera: CustomCameraController
        var created = false
        lateinit var preview: PreviewView
        try {
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.camera_stop).performClick()
                preview = PreviewView(activity)
                activity.setContentView(preview)
                camera = CustomCameraController(activity,object : CameraFrameListener {
                    override fun onFrame(frame: CameraFrame) {
                        count.incrementAndGet(); if (!frames.offer(frame)) { frames.poll(); frames.offer(frame) }
                    }
                    override fun onStreamStopped() { stopped.countDown() }
                },onError = { errors.offer(it) })
                created = true
                camera.start(activity,preview,CameraConfig(width=320,height=240,maxAnalysisFps=5))
            }
            fun next(lens: CameraLens, previousSession: Long = -1): CameraFrame {
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while (System.nanoTime() < deadline) {
                    errors.poll()?.let { throw AssertionError("Camera error",it) }
                    val frame = frames.poll(200,TimeUnit.MILLISECONDS) ?: continue
                    if (frame.lens == lens && frame.sessionId != previousSession) return frame
                }
                throw AssertionError("No frame for $lens; errors=$errors")
            }
            val front = next(CameraLens.FRONT)
            assertTrue(front.width>0 && front.height>0)
            assertTrue(front.mirrored)
            assertTrue(front.rotationDegrees in listOf(0,90,180,270))
            assertEquals(front.width*front.height*4,front.readOnlyPixels().remaining())
            assertEquals(255,front.readOnlyPixels().get(3).toInt() and 255)
            val saved = ByteArray(front.readOnlyPixels().remaining()).also { front.readOnlyPixels().get(it) }
            frames.clear()
            instrumentation.runOnMainSync { camera.switchCamera() }
            val back = next(CameraLens.BACK,front.sessionId)
            assertFalse(back.mirrored)
            assertFalse(camera.isCurrent(front))
            assertTrue(camera.isCurrent(back))
            val after = ByteArray(saved.size).also { front.readOnlyPixels().get(it) }
            assertArrayEquals(saved,after)
            stopped = CountDownLatch(1)
            instrumentation.runOnMainSync { camera.stop() }
            assertTrue(stopped.await(5,TimeUnit.SECONDS))
            val captured = count.get(); Thread.sleep(350)
            assertEquals(captured,count.get())
            assertFalse(camera.isCurrent(back))
            frames.clear()
            instrumentation.runOnMainSync { camera.start(activity,preview,CameraConfig(width=320,height=240,maxAnalysisFps=5)) }
            val restarted = next(CameraLens.FRONT,front.sessionId)
            assertNotEquals(back.sessionId,restarted.sessionId)
        } finally {
            instrumentation.runOnMainSync { if (created) camera.close(); activity.finish() }
        }
    }

    @Test fun cameraPageProducesProcessedImageAndStopClearsIt() {
        val savedFile=java.io.File(instrumentation.targetContext.filesDir,"beauty_resources/user-state.json")
        val saved=savedFile.takeIf{it.exists()}?.readBytes()
        val activity = activity()
        val analyzed = java.util.concurrent.atomic.AtomicBoolean()
        val analysisError = java.util.concurrent.atomic.AtomicReference<String?>()
        try {
            selectCameraTestSticker(activity)
            instrumentation.runOnMainSync {
                // 实际开启素材才能触发按需检测；只选款式不会运行模型。
                val gpu = activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
                val listener = gpu.onImage
                gpu.onImage = { stats ->
                    listener(stats)
                    if (stats.analysis.durationNs != null) analyzed.set(true)
                    stats.analysis.error?.let { analysisError.set(it) }
                }
                activity.findViewById<Spinner>(R.id.camera_backend).setSelection(1)
                activity.findViewById<Spinner>(R.id.camera_beauty_backend).setSelection(0)
                activity.findViewById<Spinner>(R.id.camera_glasses_style).setSelection(2)
                activity.findViewById<Spinner>(R.id.camera_head_style).setSelection(3)
                activity.findViewById<CheckBox>(R.id.camera_glasses).isChecked = true
                activity.findViewById<CheckBox>(R.id.camera_hat).isChecked = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.findViewById<View>(R.id.camera_start).performClick() }
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            var ready = false
            while (!ready && System.nanoTime()<deadline) {
                instrumentation.runOnMainSync {
                    ready=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).renderedFrames>2 && analyzed.get()
                }
                analysisError.get()?.let { throw AssertionError("Analysis failed: $it") }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Processed image missing",ready)
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.camera_stop).performClick()
                assertEquals(0L,activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).renderedFrames)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() };instrumentation.waitForIdleSync()
            if(saved==null)savedFile.delete()else savedFile.writeBytes(saved)
        }
    }
}
