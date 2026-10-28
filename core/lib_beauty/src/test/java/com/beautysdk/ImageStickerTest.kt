package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ImageStickerTest {
    private fun face() = FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),mapOf(
        FaceAnchor.LEFT_EYE to FacePoint(.4f,.5f),FaceAnchor.RIGHT_EYE to FacePoint(.6f,.5f)))

    @Test fun asymmetricImageKeepsDisplayOrientationForEveryRotationAndMirror() {
        val image = StickerImage(2,2,intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),
            0xff0000ff.toInt(),0xffffffff.toInt()))
        val settings = BeautySettings(stickerPack=StickerPack(listOf(ImageSticker(image,StickerAnchor.EYES,2f))))
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val info = FrameInfo(200,200,1,rotation,mirror)
            val orientation = AnalysisOrientation.upright(AnalysisImage(info,200,200,ByteArray(200*200*3)))
            val input = RgbaFrame(info,ByteBuffer.allocate(200*200*4),800)
            val output = RgbaBeautyRenderer().render(input,settings,orientation.toRaw(face())) as RgbaFrame
            for ((point,expected) in listOf(FacePoint(.35f,.35f) to 0xffff0000.toInt(),
                FacePoint(.65f,.35f) to 0xff00ff00.toInt(),FacePoint(.35f,.65f) to 0xff0000ff.toInt())) {
                val raw = orientation.toRaw(point)
                val x = (raw.x*200).toInt(); val y = (raw.y*200).toInt()
                val data=output.readOnlyPixels(); val index=y*800+x*4
                val actual=((data.get(index+3).toInt() and 255) shl 24) or
                    ((data.get(index).toInt() and 255) shl 16) or
                    ((data.get(index+1).toInt() and 255) shl 8) or (data.get(index+2).toInt() and 255)
                assertEquals("$rotation/$mirror/$point",expected,actual)
            }
            assertEquals(info,output.info)
            assertEquals(0,input.readOnlyPixels().get(100*800+100*4).toInt())
        }
    }

    @Test fun straightAlphaCompositesLayersAndMissingAnchorsHideImages() {
        val info=FrameInfo(100,100,1)
        val input=RgbaFrame(info,ByteBuffer.allocate(40000),400)
        val red=ImageSticker(StickerImage(1,1,intArrayOf(0x80ff0000.toInt())),StickerAnchor.EYES)
        val green=ImageSticker(StickerImage(1,1,intArrayOf(0x8000ff00.toInt())),StickerAnchor.EYES)
        val settings=BeautySettings(stickerPack=StickerPack(listOf(red,green)))
        val renderer=RgbaBeautyRenderer()
        val out=(renderer.render(input,settings,face()) as RgbaFrame).readOnlyPixels()
        val i=50*400+50*4
        assertEquals(85,out.get(i).toInt() and 255)
        assertEquals(170,out.get(i+1).toInt() and 255)
        assertEquals(192,out.get(i+3).toInt() and 255)
        assertEquals(0,(renderer.render(input,settings,null) as RgbaFrame).readOnlyPixels().get(i+3).toInt())
        val lip=BeautySettings(stickerPack=StickerPack(listOf(red.copy(anchor=StickerAnchor.UPPER_LIP))))
        assertEquals(0,(renderer.render(input,lip,face()) as RgbaFrame).readOnlyPixels().get(i+3).toInt())
        assertEquals(0,out.get(3).toInt()) // 画面外的素材区域保持透明。
    }

    @Test fun imageAndPackDefensivelyCopyMutableInput() {
        val pixels=intArrayOf(0xffff0000.toInt())
        val image=StickerImage(1,1,pixels)
        pixels[0]=0
        assertEquals(0xffff0000.toInt(),image.pixel(0,0))
        val layers=mutableListOf(ImageSticker(image,StickerAnchor.EYES))
        val pack=StickerPack(layers)
        layers.clear()
        assertEquals(1,pack.layers.size)
        assertThrows(IllegalArgumentException::class.java) { StickerPack(List(4) { pack.layers[0] }) }
    }
}
