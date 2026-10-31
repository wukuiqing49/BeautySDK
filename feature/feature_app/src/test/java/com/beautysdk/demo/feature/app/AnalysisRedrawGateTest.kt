package com.beautysdk.demo.feature.app

import org.junit.Assert.*
import org.junit.Test

class AnalysisRedrawGateTest {
    @Test fun lateCompletionWakesOnlyCurrentSampleOnce() {
        val gate=AnalysisRedrawGate()
        gate.beginFrame(10,true)
        assertFalse(gate.completed(9))
        assertTrue(gate.completed(10))
        gate.beginFrame(10,true)
        assertFalse(gate.completed(10))
        gate.beginFrame(11,true)
        assertFalse(gate.completed(10))
        assertTrue(gate.completed(11))
    }

    @Test fun alreadyFreshOrDisabledOrResetDoesNotWake() {
        val gate=AnalysisRedrawGate()
        gate.beginFrame(10,true)
        gate.rendered(10,10)
        assertFalse(gate.completed(10))
        gate.beginFrame(11,false)
        assertFalse(gate.completed(11))
        gate.beginFrame(12,true)
        gate.clear()
        assertFalse(gate.completed(12))
    }

    @Test fun oldResultDoesNotMarkNewVideoFrameFresh() {
        val gate=AnalysisRedrawGate()
        gate.beginFrame(11,true)
        gate.rendered(10,10)
        gate.rendered(11,10)
        assertTrue(gate.completed(11))
    }
}
