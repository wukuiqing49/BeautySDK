package com.beautysdk
import org.junit.Assert.*
import org.junit.Test

class BeautyPerformanceMeterTest {
    @Test fun measuresSuccessfulSamplesWithoutInventingGpuTime() {
        var now=0L;val meter=BeautyPerformanceMeter { now }
        assertNull(meter.snapshot().processedFps)
        repeat(30) { now+=33_333_334L;meter.record(2_000_000L) }
        val s=meter.snapshot();assertEquals(30L,s.processedFrames);assertEquals(30.0,s.processedFps!!,.001)
        assertEquals(2_000_000L,s.averageCpuSubmissionNs);assertNull(s.gpuExecutionNs)
        meter.reset();assertEquals(0L,meter.snapshot().processedFrames);assertNull(meter.snapshot().processedFps)
    }
}
