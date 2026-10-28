package com.beautysdk.demo.feature.app

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test

class PreviewTrackingTest {
    private val source=FrameInfo(320,240,1_000_000_000,90,true)
    private val face=FaceObservation(FaceBounds(.1f,.2f,.8f,.9f),mapOf(FaceAnchor.NOSE to FacePoint(.3f,.4f)))
    private val result=PreviewDetection(7,DemoBackend.MEDIAPIPE,source,listOf(face),10_000_000)
    private fun PreviewDetection.snapshot(info: FrameInfo, epoch: Long, backend: DemoBackend): FaceAnalysisSnapshot {
        val history = PreviewDetectionHistory()
        history.publish(this, 1)
        return history.snapshot(info, epoch, backend, 1)
    }
    private fun output(time: Long=source.timestampNs+50_000_000)=FrameInfo(640,480,time,90,true)
    @Test fun sameViewportCanUseNormalizedAnchorsAcrossResolutionsWithinDeadline() {
        val snapshot=result.snapshot(output(source.timestampNs+PreviewDetection.MAX_AGE_NS),7,DemoBackend.MEDIAPIPE)
        assertEquals(FaceAnalysisState.READY,snapshot.state)
        val detected = checkNotNull(snapshot.result)
        assertSame(face,detected.faces.single())
        assertEquals(PreviewDetection.MAX_AGE_NS,snapshot.resultAgeNs)
        assertEquals(FacePoint(.3f,.4f),detected.faces.single().anchors[FaceAnchor.NOSE])
    }
    @Test fun staleFutureWrongSessionBackendAndGeometryCannotRenderStickers() {
        val stale=result.snapshot(output(source.timestampNs+PreviewDetection.MAX_AGE_NS+1),7,DemoBackend.MEDIAPIPE)
        assertEquals(FaceAnalysisState.STALE,stale.state); assertNull(stale.result)
        val invalid=listOf(
            result.snapshot(output(source.timestampNs-1),7,DemoBackend.MEDIAPIPE),
            result.snapshot(output(),8,DemoBackend.MEDIAPIPE),
            result.snapshot(output().copy(rotationDegrees=270),7,DemoBackend.MEDIAPIPE),
            result.snapshot(output().copy(mirrored=false),7,DemoBackend.MEDIAPIPE),
            result.snapshot(output().copy(width=1280,height=720),7,DemoBackend.MEDIAPIPE))
        invalid.forEach { assertEquals(FaceAnalysisState.PROCESSING,it.state); assertNull(it.result) }
    }
    @Test fun noFaceErrorsAndDisabledNeverReusePreviousFace() {
        val noFace=result.copy(faces=emptyList()).snapshot(output(),7,DemoBackend.MEDIAPIPE)
        assertEquals(FaceAnalysisState.NO_FACE,noFace.state); assertTrue(noFace.result!!.faces.isEmpty())
        val failed=result.copy(error="fixture error").snapshot(output(),7,DemoBackend.MEDIAPIPE)
        assertEquals(FaceAnalysisState.ERROR,failed.state); assertNull(failed.result)
        assertEquals("fixture error",failed.error)
        assertEquals(FaceAnalysisState.DISABLED,result.snapshot(output(),7,DemoBackend.BEAUTY_ONLY).state)
    }
    @Test fun fasterAnalysisDoesNotMakeStickersBlinkOnSlowerPreview() {
        val history = PreviewDetectionHistory()
        history.publish(result, 1)
        val future = result.copy(frame=source.copy(timestampNs=source.timestampNs+100_000_000))
        history.publish(future, 1)
        // 预览比分析落后50ms；旧实现此处判定最新结果来自未来并隐藏全部贴纸。
        val between = history.snapshot(output(), 7, DemoBackend.MEDIAPIPE, 1)
        assertEquals(FaceAnalysisState.READY, between.state)
        assertEquals(source.timestampNs, between.result!!.info.timestampNs)
        val caughtUp = history.snapshot(output(future.frame.timestampNs), 7, DemoBackend.MEDIAPIPE, 1)
        assertEquals(future.frame.timestampNs, caughtUp.result!!.info.timestampNs)
    }
    @Test fun revisionCameraAndBackendChangesNeverRestoreOldStickers() {
        val history = PreviewDetectionHistory()
        history.publish(result, 1)
        for ((epoch,backend,revision) in listOf(Triple(8L,DemoBackend.MEDIAPIPE,1L),
            Triple(7L,DemoBackend.BEAUTY_ONLY,1L),Triple(7L,DemoBackend.MEDIAPIPE,2L))) {
            assertNull(history.snapshot(output(),epoch,backend,revision).result)
        }
        history.publish(result.copy(frame=source.copy(timestampNs=1)), 2)
        assertNull(history.snapshot(output(),7,DemoBackend.MEDIAPIPE,1).result)
        assertEquals(FaceAnalysisState.READY,history.snapshot(output(2),7,DemoBackend.MEDIAPIPE,2).state)
        history.clear()
        assertNull(history.snapshot(output(2),7,DemoBackend.MEDIAPIPE,2).result)
    }
}
