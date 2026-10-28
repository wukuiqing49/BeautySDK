package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

/** 正式Web打包器的V4资源跨端回读；坏包不允许降级成程序化假效果。 */
class EyeMakeupResourceDeviceTest {
    private val assets=InstrumentationRegistry.getInstrumentation().context.assets
    @Test fun allBuiltinPngStylesLoadRoundTripAndReplaceOldBeautyMaterial() {
        var previous:EyeMakeup?=null
        for(kind in listOf("natural","dense","cat")) {
            val pack=EffectResourcePack.loadZip(assets.open("effect_packs/lash_$kind.zip"))
            val settings=pack.applyTo(BeautySettings(eyeMakeup=previous))
            val eye=requireNotNull(settings.eyeMakeup)
            assertNotSame(previous,eye);assertEquals(9,eye.root.size)
            assertTrue(settings.requiresFaceAnalysis && pack.requiresExtendedBeauty)
            assertEquals(.85f,settings.adjustments.eyelashes,0f)
            val bytes=ByteArrayOutputStream();pack.writeZip(bytes)
            assertEquals(eye.root,EffectResourcePack.loadZip(bytes.toByteArray().inputStream()).applyTo(BeautySettings()).eyeMakeup!!.root)
            previous=eye
        }
        val old=EffectResourcePack.loadZip(assets.open("effect_packs/beauty_v3.zip"))
        assertNull(old.applyTo(BeautySettings(eyeMakeup=previous)).eyeMakeup)
        val theme=BeautyThemePack.loadZip(assets.open("eye_theme.zip"))
        assertTrue(theme.requirements.faceTracking && theme.requirements.extendedBeauty)
        assertFalse(theme.requirements.segmentation)
        assertNotNull(theme.prepare(ThemeCapabilities(true,false,true)).eyeMakeup)
    }
    @Test fun rejectsMalformedBindingsMissingPngOpaqueAndInvisibleMaterials() {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(assets.open("effect_packs/lash_natural.zip")).use {z->while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes()}}
        fun zip(files:Map<String,ByteArray>):ByteArray {
            val out=ByteArrayOutputStream();ZipOutputStream(out).use {z->files.forEach {(n,b)->z.putNextEntry(ZipEntry(n));z.write(b);z.closeEntry()}};return out.toByteArray()
        }
        val original=entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("schemaVersion",3)},
            {it.getJSONObject("config").getJSONObject("eyeMakeup").put("kind","UNKNOWN")},
            {it.getJSONObject("config").getJSONObject("eyeMakeup").put("mirrorRight",1)},
            {it.getJSONObject("config").getJSONObject("eyeMakeup").getJSONArray("root").remove(8)},
            {it.getJSONObject("config").getJSONObject("eyeMakeup").getJSONArray("root").getJSONArray(1).put(0,0)},
            {it.getJSONObject("config").getJSONObject("eyeMakeup").put("length","0.2")})
        for(change in changes) {
            val root=JSONObject(original);change(root)
            assertThrows(Exception::class.java){EffectResourcePack.loadZip(zip(entries+mapOf("manifest.json" to root.toString().toByteArray())).inputStream())}
        }
        assertThrows(Exception::class.java){EffectResourcePack.loadZip(zip(entries.filterKeys {it!="eyelash.png"}).inputStream())}
        for(color in intArrayOf(0,0xffffffff.toInt())) {
            val image=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(color)
            val out=ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle()
            assertThrows(Exception::class.java){EffectResourcePack.loadZip(zip(entries+mapOf("eyelash.png" to out.toByteArray())).inputStream())}
        }
    }
}
