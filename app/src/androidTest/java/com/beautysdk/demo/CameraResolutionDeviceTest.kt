package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.beautysdk.camera.CameraLens
import com.beautysdk.demo.feature.app.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** 仅记录实际尺寸/方向/计数，不采集或保存相机画面。 */
class CameraResolutionDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    @get:org.junit.Rule val configuration=UiPreviewConfigurationRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun launch():CameraTestActivity {
        // ColorOS相机退出后可能回到竖屏锁屏；冷启动测试的每次重开都须重新建立可见/旋转前置条件。
        for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        }
        InstrumentationRegistry.getArguments().getString("uiRotation")?.toInt()?.let {check(inst.uiAutomation.setRotation(it))}
        return inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
    }
    private fun menu(activity:CameraTestActivity):PopupMenu {
        activity.findViewById<View>(R.id.camera_more).performClick()
        return CameraTestActivity::class.java.getDeclaredField("moreMenu").apply {isAccessible=true}.get(activity) as PopupMenu
    }
    private fun select(activity:CameraTestActivity,value:DemoCaptureResolution) {
        inst.runOnMainSync {
            val popup=menu(activity)
            val submenu=checkNotNull(popup.menu.findItem(R.id.camera_resolution).subMenu)
            assertEquals(3,submenu.size())
            val id=when(value) {DemoCaptureResolution.SD->R.id.camera_resolution_sd
                DemoCaptureResolution.HD->R.id.camera_resolution_hd
                DemoCaptureResolution.FULL_HD->R.id.camera_resolution_full_hd}
            assertTrue(submenu.performIdentifierAction(id,0));popup.dismiss()
        }
    }
    private fun observe(activity:CameraTestActivity):AtomicReference<GpuDemoStats?> {
        val latest=AtomicReference<GpuDemoStats?>()
        inst.runOnMainSync {
            val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
            val previous=preview.onImage
            preview.onImage={previous(it);latest.set(it)}
        }
        return latest
    }
    private fun await(latest:AtomicReference<GpuDemoStats?>,resolution:DemoCaptureResolution,lens:CameraLens):GpuDemoStats {
        val deadline=System.nanoTime()+25_000_000_000L
        while(System.nanoTime()<deadline) {
            val value=latest.get()
            if(value!=null && value.lens==lens && value.requestedWidth==resolution.width &&
                value.requestedHeight==resolution.height && value.width>0 && value.height>0 &&
                value.analysisWidth>0 && value.analysisHeight>0 && value.renderedFrames>=3) {
                assertTrue("HD output must not enlarge the fixture device analysis stream to 720p",
                    maxOf(value.analysisWidth,value.analysisHeight)<=640)
                assertTrue("Analysis and output must share the cropped aspect ratio",
                    kotlin.math.abs(value.width.toDouble()/value.height-value.analysisWidth.toDouble()/value.analysisHeight)<.025)
                android.util.Log.i("BeautyResolution",org.json.JSONObject().put("requested", "${resolution.width}x${resolution.height}")
                    .put("actual","${value.width}x${value.height}").put("analysis","${value.analysisWidth}x${value.analysisHeight}")
                    .put("lens",lens.name).put("renderedFrames",value.renderedFrames).toString())
                return value
            }
            Thread.sleep(30)
        }
        fail("No current resolution frame: $resolution/$lens; latest=${latest.get()}")
        error("unreachable")
    }
    private fun finish(activity:CameraTestActivity) {
        inst.runOnMainSync {activity.finish()}
        val deadline=System.nanoTime()+10_000_000_000L
        while(System.nanoTime()<deadline) {
            var destroyed=false;inst.runOnMainSync {destroyed=activity.isDestroyed}
            if(destroyed)return
            Thread.sleep(30)
        }
        fail("Activity did not destroy")
    }

    @Test fun presetsSwitchRealFramesBothLensesAndCanReturnToSd() {
        val activity=launch()
        try {
            val latest=observe(activity)
            val sizes=mutableSetOf<Pair<Int,Int>>()
            for(lens in listOf(CameraLens.FRONT,CameraLens.BACK)) {
                if(lens==CameraLens.BACK) inst.runOnMainSync {activity.findViewById<View>(R.id.camera_switch).performClick()}
                for(resolution in listOf(DemoCaptureResolution.SD,DemoCaptureResolution.HD,DemoCaptureResolution.FULL_HD,DemoCaptureResolution.SD)) {
                    select(activity,resolution)
                    val frame=await(latest,resolution,lens);sizes.add(frame.width to frame.height)
                    inst.runOnMainSync {
                        val label=activity.findViewById<TextView>(R.id.camera_resolution)
                        assertTrue(label.text.contains("${frame.width}×${frame.height}"))
                        assertTrue(label.text.contains("${frame.analysisWidth}×${frame.analysisHeight}"))
                        assertTrue(label.isShown)
                    }
                }
            }
            assertTrue("At least two actual negotiated sizes on this fixture device",sizes.size>=2)
            // 不等待中间配置出帧，验证连续重绑只交付最新代次。
            select(activity,DemoCaptureResolution.HD);select(activity,DemoCaptureResolution.FULL_HD);select(activity,DemoCaptureResolution.SD)
            await(latest,DemoCaptureResolution.SD,CameraLens.BACK)
            inst.runOnMainSync {activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).showOriginal=true}
            val pauseDeadline=System.nanoTime()+10_000_000_000L
            while(System.nanoTime()<pauseDeadline && latest.get()?.analysisWidth!=0)Thread.sleep(30)
            assertEquals("Original mode must not display an active analysis stream",0,latest.get()!!.analysisWidth)
        } finally {finish(activity)}
    }

    @Test fun selectedResolutionSurvivesRecreationAndMenuRemainsChecked() {
        var activity=launch()
        try {
            val latest=observe(activity)
            select(activity,DemoCaptureResolution.HD);await(latest,DemoCaptureResolution.HD,CameraLens.FRONT)
            val old=activity
            inst.runOnMainSync {old.recreate()}
            val deadline=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<deadline) {
                var found:CameraTestActivity?=null
                inst.runOnMainSync {found=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<CameraTestActivity>().firstOrNull {it!==old}}
                if(found!=null) {activity=found!!;break};Thread.sleep(30)
            }
            assertNotSame("Activity must recreate",old,activity)
            await(observe(activity),DemoCaptureResolution.HD,CameraLens.FRONT)
            inst.runOnMainSync {
                val popup=menu(activity)
                assertTrue(popup.menu.findItem(R.id.camera_resolution).subMenu!!.findItem(R.id.camera_resolution_hd).isChecked)
                popup.dismiss()
                val label=activity.findViewById<TextView>(R.id.camera_resolution)
                for(i in 0 until label.layout.lineCount)assertEquals(0,label.layout.getEllipsisCount(i))
            }
        } finally {finish(activity)}
    }

    @Test fun selectionPersistsAcrossNewSessionAndDiagnosticsCanBeRead() {
        var activity=launch()
        try {
            select(activity,DemoCaptureResolution.HD)
            await(observe(activity),DemoCaptureResolution.HD,CameraLens.FRONT)
            finish(activity)
            activity=launch()
            await(observe(activity),DemoCaptureResolution.HD,CameraLens.FRONT)
            inst.runOnMainSync {
                InstrumentationRegistry.getArguments().getString("uiExpectedFont")?.let {
                    assertEquals(it.toFloat(),activity.resources.configuration.fontScale,0f)
                }
                InstrumentationRegistry.getArguments().getString("uiRotation")?.let {
                    assertEquals(if(it=="1")android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT,activity.resources.configuration.orientation)
                }
                val scroll=activity.findViewById<android.widget.ScrollView>(R.id.camera_diagnostics_scroll)
                scroll.isSmoothScrollingEnabled=false
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            inst.waitForIdleSync();Thread.sleep(300)
            inst.runOnMainSync {
                val scroll=activity.findViewById<View>(R.id.camera_diagnostics_scroll)
                val label=activity.findViewById<TextView>(R.id.camera_resolution)
                val stage=activity.findViewById<View>(R.id.camera_stage)
                val viewport=android.graphics.Rect();val visible=android.graphics.Rect()
                assertTrue(scroll.getGlobalVisibleRect(viewport));assertTrue(stage.getGlobalVisibleRect(visible))
                assertTrue("Diagnostic viewport must fit camera stage",visible.contains(viewport))
                assertTrue(label.getGlobalVisibleRect(visible))
                val position=IntArray(2);label.getLocationOnScreen(position)
                val lastLine=label.layout.lineCount-1
                val lastTop=position[1]+label.totalPaddingTop+label.layout.getLineTop(lastLine)
                val lastBottom=position[1]+label.totalPaddingTop+label.layout.getLineBottom(lastLine)
                assertTrue("Final resolution line must be reachable",lastTop>=viewport.top && lastBottom<=viewport.bottom)
                assertEquals("All text must be laid out",label.text.length,label.layout.getLineEnd(label.layout.lineCount-1))
                for(i in 0 until label.layout.lineCount)assertEquals(0,label.layout.getEllipsisCount(i))
                val popup=menu(activity)
                assertTrue(popup.menu.findItem(R.id.camera_resolution).subMenu!!.findItem(R.id.camera_resolution_hd).isChecked)
                popup.dismiss()
            }
        } finally {finish(activity)}
    }
}
