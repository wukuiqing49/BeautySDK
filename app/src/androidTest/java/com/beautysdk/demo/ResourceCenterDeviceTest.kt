package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.resources.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream

/** Web真实包通过公共库安装/组合；每个用例使用独立缓存目录，不清宿主数据。 */
class ResourceCenterDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun bytes(name:String)=context.assets.open("resource_center/$name").use{it.readBytes()}
    private fun hash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun scoped(test:(BeautyResourceManager,File)->Unit){val dir=File(context.cacheDir,"resource_test_"+java.util.UUID.randomUUID());try{test(BeautyResourceManager(dir),dir)}finally{dir.deleteRecursively()}}
    @Test fun allCurrentWebResourcesInstallAndLoad(){scoped{manager,_->
        val names=BeautyResourceManager.parseCatalog(bytes("catalog.json")).map{it.manifest.id+"_"+it.manifest.revision+".zip"};assertEquals(27,names.size)
        for(name in names){val installed=manager.install(bytes(name).inputStream());manager.acquire(installed.hash).use{lease->val s=lease.resource.applyTo(BeautySettings(),ResourceCapabilities.androidGpuPixel());assertNotNull(s);assertTrue(lease.resource.previewPng().isNotEmpty())}}
        assertEquals(27,manager.list().size)
    }}
    @Test fun eyelashPreservesOtherMakeupAndParameters(){scoped{manager,_->
        val lash=manager.install(bytes("lash_natural_1.zip").inputStream());val lip=manager.install(bytes("lipstick_daily_1.zip").inputStream());val composer=BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel())
        var s=composer.select(BeautyResourceState(parameters=mapOf("beauty.smoothing" to .6f)),lip);s=composer.select(s,lash)
        composer.prepare(s).use{assertEquals(.6f,it.settings.smoothing);assertEquals(.35f,it.settings.adjustments.lipstick);assertNotNull(it.settings.eyeMakeup)}
        composer.prepare(s.copy(selections=s.selections+("makeup.eyelash" to null))).use{assertNull(it.settings.eyeMakeup);assertEquals(.35f,it.settings.adjustments.lipstick)}
    }}
    @Test fun themeMaterializesBeforeSingleSlotEditing(){scoped{manager,_->
        val theme=manager.install(bytes("daily_theme_1.zip").inputStream());val lip=manager.install(bytes("lipstick_daily_1.zip").inputStream());val composer=BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel())
        val next=composer.select(composer.select(BeautyResourceState(),theme),lip)
        assertFalse(next.selections.containsKey("theme"));composer.prepare(next).use{assertNotNull(it.settings.eyeMakeup);assertEquals(.35f,it.settings.smoothing);assertEquals(.35f,it.settings.adjustments.lipstick)}
    }}
    @Test fun defaultAndStateRoundTripRespectExplicitNull(){scoped{_,dir->
        val defaults=BeautyResourceDefaults.parse(bytes("defaults.json"));val user=BeautyResourceState(parameters=mapOf("beauty.smoothing" to .8f),selections=mapOf("makeup.eyelash" to null),enabled=false)
        val store=BeautyResourceStateStore(File(dir,"user-state.json"));store.write(user);assertEquals(user,store.read());val merged=defaults.merge(store.read());assertEquals(.8f,merged.parameters["beauty.smoothing"]);assertFalse(merged.enabled);assertTrue(merged.selections.containsKey("makeup.eyelash"));assertNull(merged.selections["makeup.eyelash"])
        val root=JSONObject(String(bytes("defaults.json")));root.put("enabled","true");assertThrows(Exception::class.java){BeautyResourceDefaults.parse(root.toString().toByteArray())}
    }}
    @Test fun activeLeasePreventsDeletionAndDuplicateInstallReuses(){scoped{manager,_->
        val b=bytes("lash_dense_1.zip");val one=manager.install(b.inputStream());assertEquals(one,manager.install(b.inputStream()));val lease=manager.acquire(one.hash)
        assertThrows(Exception::class.java){manager.uninstall(one.hash)};lease.close();lease.close();manager.uninstall(one.hash);assertTrue(manager.list().isEmpty())
    }}
    @Test fun badDigestCancelledAndPathTraversalNeverInstall(){scoped{manager,_->
        val entries=BeautyResourceManager.parseCatalog(bytes("catalog.json"));val e=entries.first();val b=bytes("lash_natural_1.zip")
        assertThrows(Exception::class.java){manager.install(b.inputStream(),e.copy(sha256="0".repeat(64)))}
        val cancel=ResourceCancellation().apply{cancel()};assertThrows(Exception::class.java){manager.install(b.inputStream(),cancel=cancel)}
        assertThrows(Exception::class.java){ResourceArchive.read(ResourceArchive.write(mapOf("../escape" to byteArrayOf(1))))};assertTrue(manager.list().isEmpty())
    }}
    @Test fun fakeNetworkCatalogCacheAndDownloadUseSameInstaller(){
        val data=bytes("lash_natural_1.zip");val catalog=bytes("catalog.json");var offline=false
        val transport=ResourceTransport {url->if(offline)error("offline");object:ResourceDownload{override val input=ByteArrayInputStream(if(url.endsWith("catalog.json"))catalog else data);override val length=input.available().toLong();override fun close(){input.close()}}}
        val dir=File(context.cacheDir,"network_test_"+java.util.UUID.randomUUID());try{val manager=BeautyResourceManager(dir,transport);val list=manager.catalog("https://resources.example/catalog.json");val entry=list.first{it.manifest.id=="lash_natural"};val installed=manager.download(entry);assertEquals(hash(data),installed.hash);offline=true;assertEquals(list,manager.catalog("https://resources.example/catalog.json"));manager.acquire(installed.hash).close()}finally{dir.deleteRecursively()}
    }
    @Test fun wrongScopeCapabilitiesAndRevisionConflictAreRejected(){scoped{manager,_->
        val b=bytes("lash_natural_1.zip");val one=manager.install(b.inputStream());manager.acquire(one.hash).use{assertThrows(Exception::class.java){it.resource.applyTo(BeautySettings(),ResourceCapabilities(emptySet()))}}
        val files=ResourceArchive.read(b);val m=JSONObject(String(files.getValue("manifest.json")));m.put("name","另一名字");files["manifest.json"]=m.toString().toByteArray();assertThrows(Exception::class.java){manager.install(ResourceArchive.write(files).inputStream())}
        m.getJSONArray("scope").put(0,"beauty.smoothing");files["manifest.json"]=m.toString().toByteArray();assertThrows(Exception::class.java){ResourceArchive.load(ResourceArchive.write(files))}
    }}

    @Test fun glassesMoustacheAndHatComposeInIndependentSlots(){scoped{manager,_->
        val resources=context.assets.list("resource_center")!!.filter{it.endsWith(".zip")}.map{manager.install(bytes(it).inputStream())}
        val composer=BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel())
        var state=BeautyResourceState()
        for(slot in listOf("sticker.glasses","sticker.moustache","sticker.hat"))state=composer.select(state,resources.first{it.descriptor.slot==slot})
        assertEquals(3,state.selections.size)
        composer.prepare(state).use{assertTrue(it.settings.stickerPack!!.layers.size>=3)}
    }}
    @Test fun restartPreservesPresetScopeAndMissingPackageOnlyDisablesItsSlot(){scoped{manager,_->
        val beauty=manager.install(bytes("beauty_daily_1.zip").inputStream())
        val defaults=BeautyResourceDefaults("zh-CN",true,BeautyResourceState(parameters=mapOf("beauty.smoothing" to .9f)),ResourceCategory.entries)
        val saved=BeautyResourceState(selections=mapOf("beauty" to beauty.hash,"makeup.eyelash" to "f".repeat(64)))
        val restored=defaults.merge(saved,installed=manager.list()).resolveInstalled(manager.list())
        assertEquals(setOf("makeup.eyelash"),restored.missingSlots)
        BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel()).prepare(restored.state).use{assertEquals(.35f,it.settings.smoothing)}
        assertEquals("f".repeat(64),saved.selections["makeup.eyelash"])
    }}
}
