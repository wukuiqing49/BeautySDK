package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StickerResourceGeometryTest {
    @Test fun offsetsFollowDisplayAxesAcrossResolutionRotationAndMirror() {
        val image=StickerImage(1,1,intArrayOf(-1))
        val layer=ImageSticker(image,StickerAnchor.EYES,offsetX=.3f,offsetY=-.2f,rotationDegrees=20f)
        for(size in listOf(100,200)) for(rotation in listOf(0,90,180,270)) for(mirror in listOf(false,true)) {
            val info=FrameInfo(size*2,size,1,rotation,mirror)
            val orientation=AnalysisOrientation.upright(AnalysisImage(info,info.width,info.height,ByteArray(info.width*info.height*3)))
            val face=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),mapOf(
                FaceAnchor.LEFT_EYE to FacePoint(.4f,.5f),FaceAnchor.RIGHT_EYE to FacePoint(.6f,.5f)))
            val placement=StickerGeometry.place(orientation.toRaw(face),info)
            val actual=layer.pose(placement,info)!!
            val expected=orientation.toRaw(FacePoint(.56f,.5f-.04f*orientation.image.width/orientation.image.height))
            assertEquals("$size/$rotation/$mirror x",expected.x,actual.center.x,.00001f)
            assertEquals("$size/$rotation/$mirror y",expected.y,actual.center.y,.00001f)
            val signed=20f*(kotlin.math.PI/180).toFloat()*(if(mirror) -1f else 1f)
            assertEquals(signed,actual.angleRadians-placement.glasses!!.angleRadians,.00001f)
        }
    }

    @Test fun layerEditsKeepImageAndDefaultPoseUnchanged() {
        val image=StickerImage(1,1,intArrayOf(-1))
        val layer=ImageSticker(image,StickerAnchor.EYES)
        val pose=StickerPose(FacePoint(.5f,.5f),100f,.2f)
        assertSame(pose,layer.pose(StickerPlacement(pose,null),FrameInfo(640,480,1)))
        assertSame(image,layer.copy(offsetX=.1f).image)
        assertNull(layer.copy(anchor=StickerAnchor.UPPER_LIP).pose(StickerPlacement(pose,null),FrameInfo(640,480,1)))
    }

    @Test fun resourceParametersRejectInvalidValuesAndCopyLayerList() {
        val layer=StickerLayerSpec("main","sticker.png",StickerAnchor.EYES)
        assertThrows(IllegalArgumentException::class.java) { layer.copy(image="../sticker.png") }
        assertThrows(IllegalArgumentException::class.java) { layer.copy(widthInAnchorSpans=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { layer.copy(offsetX=4.1f) }
        assertThrows(IllegalArgumentException::class.java) { layer.copy(rotationDegrees=181f) }
        assertThrows(IllegalArgumentException::class.java) { StickerResourceManifest("sample","Sample",listOf(layer,layer)) }
        val layers=mutableListOf(layer)
        val manifest=StickerResourceManifest("sample","Sample",layers)
        layers.clear()
        assertEquals(1,manifest.layers.size)
        assertThrows(UnsupportedOperationException::class.java) { (manifest.layers as MutableList).clear() }
    }
}
