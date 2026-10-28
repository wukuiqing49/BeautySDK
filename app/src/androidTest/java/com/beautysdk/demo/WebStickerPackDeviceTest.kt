package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test

/** 网页真实打包器导出夹具由Android正式加载器读取，不采集相机或修改用户草稿。 */
class WebStickerPackDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test fun webExportedSixPacksLoadUsingPublicSdk() {
        val names = assets.list("web_sticker_packs")!!.filter { it.endsWith(".zip") && it != "browser_hat.zip" }
        assertEquals(6, names.size)
        for (name in names) {
            val resource = StickerResourcePack.loadZip(assets.open("web_sticker_packs/$name"))
            val spec = resource.manifest.layers.single()
            val layer = resource.toStickerPack().layers.single()
            assertEquals(.2f, layer.offsetX, 0f)
            assertEquals(-.3f, layer.offsetY, 0f)
            assertEquals(15f, layer.rotationDegrees, 0f)
            assertEquals(spec.anchor, layer.anchor)
            assertTrue(resource.previewPng().isNotEmpty())
        }
    }

    @Test fun browserDownloadedHatLoadsWithEditedMetadata() {
        val resource = StickerResourcePack.loadZip(assets.open("web_sticker_packs/browser_hat.zip"))
        assertEquals("workshop_hat", resource.manifest.id)
        assertEquals("工坊测试礼帽", resource.manifest.name)
        assertEquals(1f, resource.toStickerPack().layers.single().rotationDegrees, 0f)
        assertEquals(1.45f, resource.toStickerPack().layers.single().pivotY, 0f)
    }

}
