package com.beautysdk.demo.feature.app

import org.junit.Assert.*
import org.junit.Test

class PreviewRequestTest {
    @Test fun mouthStickerGetsFreshFramePathWithoutEnablingMakeupOrChangingEyes() {
        fun pack(anchor:com.beautysdk.StickerAnchor)=com.beautysdk.StickerPack(listOf(com.beautysdk.ImageSticker(com.beautysdk.StickerImage(1,1,intArrayOf(-1)),anchor)))
        val effects=DemoEffects(0f,0f,false,false,false)
        val base=PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,effects,beautyBackend=DemoBeautyBackend.GPUPIXEL),false,0)
        val mouth=pack(com.beautysdk.StickerAnchor.UPPER_LIP)
        val cases=listOf(base.copy(options=base.options.copy(effects=effects.copy(moustache=true))),
            base.copy(customPack=mouth),base.copy(options=base.options.copy(effects=effects.copy(themeSettings=com.beautysdk.BeautySettings(stickerPack=mouth)))))
        for(request in cases) {
            assertTrue(request.automaticGpuAnalysis);assertTrue(request.needsFreshFaceRedraw)
            assertFalse(request.copy(original=true).needsFreshFaceRedraw)
            assertFalse(request.copy(options=request.options.copy(faceAnalysisEnabled=false)).needsFreshFaceRedraw)
        }
        val eyes=base.copy(customPack=pack(com.beautysdk.StickerAnchor.EYES))
        assertFalse(eyes.automaticGpuAnalysis);assertFalse(eyes.needsFreshFaceRedraw)
        assertFalse(cases[0].copy(customPack=com.beautysdk.StickerPack(emptyList())).automaticGpuAnalysis)
        assertFalse(cases[0].copy(options=cases[0].options.copy(effects=cases[0].options.effects.copy(themeSettings=com.beautysdk.BeautySettings()))).automaticGpuAnalysis)
    }

    @Test fun makeupPrefersGpuWithoutDependingOnSliderStrengthOrOriginalPreview() {
        val options=CameraDemoOptions(DemoBackend.MEDIAPIPE,DemoEffects(0f,0f,false,false,false),beautyBackend=DemoBeautyBackend.GPUPIXEL)
        val neutral=PreviewRequest(options,false,0)
        assertFalse(neutral.automaticGpuAnalysis);assertFalse(neutral.prefersGpuAnalysis)
        for(a in listOf(com.beautysdk.BeautyAdjustments(lipstick=.1f),com.beautysdk.BeautyAdjustments(lipstick=.9f),
            com.beautysdk.BeautyAdjustments(blusher=.4f),com.beautysdk.BeautyAdjustments(eyelashes=.6f))) {
            val manual=neutral.copy(options=options.copy(effects=options.effects.copy(adjustments=a)))
            val theme=neutral.copy(options=options.copy(effects=options.effects.copy(themeSettings=com.beautysdk.BeautySettings(adjustments=a))))
            assertTrue(manual.prefersGpuAnalysis);assertTrue(theme.prefersGpuAnalysis)
            assertTrue("Long-press original must not change backend preference",theme.copy(original=true).prefersGpuAnalysis)
            assertFalse(manual.copy(options=manual.options.copy(beautyBackend=DemoBeautyBackend.BASIC_GL)).automaticGpuAnalysis)
        }
        assertTrue(neutral.copy(options=options.copy(mediaPipeGpu=true)).prefersGpuAnalysis)
    }
    @Test fun threeDRequestsRealFaceAnalysisEvenWithAnEmptyTheme() {
        val options=CameraDemoOptions(DemoBackend.MEDIAPIPE,
            DemoEffects(0f,0f,false,false,false,themeSettings=com.beautysdk.BeautySettings()),
            sticker3D=com.beautysdk.BuiltInStickers3D.glasses())
        val request=PreviewRequest(options,false,0)
        assertTrue(request.needsAnalysis)
        assertFalse(request.copy(original=true).needsAnalysis)
        assertFalse(request.copy(options=options.copy(faceAnalysisEnabled=false)).needsAnalysis)
        assertFalse(request.copy(options=options.copy(sticker3D=null)).needsAnalysis)
    }
    @Test fun explicitFaceObservationAndSwitchOverrideEffectDemand() {
        val options=CameraDemoOptions(DemoBackend.MEDIAPIPE,DemoEffects(0f,0f,false,false,false),observeFaces=true,gestureEnabled=true)
        val request=PreviewRequest(options,false,0)
        assertTrue(request.needsAnalysis)
        assertTrue(request.needsGestures)
        val off=request.copy(options=options.copy(faceAnalysisEnabled=false,effects=options.effects.copy(glasses=true)))
        assertFalse(off.needsAnalysis)
        assertTrue(off.needsGestures)
        assertFalse(request.copy(original=true).needsAnalysis)
        assertFalse(request.copy(original=true).needsGestures)
    }

    @Test fun gestureDemandDoesNotDependOnFaceEffectsAndOriginalDisablesIt() {
        val request=PreviewRequest(CameraDemoOptions(DemoBackend.BEAUTY_ONLY,
            DemoEffects(0f,0f,false,false,false),gestureEnabled=true),false,0)
        assertTrue(request.needsGestures)
        assertFalse(request.needsAnalysis)
        assertFalse(request.copy(original=true).needsGestures)
        assertFalse(request.copy(options=request.options.copy(gestureEnabled=false)).needsGestures)
    }

    @Test fun importedPackDrivesAnalysisAndEmptyOverrideDisablesBuiltIns() {
        val effects=DemoEffects(0f,0f,false,true,false)
        val base=PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,effects),false,0)
        assertFalse(base.copy(customPack=com.beautysdk.StickerPack(emptyList())).needsAnalysis)
        val pack=com.beautysdk.StickerPack(listOf(com.beautysdk.ImageSticker(
            com.beautysdk.StickerImage(1,1,intArrayOf(-1)),com.beautysdk.StickerAnchor.EYES)))
        val imported=base.copy(options=base.options.copy(effects=effects.copy(glasses=false)),customPack=pack)
        assertTrue(imported.needsAnalysis)
        assertFalse(imported.copy(original=true).needsAnalysis)
        assertFalse(imported.copy(options=imported.options.copy(backend=DemoBackend.BEAUTY_ONLY)).needsAnalysis)
    }

    @Test fun analysisRunsOnlyForVisibleEnabledStickers() {
        val effects = DemoEffects(.4f, .3f, false, false, false)
        val request = PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE, effects), false, 0)
        assertFalse(request.needsAnalysis)
        for (sticker in listOf(effects.copy(glasses = true), effects.copy(moustache = true), effects.copy(hat = true))) {
            val active = request.copy(options = request.options.copy(effects = sticker))
            assertTrue(active.needsAnalysis)
            assertFalse(active.copy(original = true).needsAnalysis)
            assertFalse(active.copy(options = active.options.copy(backend = DemoBackend.BEAUTY_ONLY)).needsAnalysis)
        }
        assertFalse(request.copy(options = request.options.copy(effects = effects.copy(glasses = true, glassesStyle = 0))).needsAnalysis)
        assertFalse(request.copy(options = request.options.copy(effects = effects.copy(hat = true, headStyle = 0))).needsAnalysis)
    }
    @Test fun reshapeDrivesAnalysisWithoutStickersButColorDoesNot() {
        val effects=DemoEffects(0f,0f,false,false,false,
            adjustments=com.beautysdk.BeautyAdjustments(slimFace=.3f))
        val request=PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,effects,
            beautyBackend=DemoBeautyBackend.GPUPIXEL),false,0,com.beautysdk.StickerPack(emptyList()))
        assertTrue(request.needsAnalysis)
        assertFalse(request.copy(original=true).needsAnalysis)
        assertFalse(request.copy(options=request.options.copy(beautyBackend=DemoBeautyBackend.BASIC_GL)).needsAnalysis)
        assertFalse(request.copy(options=request.options.copy(effects=effects.copy(
            adjustments=com.beautysdk.BeautyAdjustments(brightness=.5f)))).needsAnalysis)
    }
    @Test fun makeupAloneRequestsFacesAndOriginalBypassesIt() {
        for(a in listOf(com.beautysdk.BeautyAdjustments(lipstick=.5f),com.beautysdk.BeautyAdjustments(blusher=.5f),
            com.beautysdk.BeautyAdjustments(slimNose=.5f),com.beautysdk.BeautyAdjustments(chinLength=-.5f),com.beautysdk.BeautyAdjustments(mouthSize=.5f))) {
            val request=PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,
                DemoEffects(0f,0f,false,false,false,adjustments=a),beautyBackend=DemoBeautyBackend.GPUPIXEL),false,0)
            assertTrue(request.needsAnalysis)
            assertFalse(request.copy(original=true).needsAnalysis)
            assertFalse(request.copy(options=request.options.copy(beautyBackend=DemoBeautyBackend.BASIC_GL)).needsAnalysis)
        }
    }
    @Test fun segmentationIndependentOfFaceBackendAndOriginalDisablesBoth() {
        val effects=DemoEffects(0f,0f,false,false,false,scene=com.beautysdk.BeautyScene(background=com.beautysdk.BackgroundMode.BLUR))
        val request=PreviewRequest(CameraDemoOptions(DemoBackend.BEAUTY_ONLY,effects),false,0)
        assertTrue(request.needsSegmentation);assertFalse(request.needsAnalysis)
        assertFalse(request.copy(original=true).needsSegmentation)
        val follow=request.copy(options=request.options.copy(backend=DemoBackend.MEDIAPIPE,effects=effects.copy(
            scene=com.beautysdk.BeautyScene(motion=com.beautysdk.CameraMotion.FACE_FOLLOW))))
        assertTrue(follow.needsAnalysis);assertFalse(follow.needsSegmentation)
    }
    @Test fun completeThemeOverridesOldManualAndCustomStickerAnalysis() {
        val image=com.beautysdk.StickerImage(1,1,intArrayOf(-1))
        val pack=com.beautysdk.StickerPack(listOf(com.beautysdk.ImageSticker(image,com.beautysdk.StickerAnchor.EYES)))
        val old=DemoEffects(0f,0f,false,true,true,themeSettings=com.beautysdk.BeautySettings())
        val request=PreviewRequest(CameraDemoOptions(DemoBackend.MEDIAPIPE,old),false,0,pack)
        assertFalse(request.needsAnalysis)
        val active=request.copy(options=request.options.copy(effects=old.copy(themeSettings=com.beautysdk.BeautySettings(stickerPack=pack))))
        assertTrue(active.needsAnalysis)
        assertFalse(active.copy(original=true).needsAnalysis)
    }
}
