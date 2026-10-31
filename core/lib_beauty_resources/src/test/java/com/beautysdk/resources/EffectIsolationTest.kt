package com.beautysdk.resources

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test

class EffectIsolationTest {
    private fun d(category:ResourceCategory,sub:String,slot:String,scope:Set<String> = emptySet()) =
        ResourceDescriptor(slot,1,category,sub,slot,"en",ResourceText(slot),emptyMap(),emptySet(),scope)
    @Test fun missingLashCannotRestoreProceduralFallbackOrResetOtherMakeup() {
        val state=BeautyResourceState(parameters=mapOf("makeup.eyelashes" to .8f,"makeup.lipstick" to .7f),
            selections=mapOf("makeup.eyelash" to "missing"))
        val restored=state.resolveInstalled(emptyList())
        assertEquals(setOf("makeup.eyelash"),restored.missingSlots)
        assertEquals(0f,restored.state.parameters.getValue("makeup.eyelashes"),0f)
        assertEquals(.7f,restored.state.parameters.getValue("makeup.lipstick"),0f)
    }
    @Test fun everyParameterChangesOnlyItsOwnField() {
        val original=BeautyParameters.neutral.mapValues { (key,value)-> if(value==0f) .3f else .8f }
        val state=BeautyResourceState(original,mapOf("sticker.moustache" to "hat", "makeup.eyelash" to "lash"))
        for(key in original.keys) {
            val changed=BeautyResourceEdits.parameters(state,BeautyParameters.category(key),mapOf(key to .5f))
            assertEquals(state.selections,changed.selections)
            assertEquals(original-key,changed.parameters-key)
            assertEquals(.5f,changed.parameters.getValue(key),0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BeautyResourceEdits.parameters(state,ResourceCategory.BEAUTY,mapOf("makeup.lipstick" to .5f))
        }
    }
    @Test fun deletingActiveLashNeutralizesOnlyLashesAndKeepsMoustache() {
        val lash=InstalledResource("lash",d(ResourceCategory.MAKEUP,"EYELASH","makeup.eyelash",setOf("makeup.eyelashes")))
        val state=BeautyResourceState(mapOf("makeup.eyelashes" to .8f,"makeup.lipstick" to .6f,"makeup.blusher" to .2f),
            mapOf("makeup.eyelash" to "lash","sticker.moustache" to "moustache"))
        val result=BeautyResourceEdits.remove(state,"lash",listOf(lash))
        assertEquals(0f,result.parameters.getValue("makeup.eyelashes"),0f)
        assertEquals(state.parameters-"makeup.eyelashes",result.parameters-"makeup.eyelashes")
        assertEquals("moustache",result.selections["sticker.moustache"])
        assertNull(result.selections["makeup.eyelash"])
        assertEquals(state,BeautyResourceEdits.remove(state,"unused",listOf(lash)))
    }
    @Test fun themeAndMaterializedSlotsUseIdenticalPrecedence() {
        val legacy=PreparedResource(d(ResourceCategory.BEAUTY,"LEGACY","beauty",setOf("makeup.lipstick")),byteArrayOf(),mapOf("makeup.lipstick" to .2f),BeautySettings())
        val lip=PreparedResource(d(ResourceCategory.MAKEUP,"LIPSTICK","makeup.lipstick",setOf("makeup.lipstick")),byteArrayOf(),mapOf("makeup.lipstick" to .8f),null)
        val capabilities=ResourceCapabilities(emptySet())
        for(children in listOf(listOf(lip,legacy),listOf(legacy,lip))) {
            val theme=PreparedResource(d(ResourceCategory.THEME,"THEME","theme"),byteArrayOf(),emptyMap(),null,children)
            val split=ResourceEffectApplication.ordered(children).fold(BeautySettings()) { s,r->r.applyTo(s,capabilities) }
            assertEquals(split,theme.applyTo(BeautySettings(),capabilities))
            assertEquals(.8f,split.adjustments.lipstick,0f)
        }
    }
    @Test fun makeupCannotReplaceStickerPayloadOrBackground() {
        val image=StickerImage(1,1,intArrayOf(-1))
        val pack=StickerPack(listOf(ImageSticker(image,StickerAnchor.UPPER_LIP)))
        val base=BeautySettings(stickerPack=pack,scene=BeautyScene(background=BackgroundMode.BLUR))
        for((sub,key) in listOf("LIPSTICK" to "makeup.lipstick","BLUSHER" to "makeup.blusher","EYELASH" to "makeup.eyelashes")) {
            val resource=PreparedResource(d(ResourceCategory.MAKEUP,sub,"makeup.$sub",setOf(key)),byteArrayOf(),mapOf(key to .6f),BeautySettings())
            val result=resource.applyTo(base,ResourceCapabilities(emptySet()))
            assertSame(pack,result.stickerPack);assertEquals(base.scene,result.scene)
        }
    }
}
