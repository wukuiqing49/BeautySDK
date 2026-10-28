package com.beautysdk.demo.feature.app

import com.beautysdk.FaceAnalysisTiming
import com.beautysdk.FaceAnalyzerTiming
import org.junit.Assert.*
import org.junit.Test

class AnalysisTimingWindowTest {
    @Test fun recordsSlowFramesAndMissingBackendWithoutPretendingZero() {
        val window=AnalysisTimingWindow()
        window.add(FaceAnalysisTiming(1,1000,2000,10_000_000,null,1,FaceAnalyzerTiming(1000,9_000_000)))
        window.add(FaceAnalysisTiming(2,1000,2000,200_000_000,210_000_000,0,null))
        val text=window.summary()
        assertTrue(text.contains("n=2 faceResults=1"))
        assertTrue(text.contains("totalMeanMs=105.0 totalMaxMs=200.0"))
        assertTrue(text.contains("inferMeanMs=9.0 inferMaxMs=9.0"))
        assertTrue(text.contains("intervalMeanMs=210.0"))
        window.clear()
        assertTrue(window.summary().contains("n=0 faceResults=0"))
        assertTrue(window.summary().contains("inferMs=unavailable"))
    }
}
