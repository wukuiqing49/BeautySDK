package com.beautysdk.demo

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 只读取回调次数；不采集或保存相机画面。 */
class PreviewCallbackSafetyDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()

    @Test fun failingObserverAndErrorHandlerDoNotCrashOrBlockOtherObservers() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val images=AtomicInteger();val errors=AtomicInteger();val scenes=AtomicInteger()
        val nextFrames=CountDownLatch(3)
        lateinit var preview:GpuBeautyPreview
        try {
            inst.runOnMainSync {
                preview=activity.findViewById(R.id.camera_gpu_preview)
                preview.onImage={images.incrementAndGet();throw IllegalStateException("Injected preview observer failure")}
                preview.onError={errors.incrementAndGet();throw IllegalStateException("Injected preview error handler failure")}
                preview.onSceneStatus={_,_->scenes.incrementAndGet();nextFrames.countDown()}
            }
            assertTrue("Other observers must keep receiving frames",nextFrames.await(20,TimeUnit.SECONDS))
            inst.waitForIdleSync()
            assertTrue(images.get()>=3)
            assertTrue(errors.get()>=1)
            assertTrue(scenes.get()>=3)
        } finally {
            inst.runOnMainSync {activity.finish()}
            // finish只是请求；等待真正销毁后再判断晚到回调，不能把关闭动画期间的帧当泄漏。
            val deadline=System.nanoTime()+10_000_000_000L
            var destroyed=false
            while(!destroyed && System.nanoTime()<deadline) {
                inst.runOnMainSync {destroyed=activity.isDestroyed}
                if(!destroyed)Thread.sleep(20)
            }
            assertTrue("Activity must finish destroying",destroyed)
            inst.waitForIdleSync()
        }
        val afterClose=scenes.get()
        Thread.sleep(250)
        assertEquals("No UI delivery after close",afterClose,scenes.get())
    }
}
