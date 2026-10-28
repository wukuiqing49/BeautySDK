package com.beautysdk.demo

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** 不启动相机；验证操作区滚动时主预览保持在屏内，并明确切换原图/合成图。 */
class DemoLayoutDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun diagnosticsKeepBoundsAndMoreControlsDefaultDetection() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val before=Rect()
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val gpu=activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                assertTrue(gpu.options.observeFaces);assertTrue(gpu.options.faceAnalysisEnabled);assertTrue(gpu.options.gestureEnabled)
                val card=activity.findViewById<View>(R.id.camera_diagnostics)
                assertTrue(card.getGlobalVisibleRect(before))
                activity.findViewById<android.widget.TextView>(R.id.camera_hand_summary).text=activity.getString(R.string.camera_hand_value,"A very long gesture classification")
                activity.findViewById<android.widget.TextView>(R.id.camera_performance).text=activity.getString(R.string.camera_performance_compact,123.4,9999L)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val after=Rect();assertTrue(activity.findViewById<View>(R.id.camera_diagnostics).getGlobalVisibleRect(after));assertEquals(before,after)
                val gpu=activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                fun toggle(id:Int,checked:Boolean) {
                    activity.findViewById<View>(R.id.camera_more).performClick()
                    val field=CameraTestActivity::class.java.getDeclaredField("moreMenu").apply{isAccessible=true}
                    val popup=field.get(activity) as androidx.appcompat.widget.PopupMenu
                    assertEquals(checked,popup.menu.findItem(id).isChecked)
                    assertTrue(popup.menu.performIdentifierAction(id,0));popup.dismiss()
                }
                toggle(R.id.camera_face_summary,true);assertFalse(gpu.options.faceAnalysisEnabled);assertTrue(gpu.options.gestureEnabled)
                toggle(R.id.camera_face_summary,false);assertTrue(gpu.options.faceAnalysisEnabled)
                toggle(R.id.camera_gesture_enabled,true);assertFalse(gpu.options.gestureEnabled);assertTrue(gpu.options.faceAnalysisEnabled)
                toggle(R.id.camera_gesture_enabled,false);assertTrue(gpu.options.gestureEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun originalHoldReleasesAndDiagnosticsStayBelowButton() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val button=activity.findViewById<View>(R.id.camera_compare)
                val gpu=activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                fun touch(action:Int) {
                    val now=android.os.SystemClock.uptimeMillis()
                    val event=android.view.MotionEvent.obtain(now,now,action,button.width/2f,button.height/2f,0)
                    try { button.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN);assertTrue(gpu.showOriginal)
                touch(android.view.MotionEvent.ACTION_UP);assertFalse(gpu.showOriginal)
                touch(android.view.MotionEvent.ACTION_DOWN);assertTrue(gpu.showOriginal)
                touch(android.view.MotionEvent.ACTION_CANCEL);assertFalse(gpu.showOriginal)
                val bounds=Rect();assertTrue(button.getGlobalVisibleRect(bounds))
                for(id in listOf(R.id.camera_face_summary,R.id.camera_hand_summary,R.id.camera_performance)) {
                    val info=Rect();assertTrue(activity.findViewById<View>(id).getGlobalVisibleRect(info))
                    assertTrue(info.top>=bounds.bottom)
                }
                val reset=activity.findViewById<View>(R.id.camera_reset_all)
                assertTrue(reset.isShown);reset.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val field=CameraTestActivity::class.java.getDeclaredField("resetDialog").apply{isAccessible=true}
                val dialog=field.get(activity) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(activity.findViewById<CheckBox>(R.id.camera_glasses).isChecked)
                assertFalse(activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview).showOriginal)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun cameraControlsScrollWithoutHidingProcessedPreview() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        try {
            instrumentation.waitForIdleSync()
            val before=Rect()
            instrumentation.runOnMainSync { enableOriginalInset(activity) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val preview=activity.findViewById<View>(R.id.camera_processed)
                assertTrue(preview.getGlobalVisibleRect(before))
                assertEquals(preview.height,before.height())
                assertTrue(preview.height>100)
                activity.findViewById<View>(R.id.camera_settings).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync() 
            // 返回动画结束后，设置视图才重新挂回页面。
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            var restored = false
            while (!restored && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { restored = activity.findViewById<View>(R.id.controls_scroll) != null }
                if (!restored) Thread.sleep(30)
            }

            instrumentation.runOnMainSync {
                assertEquals(View.GONE,activity.findViewById<View>(R.id.controls_scroll).visibility)
                val preview=activity.findViewById<View>(R.id.camera_processed)
                val after=Rect(); assertTrue(preview.getGlobalVisibleRect(after))
                assertEquals(before,after)
                val tracking=activity.findViewById<View>(R.id.camera_face_summary)
                val bounds=Rect(); assertTrue(tracking.getGlobalVisibleRect(bounds))
                assertEquals(tracking.height,bounds.height())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun stickerCardsKeepSelectionAndPreviewsSwap() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        try {
            instrumentation.waitForIdleSync()
            lateinit var close: View
            lateinit var grid: android.widget.GridLayout
            lateinit var glasses: CheckBox
            instrumentation.runOnMainSync {
                enableOriginalInset(activity)
                close = activity.findViewById(R.id.camera_settings_close)
                grid = activity.findViewById(R.id.camera_sticker_grid)
                glasses = activity.findViewById(R.id.camera_glasses)
                assertEquals(6,grid.childCount)
                // 相机选择会持久化，不能假设其他用例或用户尚未启用贴纸。
                glasses.isChecked=false
                assertFalse(glasses.isChecked)
                activity.findViewById<View>(R.id.camera_stickers).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                grid.getChildAt(0).performClick()
                assertTrue(glasses.isChecked)
                assertTrue(grid.getChildAt(0).isSelected)
                grid.getChildAt(1).performClick()
                assertFalse(grid.getChildAt(0).isSelected)
                assertTrue(grid.getChildAt(1).isSelected)
                close.performClick()
            }
            val deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            var closed = false
            while (!closed && System.nanoTime()<deadline) {
                instrumentation.runOnMainSync { closed = activity.findViewById<View>(R.id.controls_scroll)!=null }
                if (!closed) Thread.sleep(30)
            }
            instrumentation.runOnMainSync {
                assertTrue(glasses.isChecked)
                val result = activity.findViewById<View>(R.id.camera_processed)
                val small = activity.findViewById<View>(R.id.camera_effect_container)
                val stage = activity.findViewById<View>(R.id.camera_stage)
                val gpu = activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                assertTrue(gpu.effectIsMain)
                small.performClick()
                assertFalse(gpu.effectIsMain)
                small.performClick()
                assertTrue(gpu.effectIsMain)
                activity.findViewById<View>(R.id.camera_stop).performClick()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun imageControlsScrollWithoutHidingProcessedPreview() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,ImageLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ImageLabActivity
        try {
            instrumentation.waitForIdleSync()
            val before=Rect()
            instrumentation.runOnMainSync {
                val preview=activity.findViewById<View>(R.id.processed_image)
                assertTrue(preview.getGlobalVisibleRect(before)); assertEquals(preview.height,before.height())
                assertTrue(preview.height>100)
                activity.findViewById<ScrollView>(R.id.controls_scroll).fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val preview=activity.findViewById<View>(R.id.processed_image)
                val after=Rect(); assertTrue(preview.getGlobalVisibleRect(after)); assertEquals(before,after)
                activity.findViewById<CheckBox>(R.id.show_original).isChecked=true
                assertEquals(View.INVISIBLE,preview.visibility)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}

/** 经真实菜单项启用可选小窗，测试不再假设默认显示双画面。须在主线程调用。 */
internal fun enableOriginalInset(activity:CameraTestActivity) {
    if(activity.findViewById<View>(R.id.camera_effect_container).visibility==View.VISIBLE)return
    activity.findViewById<View>(R.id.camera_more).performClick()
    val popup=CameraTestActivity::class.java.getDeclaredField("moreMenu").apply{isAccessible=true}.get(activity) as androidx.appcompat.widget.PopupMenu
    assertTrue(popup.menu.performIdentifierAction(R.id.camera_effect_container,0));popup.dismiss()
}
