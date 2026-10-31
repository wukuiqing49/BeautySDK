package com.beautysdk.resources

import com.beautysdk.*
import org.junit.Test
import org.junit.Assert.*

class ResourceCompositionTest {
    private fun descriptor(category:ResourceCategory,sub:String,scope:Set<String>)=ResourceDescriptor("sample",1,category,sub,"sample","zh-CN",ResourceText("默认"),mapOf("en" to ResourceText("English"),"zh-CN" to ResourceText("简体"),"zh-Hant" to ResourceText("繁體")),emptySet(),scope)
    @Test fun languageFallsBackToParentThenDefault(){val d=descriptor(ResourceCategory.MAKEUP,"EYELASH",emptySet());assertEquals("English",d.localized("en-US").name);assertEquals("简体",d.localized("fr-FR").name);assertEquals("繁體",d.localized("zh-Hant-TW").name)}
    @Test fun eyelashScopePreservesLipstickAndSmoothing(){val image=StickerImage(1,1,intArrayOf(0x80000000.toInt()));val points=(0..8).map{FacePoint(it/8f,.8f)};val material=EyeMakeup(image,points);val d=descriptor(ResourceCategory.MAKEUP,"EYELASH",setOf("makeup.eyelashes"));val p=PreparedResource(d,byteArrayOf(),mapOf("makeup.eyelashes" to .8f),BeautySettings(eyeMakeup=material));val s=p.applyTo(BeautySettings(smoothing=.6f,adjustments=BeautyAdjustments(lipstick=.4f)),ResourceCapabilities(emptySet()));assertEquals(.6f,s.smoothing);assertEquals(.4f,s.adjustments.lipstick);assertEquals(.8f,s.adjustments.eyelashes);assertSame(material,s.eyeMakeup)}
    @Test fun explicitParametersOverrideOnlyMentionedFields(){val base=BeautySettings(smoothing=.5f,adjustments=BeautyAdjustments(lipstick=.7f,contrast=1.3f));val s=BeautyParameters.apply(base,mapOf("beauty.whitening" to .4f));assertEquals(.5f,s.smoothing);assertEquals(.4f,s.whitening);assertEquals(.7f,s.adjustments.lipstick);assertEquals(1.3f,s.adjustments.contrast)}
    @Test fun signedParametersAndNeutralValuesRemainCorrect(){val s=BeautyParameters.apply(BeautySettings(),mapOf("reshape.chinLength" to -.4f,"color.saturation" to 0f));assertEquals(-.4f,s.adjustments.chinLength);assertEquals(0f,s.adjustments.saturation);assertEquals(1f,BeautyParameters.neutral["color.contrast"])}
    @Test fun invalidAndUnknownParametersAreRejected(){assertThrows(Exception::class.java){BeautyParameters.validate(mapOf("reshape.chinLength" to 2f))};assertThrows(Exception::class.java){BeautyParameters.validate(mapOf("beauty.smoothing" to Float.NaN))};assertThrows(Exception::class.java){BeautyParameters.validate(mapOf("unknown" to 0f))}}
    @Test fun defaultMergePreservesExplicitDisableAndHostPriority(){val d=BeautyResourceDefaults("zh-CN",true,BeautyResourceState(parameters=mapOf("beauty.smoothing" to .3f),selections=mapOf("makeup.eyelash" to "a")),ResourceCategory.entries);val s=d.merge(BeautyResourceState(parameters=mapOf("beauty.smoothing" to .5f),selections=mapOf("makeup.eyelash" to null),enabled=false),BeautyResourceState(parameters=mapOf("beauty.smoothing" to .8f),enabled=false));assertEquals(.8f,s.parameters["beauty.smoothing"]);assertTrue(s.selections.containsKey("makeup.eyelash"));assertNull(s.selections["makeup.eyelash"]);assertFalse(s.enabled)}

    @Test fun savedThemeReplacesDefaultSlotsBeforePreparing(){
        val defaults=BeautyResourceDefaults("zh-CN",true,BeautyResourceState(selections=mapOf("makeup.eyelash" to "a")),ResourceCategory.entries)
        assertEquals(mapOf("theme" to "b"),defaults.merge(BeautyResourceState(selections=mapOf("theme" to "b"))).selections)
        val withTheme=defaults.copy(state=BeautyResourceState(selections=mapOf("theme" to "a")))
        assertEquals(mapOf("makeup.eyelash" to "b"),withTheme.merge(BeautyResourceState(selections=mapOf("makeup.eyelash" to "b"))).selections)
    }
    @Test fun savedResourceScopeWinsOverDefaultParametersUnlessManuallyOverridden(){
        val d=descriptor(ResourceCategory.BEAUTY,"PRESET",setOf("beauty.smoothing"))
        val installed=listOf(InstalledResource("a",d))
        val defaults=BeautyResourceDefaults("zh-CN",true,BeautyResourceState(parameters=mapOf("beauty.smoothing" to .9f,"beauty.whitening" to .2f)),ResourceCategory.entries)
        val saved=BeautyResourceState(selections=mapOf("beauty" to "a"))
        assertFalse(defaults.merge(saved,installed=installed).parameters.containsKey("beauty.smoothing"))
        assertEquals(.2f,defaults.merge(saved,installed=installed).parameters["beauty.whitening"])
        assertEquals(.6f,defaults.merge(saved.copy(parameters=mapOf("beauty.smoothing" to .6f)),installed=installed).parameters["beauty.smoothing"])
    }

    @Test fun legacyBeautyWithoutEyeMaterialPreservesSelectedLashes(){
        val material=EyeMakeup(StickerImage(1,1,intArrayOf(0x80000000.toInt())),(0..8).map{FacePoint(it/8f,.8f)})
        val legacy=PreparedResource(descriptor(ResourceCategory.BEAUTY,"LEGACY",setOf("beauty.smoothing")),byteArrayOf(),mapOf("beauty.smoothing" to .3f),BeautySettings())
        val base=BeautySettings(eyeMakeup=material,adjustments=BeautyAdjustments(lipstick=.7f,eyelashes=.6f))
        val result=legacy.applyTo(base,ResourceCapabilities(emptySet()))
        assertSame(material,result.eyeMakeup)
        assertEquals(.7f,result.adjustments.lipstick)
        assertEquals(.6f,result.adjustments.eyelashes)
        assertEquals(.3f,result.smoothing)
    }
}
