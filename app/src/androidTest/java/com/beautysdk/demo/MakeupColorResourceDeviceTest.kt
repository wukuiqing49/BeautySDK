package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.resources.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class MakeupColorResourceDeviceTest {
    @Test fun coloredResourceDeclaresCapabilityAndOnlyChangesItsRegion() {
        val bitmap=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888)
        val png=java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val features=listOf("makeup.parameters.v1","face.landmarks478","makeup.colors.v1")
        val manifest=JSONObject().put("packageVersion",1).put("id","color_test").put("revision",1)
            .put("category","MAKEUP").put("subcategory","LIPSTICK").put("slot","makeup.lipstick")
            .put("defaultLocale","en").put("name","Rose").put("description","").put("localizations",JSONObject())
            .put("preview","preview.png").put("effect","effect.json").put("requirements",JSONObject().put("features",JSONArray(features)))
            .put("scope",JSONArray(listOf("makeup.lipstick")))
        val colors=JSONObject().put("lipstick",JSONObject().put("red",.2).put("green",.3).put("blue",.4))
        val payload=JSONObject().put("parameters",JSONObject().put("makeup.lipstick",.7)).put("colors",colors)
        val effect=JSONObject().put("effectVersion",1).put("effectType","PARAMETERS").put("payload",payload)
        fun archive()=ResourceArchive.write(mapOf("manifest.json" to manifest.toString().toByteArray(),"effect.json" to effect.toString().toByteArray(),"preview.png" to png))
        val prepared=ResourceArchive.load(archive())
        val base=BeautySettings(adjustments=BeautyAdjustments(blusher=.6f,eyelashes=.5f))
        val result=prepared.applyTo(base,ResourceCapabilities.androidGpuPixel())
        assertEquals(MakeupColor(.2f,.3f,.4f),result.adjustments.makeupColors.lipstick)
        assertEquals(base.adjustments.makeupColors.blusher,result.adjustments.makeupColors.blusher)
        assertEquals(.6f,result.adjustments.blusher,0f);assertEquals(.5f,result.adjustments.eyelashes,0f)
        assertThrows(UnsupportedResourceException::class.java){prepared.applyTo(base,ResourceCapabilities(features.toSet()-"makeup.colors.v1"))}
        payload.put("colors",JSONObject().put("blusher",colors.getJSONObject("lipstick")))
        assertThrows(IllegalArgumentException::class.java){ResourceArchive.load(archive())}
    }
    @Test fun versionOneStateMigratesColorsAndRejectsOversizeBeforeParsing() {
        val file=File.createTempFile("makeup-state-",".json",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("""{"stateVersion":1,"parameters":{"makeup.lipstick":0.6},"selections":{},"enabled":true}""")
            val store=BeautyResourceStateStore(file);val old=checkNotNull(store.read())
            assertTrue(old.makeupColors.isEmpty())
            val next=old.copy(makeupColors=mapOf("lipstick" to MakeupColor(.1f,.2f,.3f)))
            store.write(next);assertEquals(next,store.read());assertEquals(2,JSONObject(file.readText()).getInt("stateVersion"))
            file.writeBytes(ByteArray(65537));assertThrows(IllegalArgumentException::class.java){store.read()}
        }finally {file.delete()}
    }
}
