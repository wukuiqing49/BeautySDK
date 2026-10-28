package com.beautysdk.demo

import android.content.Intent
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.camera.CameraLens
import com.beautysdk.camera.CameraStatus
import com.beautysdk.demo.feature.app.*
import org.junit.Assert.*
import org.junit.Test

/** 注入确定的回调数据验证页面展示，不把模拟事件当成真人识别结果。 */
class CallbackDisplayDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun withStoppedCamera(block: (CameraTestActivity, GpuBeautyPreview) -> Unit) {
        val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
        try {
            inst.runOnMainSync {preview.stop()};inst.waitForIdleSync()
            inst.runOnMainSync {block(activity,preview)}
        } finally {inst.runOnMainSync {activity.finish()};inst.waitForIdleSync()}
    }

    @Test fun faceTransitionsDoNotRepeatOrTreatStaleAsLeave() = withStoppedCamera { a,p ->
        val label=a.findViewById<TextView>(R.id.camera_tracking)
        fun send(state:DemoTrackingState,count:Int=0) = p.onImage(GpuDemoStats(640,480,CameraLens.FRONT,
            DemoBackend.MEDIAPIPE,count,0,1,DemoFaceStatus(state,0,1,0,null),1))
        fun contains(id:Int,number:Int) = assertTrue(label.text.toString().contains(a.getString(id,number)))
        send(DemoTrackingState.NO_FACE)
        assertTrue(label.text.contains(a.getString(R.string.camera_callback_face_wait)))
        send(DemoTrackingState.READY,1);contains(R.string.camera_callback_face_enter,1)
        repeat(4){send(DemoTrackingState.READY,1)};contains(R.string.camera_callback_face_enter,1)
        send(DemoTrackingState.STALE);contains(R.string.camera_callback_face_enter,1)
        send(DemoTrackingState.ERROR);contains(R.string.camera_callback_face_enter,1)
        send(DemoTrackingState.READY,1);contains(R.string.camera_callback_face_enter,1)
        send(DemoTrackingState.NO_FACE);contains(R.string.camera_callback_face_leave,2)
        repeat(4){send(DemoTrackingState.NO_FACE)};contains(R.string.camera_callback_face_leave,2)
        send(DemoTrackingState.READY,1);contains(R.string.camera_callback_face_enter,3)
        send(DemoTrackingState.DISABLED)
        assertTrue(label.text.contains(a.getString(R.string.camera_callback_face_disabled)))
        p.onStatus(CameraStatus.STARTING)
        send(DemoTrackingState.READY,1);contains(R.string.camera_callback_face_enter,1)
    }

    @Test fun gestureCallbackStaysVisibleUntilNextEventAndResetsWithSession() = withStoppedCamera { a,p ->
        val label=a.findViewById<TextView>(R.id.camera_gesture_status)
        val enabled=a.findViewById<CheckBox>(R.id.camera_gesture_enabled)
        assertFalse(label.isShown);assertFalse(enabled.isShown)
        a.findViewById<android.view.View>(R.id.camera_hand_summary).performClick()
        assertTrue(label.isShown);assertTrue(enabled.isShown)
        val names=a.resources.getStringArray(R.array.gesture_names)
        val phases=a.resources.getStringArray(R.array.gesture_phases)
        fun event(id:Long,kind:GestureKind,phase:GestureEventPhase) = GestureEvent(id,kind,phase,id,
            listOf(1),FacePoint(.5f,.5f),GestureSource.MODEL)
        fun expected(id:Long,kind:GestureKind,phase:GestureEventPhase)=a.getString(
            R.string.camera_callback_gesture_event,names[kind.ordinal],phases[phase.ordinal],id)
        enabled.isChecked=true
        p.onGestures(GestureSnapshot(GestureAnalysisState.READY),listOf(event(1,GestureKind.VICTORY,GestureEventPhase.TRIGGER)))
        val first=expected(1,GestureKind.VICTORY,GestureEventPhase.TRIGGER)
        assertTrue(label.text.contains(first))
        p.onGestures(GestureSnapshot(GestureAnalysisState.NO_HANDS),emptyList())
        assertTrue(label.text.contains(first))
        p.onGestures(GestureSnapshot(GestureAnalysisState.READY),listOf(event(2,GestureKind.PINCH,GestureEventPhase.START),event(3,GestureKind.PINCH,GestureEventPhase.UPDATE)))
        assertTrue(label.text.contains(expected(3,GestureKind.PINCH,GestureEventPhase.UPDATE)))
        p.onGestures(GestureSnapshot(GestureAnalysisState.STALE),listOf(event(1,GestureKind.VICTORY,GestureEventPhase.TRIGGER)))
        assertTrue(label.text.contains(expected(3,GestureKind.PINCH,GestureEventPhase.UPDATE)))
        enabled.isChecked=false
        assertFalse(label.text.contains(first))
        p.onStatus(CameraStatus.STARTING)
        p.onGestures(GestureSnapshot(GestureAnalysisState.READY),listOf(event(1,GestureKind.VICTORY,GestureEventPhase.TRIGGER)))
        assertTrue(label.text.contains(first))
    }
}
