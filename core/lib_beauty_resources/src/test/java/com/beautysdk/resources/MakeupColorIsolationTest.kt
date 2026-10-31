package com.beautysdk.resources

import com.beautysdk.*
import org.junit.Test
import org.junit.Assert.*

class MakeupColorIsolationTest {
    @Test fun colorAndStrengthChangesPreserveOtherRegions() {
        val c=MakeupColor.fromRgb(0x884455)
        val base=BeautySettings(adjustments=BeautyAdjustments(lipstick=.5f,blusher=.4f,eyelashes=.8f))
        val tinted=MakeupColorOverrides.apply(base,mapOf("lipstick" to c))
        assertEquals(c,tinted.adjustments.makeupColors.lipstick)
        assertEquals(base.adjustments.makeupColors.blusher,tinted.adjustments.makeupColors.blusher)
        assertEquals(BeautyParameters.read(base),BeautyParameters.read(tinted))
        val changed=BeautyParameters.apply(tinted,mapOf("makeup.eyelashes" to .2f))
        assertEquals(tinted.adjustments.makeupColors,changed.adjustments.makeupColors)
        assertEquals(c,changed.adjustments.copy(lipstick=.1f).makeupColors.lipstick)
    }
    @Test fun invalidColorsAndUnknownRegionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java){MakeupColor(Float.NaN,0f,0f)}
        assertThrows(IllegalArgumentException::class.java){MakeupColor(1.1f,0f,0f)}
        assertThrows(IllegalArgumentException::class.java){MakeupColorOverrides.apply(BeautySettings(),mapOf("eyelashes" to MakeupColor(0f,0f,0f)))}
    }
    @Test fun stateCopyAndMergeKeepExplicitColorOverrides() {
        val color=MakeupColor(.1f,.2f,.3f)
        val state=BeautyResourceState(makeupColors=mapOf("lipstick" to color))
        assertEquals(state.makeupColors,state.copy(enabled=false).makeupColors)
        val defaults=BeautyResourceDefaults("en",true,BeautyResourceState(),ResourceCategory.entries)
        assertEquals(state.makeupColors,defaults.merge(state).makeupColors)
    }
}
