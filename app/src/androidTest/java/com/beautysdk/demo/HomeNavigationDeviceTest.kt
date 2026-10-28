package com.beautysdk.demo

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 验证首页三个真实入口；截图仅首页，不截图或保存相机帧。 */
class HomeNavigationDeviceTest {
    @Test fun homeEntriesOpenCameraWorkshopAndDiagnostics() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val home=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            inst.waitForIdleSync()
            // 首页没有相机预览，可保留页面证据；相机页面只检查控件与状态。
            val screenshot=inst.uiAutomation.takeScreenshot()
            if(screenshot!=null) {
                File(inst.targetContext.cacheDir,"full-test-home.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                screenshot.recycle()
            }
            val resourceMonitor=inst.addMonitor(ResourceCenterActivity::class.java.name,null,false)
            try {
                inst.runOnMainSync {home.findViewById<View>(R.id.open_workshop).performClick()}
                val page=inst.waitForMonitorWithTimeout(resourceMonitor,8000) as? ResourceCenterActivity
                assertNotNull("Resources must open independent resource center",page)
                inst.runOnMainSync {assertNull(page!!.findViewById<View>(R.id.camera_gpu_preview));page.finish()}
                inst.waitForIdleSync()
            } finally {inst.removeMonitor(resourceMonitor)}
            for((entry,section) in listOf(R.id.open_camera to -1,R.id.open_diagnostics to 2)) {
                val monitor=inst.addMonitor(CameraTestActivity::class.java.name,null,false)
                var camera:CameraTestActivity?=null
                try {
                    inst.runOnMainSync { home.findViewById<View>(entry).performClick() }
                    camera=inst.waitForMonitorWithTimeout(monitor,8000) as? CameraTestActivity
                    assertNotNull("Home entry did not open camera host",camera)
                    inst.waitForIdleSync()
                    // 页面消费后会移除 initial_section；断言用户实际看见的入口选中状态。
                    if(section>=0) {
                        val selected=if(section==3) R.id.camera_workshop else R.id.camera_debug
                        val end=System.nanoTime()+3_000_000_000L
                        var shown=false
                        do {
                            inst.runOnMainSync { shown=camera!!.findViewById<View>(selected).isSelected }
                            if(!shown) Thread.sleep(20)
                        } while(!shown && System.nanoTime()<end)
                        assertTrue("Home entry did not open its target section",shown)
                    }
                    inst.runOnMainSync {
                        assertTrue(camera!!.findViewById<View>(R.id.camera_stage).isShown)
                    }
                } finally {
                    inst.runOnMainSync { camera?.finish() }
                    inst.waitForIdleSync();inst.removeMonitor(monitor)
                }
            }
        } finally { inst.runOnMainSync { home.finish() } }
    }
}
