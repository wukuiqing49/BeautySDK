package com.beautysdk

import org.junit.Assert.*
import org.junit.Test

class BeautyAdjustmentsTest {
    @Test fun neutralPassesThroughAndOnlyReshapeNeedsFaces() {
        assertTrue(BeautySettings(adjustments=BeautyAdjustments()).isIdentity)
        assertFalse(BeautySettings(adjustments=BeautyAdjustments(brightness=.3f)).requiresFaceAnalysis)
        assertFalse(BeautySettings(adjustments=BeautyAdjustments(saturation=0f)).isIdentity)
        assertTrue(BeautySettings(adjustments=BeautyAdjustments(slimFace=.3f)).requiresFaceAnalysis)
        assertTrue(BeautySettings(adjustments=BeautyAdjustments(bigEyes=.3f)).requiresFaceAnalysis)
        for(a in listOf(BeautyAdjustments(lipstick=.5f),BeautyAdjustments(blusher=.5f),BeautyAdjustments(eyelashes=.5f))) {
            assertTrue(a.hasMakeup);assertFalse(a.hasReshape);assertFalse(a.hasColor)
            assertTrue(BeautySettings(adjustments=a).requiresFaceAnalysis)
            assertFalse(BeautySettings(adjustments=a).isIdentity)
        }
    }
    @Test fun additionalShapesAreIndependentFaceEffectsWithSignedControls() {
        for(a in listOf(BeautyAdjustments(slimNose=.5f),BeautyAdjustments(chinLength=-.5f),BeautyAdjustments(mouthSize=.5f))) {
            assertTrue(a.hasReshape);assertTrue(a.needsFace)
            assertFalse(a.hasMakeup);assertFalse(a.hasColor);assertFalse(a.isIdentity)
            assertTrue(BeautySettings(adjustments=a).requiresFaceAnalysis)
        }
        assertThrows(IllegalArgumentException::class.java) {BeautyAdjustments(slimNose=-.01f)}
        assertThrows(IllegalArgumentException::class.java) {BeautyAdjustments(chinLength=Float.NaN)}
        assertThrows(IllegalArgumentException::class.java) {BeautyAdjustments(mouthSize=-1.01f)}
    }
    @Test fun invalidAndNonFiniteStrengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(brightness=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(contrast=2.1f) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(slimFace=-.1f) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(bigEyes=Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(lipstick=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(blusher=1.01f) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(eyelashes=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BeautyAdjustments(eyelashes=1.01f) }
    }
}
