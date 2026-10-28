package com.beautysdk.demo
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** 使用网页正式打包器生成的夹具，验证三类资源在公共SDK中合并、导出和拒绝坏包。 */
class EffectResourceDeviceTest {
    private val assets=InstrumentationRegistry.getInstrumentation().context.assets
    private fun load(name:String)=EffectResourcePack.loadZip(assets.open("effect_packs/$name.zip"))
    @Test fun webResourcesMergeByTypeAndRoundTrip() {
        val bg=load("background");val motion=load("motion");val beauty=load("beauty")
        val original=BeautySettings(glasses=true,moustache=true)
        val result=beauty.applyTo(motion.applyTo(bg.applyTo(original)))
        assertTrue(result.glasses && result.moustache)
        assertEquals(BackgroundMode.IMAGE,result.scene.background)
        assertEquals(.25f,result.scene.backgroundFocusX,0f)
        assertEquals(CameraMotion.KEYFRAMES,result.scene.motion)
        assertEquals(1.5f,result.scene.timeline!!.sample(1000).zoom,.00001f)
        assertEquals(BeautyFilter.COOL,result.filter)
        assertTrue(result.requiresFaceAnalysis && beauty.requiresExtendedBeauty)
        assertSame(result.scene.backgroundImage,bg.applyTo(result).scene.backgroundImage)
        for(pack in listOf(bg,motion,beauty)) {
            val output=ByteArrayOutputStream();pack.writeZip(output)
            val loaded=EffectResourcePack.loadZip(output.toByteArray().inputStream())
            assertEquals(pack.id,loaded.id);assertEquals(pack.type,loaded.type)
        }
    }
    @Test fun bundledCompleteThemeCanBeConsumedByPublicSdk() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(7,context.resources.getStringArray(R.array.effect_resource_samples).size)
        val pack=BeautyThemePack.loadZip(context.assets.open("effects/full_theme.zip"))
        val settings=pack.prepare(ThemeCapabilities(true,true,true))
        assertFalse(settings.stickerPack!!.layers.isEmpty())
        assertTrue(settings.scene.needsSegmentation)
    }
    @Test fun v3RetainsMakeupAndSignedShapes() {
        val pack=load("beauty_v3")
        val a=pack.applyTo(BeautySettings()).adjustments
        assertEquals(.45f,a.lipstick,0f);assertEquals(.25f,a.blusher,0f)
        assertEquals(.15f,a.slimNose,0f);assertEquals(-.2f,a.chinLength,0f);assertEquals(.1f,a.mouthSize,0f)
        val out=ByteArrayOutputStream();pack.writeZip(out)
        assertEquals(a,EffectResourcePack.loadZip(out.toByteArray().inputStream()).applyTo(BeautySettings()).adjustments)
    }
    @Test fun invalidV2DoesNotSilentlyFallBack() {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(assets.open("effect_packs/motion.zip")).use { zip -> while(true) { val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes() } }
        val text=entries.getValue("manifest.json").toString(Charsets.UTF_8)
        for(bad in listOf(text.replace("KEYFRAMES","UNKNOWN"),text.replace("\"periodMs\": 4000","\"periodMs\": 4000.00001"),text.replace("\"zoom\": 1.5","\"zoom\": \"1.5\""),text.replace("\"schemaVersion\": 2","\"schemaVersion\": 3"))) {
            val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(if(name=="manifest.json") bad.toByteArray() else bytes);zip.closeEntry() } }
            try { EffectResourcePack.loadZip(out.toByteArray().inputStream());fail("invalid resource") } catch(_:Exception) { }
        }
    }
}
