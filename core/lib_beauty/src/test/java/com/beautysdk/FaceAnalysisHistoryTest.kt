package com.beautysdk

import org.junit.Assert.*
import org.junit.Test

class FaceAnalysisHistoryTest {
    private val info = FrameInfo(320, 240, 1_000_000_000, 90, true)
    private val face = FaceObservation(FaceBounds(.2f, .2f, .8f, .8f))
    private val maxAge = 150_000_000L
    private fun frame(ms: Long) = info.copy(timestampNs = info.timestampNs + ms * 1_000_000)

    @Test fun producerAheadOfPreviewKeepsNewestNonFutureFaceWithoutChangingAge() {
        val history = FaceAnalysisHistory()
        history.publish(frame(0), listOf(face), 4)
        history.publish(frame(33), listOf(face), 5)
        history.publish(frame(66), listOf(face), 6)
        val snapshot = history.snapshot(frame(40), maxAge)
        assertEquals(FaceAnalysisState.READY, snapshot.state)
        assertEquals(frame(33), snapshot.result!!.info)
        assertEquals(7_000_000L, snapshot.resultAgeNs)
        assertEquals(5L, snapshot.durationNs)
        assertNull(history.snapshot(frame(-1), maxAge).result)
    }

    @Test fun futureMissAndErrorDoNotHideFaceUntilTheirVideoFrameArrives() {
        for (error in listOf(null, "model failed")) {
            val history = FaceAnalysisHistory()
            history.publish(frame(0), listOf(face), 4)
            history.publish(frame(66), emptyList(), 5, error)
            assertEquals(FaceAnalysisState.READY, history.snapshot(frame(40), maxAge).state)
            val miss = history.snapshot(frame(66), maxAge)
            assertEquals(if (error == null) FaceAnalysisState.NO_FACE else FaceAnalysisState.ERROR, miss.state)
            if (error == null) assertTrue(miss.result!!.faces.isEmpty()) else assertNull(miss.result)
            // 一旦无脸/错误已经生效，之后不能回退到更早的有脸结果。
            assertNotEquals(FaceAnalysisState.READY, history.snapshot(frame(100), maxAge).state)
        }
    }

    @Test fun expiryIsStillMeasuredFromCaptureEvenIfNewerResultIsInFuture() {
        val history = FaceAnalysisHistory()
        history.publish(frame(0), listOf(face), 4)
        history.publish(frame(200), listOf(face), 5)
        assertEquals(FaceAnalysisState.READY, history.snapshot(frame(150), maxAge).state)
        val stale = history.snapshot(frame(151), maxAge)
        assertEquals(FaceAnalysisState.STALE, stale.state)
        assertEquals(151_000_000L, stale.resultAgeNs)
        assertNull(stale.result)
        assertEquals(FaceAnalysisState.READY, history.snapshot(frame(200), maxAge).state)
    }

    @Test fun ringIsBoundedAndOutOfOrderResultsCannotReplaceNewerSignals() {
        val history = FaceAnalysisHistory(2)
        history.publish(frame(0), listOf(face), 4)
        history.publish(frame(33), listOf(face), 5)
        history.publish(frame(66), emptyList(), 6)
        assertNull(history.snapshot(frame(20), maxAge).result)
        assertFalse(history.publish(frame(66), listOf(face), 7))
        assertFalse(history.publish(frame(50), listOf(face), 8))
        assertEquals(FaceAnalysisState.NO_FACE, history.snapshot(frame(66), maxAge).state)
    }

    @Test fun geometryAndExplicitStreamResetInvalidateOldFaces() {
        val history = FaceAnalysisHistory()
        history.publish(frame(0), listOf(face), 4)
        assertNull(history.snapshot(frame(20).copy(mirrored=false), maxAge).result)
        assertNull(history.snapshot(frame(20).copy(rotationDegrees=270), maxAge).result)
        assertNull(history.snapshot(frame(20).copy(width=640, height=480), maxAge).result)
        history.publish(frame(33).copy(rotationDegrees=270), listOf(face), 5)
        assertNull(history.snapshot(frame(20), maxAge).result)
        history.clear()
        assertNull(history.snapshot(frame(40).copy(rotationDegrees=270), maxAge).result)
        assertTrue(history.publish(frame(-1), listOf(face), 6))
    }

    @Test fun sharedViewportAllowsScaledFramesButNotDifferentAspectRatio() {
        val history = FaceAnalysisHistory(allowScaledFrames=true)
        history.publish(frame(0), listOf(face), 4)
        assertEquals(FaceAnalysisState.READY,
            history.snapshot(frame(20).copy(width=640,height=480),maxAge).state)
        assertNull(history.snapshot(frame(20).copy(width=1280,height=720),maxAge).result)
    }
}
