package com.beautysdk.resources

import org.junit.Assert.*
import org.junit.Test

class ResourceCapabilitiesTest {
    private fun descriptor(vararg features:String)=ResourceDescriptor(
        "example",1,ResourceCategory.BACKGROUND,"PRESET","background","en",ResourceText("Example",""),emptyMap(),features.toSet(),emptySet(),
    )

    @Test fun rendererAloneDoesNotAdvertiseModelsOrSceneComposition() {
        val available=ResourceCapabilities.configuredGpuPixel()
        assertTrue(available.features.contains("beauty.parameters.v1"))
        assertEquals(setOf("segmentation","background.v1"),available.missingFeatures(descriptor("segmentation","background.v1")))
        assertFalse(available.features.contains("face.landmarks478"))
    }

    @Test fun sceneAndFaceAreIndependentAndMissingCapabilitiesAreStructured() {
        val available=ResourceCapabilities.configuredGpuPixel(sceneRenderer=true,segmentation=true)
        assertTrue(available.features.contains("background.v1"))
        assertFalse(available.features.contains("makeup.parameters.v1"))
        try {
            available.requireSupported(descriptor("makeup.parameters.v1"))
            fail("Expected missing face support")
        } catch(error:UnsupportedResourceException) {
            assertEquals("UNSUPPORTED_RESOURCE",error.code)
            assertEquals("example",error.resourceId)
            assertEquals(setOf("makeup.parameters.v1"),error.missingFeatures)
        }
    }

    @Test fun completeConfigurationMatchesLegacyDeclaration() {
        assertEquals(ResourceCapabilities.androidGpuPixel().features,ResourceCapabilities.configuredGpuPixel(true,true,true).features)
    }

    @Test(expected=IllegalArgumentException::class)
    fun segmentationCannotBeDeclaredWithoutSceneRenderer() {
        ResourceCapabilities.configuredGpuPixel(segmentation=true)
    }
}
