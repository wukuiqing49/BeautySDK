package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ResourceContractDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    @Test fun explicitDefaultsControlOrderLanguageAndParameters() {
        val context=inst.targetContext
        val activity=inst.startActivitySync(android.content.Intent(context,ResourceCenterActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity
        lateinit var model:ResourceCenterModel
        val file=File(context.cacheDir,"ordered-contract.zip")
        fun awaitUi(check:(ResourceCenterModel.Ui)->Boolean){val deadline=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<deadline){var ready=false;inst.runOnMainSync{model.ui.value?.let{assertNull(it.error);ready=!it.busy&&it.settings!=null&&check(it)}};if(ready)return;Thread.sleep(25)};error("Resource UI timeout")}
        try {
            inst.runOnMainSync{model=androidx.lifecycle.ViewModelProvider(activity)[ResourceCenterModel::class.java]};awaitUi{true}
            file.writeBytes(inst.context.assets.open("ordered_delivery.zip").use{it.readBytes()})
            inst.runOnMainSync{model.import(android.net.Uri.fromFile(file))}
            awaitUi{it.library?.defaultLocale=="en"&&it.deliveryDefaultsAvailable}
            inst.runOnMainSync{model.applyDeliveryDefaults()}
            awaitUi{it.locale=="en"&&it.order.first()==ResourceCategory.THEME&&it.settings?.smoothing==.47f}
            // 模拟另一资源页改变默认配置；存活页面refresh必须重读语言/顺序，用户数值仍保留。
            File(context.filesDir,"beauty_resources/active-defaults.json").writeBytes(context.assets.open("resource_center/defaults.json").use{it.readBytes()})
            inst.runOnMainSync{model.refresh()}
            awaitUi{it.locale==null&&it.order.first()==ResourceCategory.BEAUTY&&it.settings?.smoothing==.47f}
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync();file.delete()}
    }
    @Test fun sameLibraryCasesAsWeb() {
        val fixture=JSONObject(inst.context.assets.open("library-cases.json").bufferedReader().use{it.readText()})
        val cases=fixture.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val json=JSONObject(fixture.getJSONObject("base").toString())
            val changes=case.getJSONArray("changes")
            for(j in 0 until changes.length()) {
                val change=changes.getJSONArray(j);var target:Any=json
                for(k in 0 until change.length()-2)target=if(target is JSONObject)target.get(change.getString(k))else (target as JSONArray).get(change.getInt(k))
                val value=change.get(change.length()-1);val key=change.get(change.length()-2)
                if(target is JSONObject)target.put(key.toString(),value)else (target as JSONArray).put((key as Number).toInt(),value)
            }
            val result=runCatching{BeautyResourceLibrary.parse(json.toString().toByteArray())}
            assertEquals(case.getString("id")+": "+result.exceptionOrNull(),case.getBoolean("accepted"),result.isSuccess)
        }
        val library=BeautyResourceLibrary.parse(fixture.getJSONObject("base").toString().toByteArray())
        assertEquals(ResourceCategory.THEME,library.categories.first().id)
        assertEquals("Beauty",library.text("category.BEAUTY","fr-FR"))
    }
    @Test fun onlineGesturesShareValidationAndDoNotAutoEnable() {
        val bytes=inst.targetContext.assets.open("resource_delivery.zip").use{it.readBytes()}
        val pack=ResourceDeliveryPack.read(bytes)
        val entries=linkedMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name.removePrefix("beauty_resources/")]=zip.readBytes()}}
        val catalog=BeautyResourceManager.parseUnifiedCatalog(entries.getValue("catalog.json"))
        assertEquals(4,catalog.gestures.size)
        val requested=mutableListOf<String>()
        val root=File(inst.targetContext.cacheDir,"network-contract-"+System.nanoTime())
        var tamper=false
        val transport=ResourceTransport {url->
            requested+=url
            val entry=catalog.gestures.single{it.url==url||it.previewUrl==url}
            val data=entries.getValue(if(url==entry.url)entry.descriptor.packagePath else entry.descriptor.preview).copyOf()
            if(tamper&&url==entry.url)data[data.lastIndex]=(data.last().toInt() xor 1).toByte()
            object:ResourceDownload {override val input=data.inputStream();override val length=data.size.toLong();override fun close(){input.close()}}
        }
        try {
            val manager=BeautyResourceManager(root,transport)
            val first=manager.downloadGesture(catalog.gestures.first())
            assertEquals(first.hash,manager.downloadGesture(catalog.gestures.first()).hash)
            assertEquals(1,manager.listGestureResources().size)
            assertNull(manager.prepareGestureSelection(emptyList()))
            assertNotNull(manager.prepareGestureSelection(listOf(first.hash)))
            tamper=true
            assertTrue(runCatching{manager.downloadGesture(catalog.gestures[1])}.isFailure)
            assertEquals(1,manager.listGestureResources().size)
            assertEquals(50,pack.resources.size)
            assertTrue(requested.isNotEmpty())
        }finally{root.deleteRecursively()}
    }
}
