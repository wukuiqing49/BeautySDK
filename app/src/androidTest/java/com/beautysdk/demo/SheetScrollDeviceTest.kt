package com.beautysdk.demo

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.*
import org.junit.Test

/** 从真实Window注入滑动，检查内容滚动且弹框位置不变，不操作素材或保存相机帧。 */
class SheetScrollDeviceTest {
    @Test fun beautyAndWorkshopScrollWithoutDraggingSheet() {
        val inst=InstrumentationRegistry.getInstrumentation()
        for(button in listOf(R.id.camera_settings,R.id.camera_stickers)) {
            val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
            lateinit var panel:ScrollView
            val location=IntArray(2)
            var x=0f;var top=0f;var bottom=0f;var originalTop=0;var hasOverflow=false
            try {
                inst.runOnMainSync {
                    panel=activity.findViewById(R.id.controls_scroll)
                    activity.findViewById<View>(button).performClick()
                }
                inst.waitForIdleSync()
                // 等待BottomSheet入场动画结束，横屏居中窗口的屏幕坐标才稳定。
                SystemClock.sleep(500)
                inst.runOnMainSync {
                    panel.scrollTo(0,0)
                    panel.getLocationOnScreen(location);originalTop=location[1]
                    val dp=activity.resources.displayMetrics.density
                    // 使用内边距空白处起手，避免误触滑杆、按钮或Spinner。
                    val visible=android.graphics.Rect(location[0],location[1],location[0]+panel.width,location[1]+panel.height)
                    val window=android.graphics.Rect();panel.getWindowVisibleDisplayFrame(window)
                    assertTrue(visible.intersect(window))
                    x=visible.right-8*dp
                    top=visible.top+visible.height()*.2f;bottom=visible.top+visible.height()*.8f
                    assertTrue(bottom>top)
                    assertFalse(BottomSheetBehavior.from((panel.parent as View).parent as View).isDraggable)
                    hasOverflow=panel.getChildAt(0).height>panel.height
                    // 分类后短面板允许完整显示；仅有溢出时断言可滚动。
                }
                fun swipe(from:Float,to:Float) {
                    val down=SystemClock.uptimeMillis()
                    fun send(action:Int,y:Float) {
                        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                        try { inst.sendPointerSync(event) } finally { event.recycle() }
                    }
                    send(MotionEvent.ACTION_DOWN,from)
                    for(i in 1..12) { SystemClock.sleep(12);send(MotionEvent.ACTION_MOVE,from+(to-from)*i/12) }
                    send(MotionEvent.ACTION_UP,to);inst.waitForIdleSync()
                }
                swipe(bottom,top)
                inst.runOnMainSync {
                    if(hasOverflow) assertTrue("Content must scroll for section $button",panel.scrollY>0)
                    else assertEquals(0,panel.scrollY) // 空工坊可完整容纳，不制造或修改用户素材强迫溢出。
                    panel.getLocationOnScreen(location);assertEquals(originalTop,location[1])
                    assertEquals(BottomSheetBehavior.STATE_EXPANDED,BottomSheetBehavior.from((panel.parent as View).parent as View).state)
                }
                swipe(top,bottom)
                inst.runOnMainSync {
                    panel.getLocationOnScreen(location);assertEquals(originalTop,location[1])
                    assertTrue(panel.isShown)
                }
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
