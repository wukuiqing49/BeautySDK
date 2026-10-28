package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** 使用同一份Web真实打包器夹具；不启动相机、不改用户草稿或已选设置。 */
class ThemeResourceDeviceTest {
    private fun bytes()=InstrumentationRegistry.getInstrumentation().context.assets.open("theme_packs/full_theme.zip").use { it.readBytes() }
    private fun rejected(action:()->Unit){try{action();fail("Expected rejection")}catch(_:Exception){}}
    @Test fun completeThemeCapabilitiesSettingsAndRoundTrip(){
        val theme=BeautyThemePack.loadZip(bytes().inputStream())
        assertEquals("full_theme",theme.id)
        assertTrue(theme.requirements.faceTracking && theme.requirements.segmentation && theme.requirements.extendedBeauty)
        rejected { theme.prepare(ThemeCapabilities()) }
        rejected { theme.prepare(ThemeCapabilities(true,false,true)) }
        rejected { theme.prepare(ThemeCapabilities(true,true,false)) }
        val settings=theme.prepare(ThemeCapabilities(true,true,true))
        assertFalse(settings.stickerPack!!.layers.isEmpty())
        assertEquals(BackgroundMode.IMAGE,settings.scene.background)
        assertEquals(CameraMotion.KEYFRAMES,settings.scene.motion)
        assertEquals(BeautyFilter.WARM,settings.filter)
        assertFalse(settings.glasses || settings.moustache || settings.hat)
        assertSame(settings,theme.prepare(ThemeCapabilities(true,true,true)))
        BeautySession().use { session ->
            val old=BeautySettings(whitening=.8f,glasses=true);session.settings=old
            rejected { theme.applyTo(session,ThemeCapabilities()) };assertSame(old,session.settings)
            theme.applyTo(session,ThemeCapabilities(true,true,true));assertSame(settings,session.settings)
        }
        val out=ByteArrayOutputStream();theme.writeZip(out)
        assertEquals(theme.name,BeautyThemePack.loadZip(out.toByteArray().inputStream()).name)
    }
    @Test fun absentCategoriesResetAndDoNotInheritPreviousTheme(){
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(bytes().inputStream()).use { zip -> while(true){val e=zip.nextEntry ?: break;entries[e.name]=zip.readBytes()} }
        val root=org.json.JSONObject(entries.getValue("bundle.json").toString(Charsets.UTF_8))
        for(kind in listOf("sticker","background","beauty")){root.getJSONObject("resources").put(kind,org.json.JSONObject.NULL);entries.remove("$kind.zip")}
        entries["bundle.json"]=root.toString().toByteArray()
        val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> entries.forEach { (n,b)->zip.putNextEntry(ZipEntry(n));zip.write(b);zip.closeEntry() } }
        val onlyMotion=BeautyThemePack.loadZip(out.toByteArray().inputStream())
        BeautySession().use { session ->
            BeautyThemePack.loadZip(bytes().inputStream()).applyTo(session,ThemeCapabilities(true,true,true))
            onlyMotion.applyTo(session,ThemeCapabilities())
            assertNull(session.settings.stickerPack);assertEquals(BackgroundMode.NONE,session.settings.scene.background)
            assertEquals(0f,session.settings.smoothing,0f);assertEquals(BeautyFilter.NONE,session.settings.filter)
            assertEquals(CameraMotion.KEYFRAMES,session.settings.scene.motion)
        }
    }
    @Test fun nestedPixelBudgetRejectedBeforeDecode(){
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("theme_packs/oversized_theme.zip").use { it.readBytes() }
        rejected { BeautyThemePack.loadZip(bytes.inputStream()) }
    }
    @Test fun malformedThemeAndWrongChildCategoryRejected(){
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(bytes().inputStream()).use { zip -> while(true){val e=zip.nextEntry ?: break;entries[e.name]=zip.readBytes()} }
        fun archive(values:Map<String,ByteArray>):ByteArray {val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> values.forEach { (n,b)->zip.putNextEntry(ZipEntry(n));zip.write(b);zip.closeEntry() } };return out.toByteArray()}
        val text=entries.getValue("bundle.json").toString(Charsets.UTF_8)
        rejected { BeautyThemePack.loadZip(archive(entries+("bundle.json" to text.replace("\"bundleVersion\": 1","\"bundleVersion\": true").toByteArray())).inputStream()) }
        rejected { BeautyThemePack.loadZip(archive(entries+("background.zip" to entries.getValue("motion.zip"))).inputStream()) }
        rejected { BeautyThemePack.loadZip(archive(entries-("beauty.zip")).inputStream()) }
        rejected { BeautyThemePack.loadZip(archive(entries+("../extra.png" to entries.getValue("preview.png"))).inputStream()) }
    }
}
