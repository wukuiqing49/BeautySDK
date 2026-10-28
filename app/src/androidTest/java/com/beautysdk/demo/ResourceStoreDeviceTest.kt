package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 使用隔离缓存与可注入传输验证下载事务；不访问网络，不影响用户已选资源。 */
class ResourceStoreDeviceTest {
    @Test fun verifiedCacheWorksOfflineAndRejectsBadUpdates() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val bytes=inst.context.assets.open("effect_packs/beauty_v3.zip").use { it.readBytes() }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entry=BeautyResourceEntry("beauty_test","美妆","1","BEAUTY","https://example.com/beauty.zip",bytes.size.toLong(),hash)
        val folder=File(inst.targetContext.cacheDir,"resource-store-test-${System.nanoTime()}")
        var online=true;var reads=0
        val catalog="""{"catalogVersion":1,"resources":[{"id":"beauty_test","name":"美妆","version":"1","type":"BEAUTY","url":"https://example.com/beauty.zip","bytes":${bytes.size},"sha256":"$hash"}]}""".toByteArray()
        val store=BeautyResourceStore(folder,BeautyResourceTransport { url,_ -> reads++;check(online);if(url.endsWith("json"))catalog else bytes })
        try {
            assertEquals(entry,store.catalog("https://example.com/catalog.json").single())
            val cache=store.obtain(entry) { assertEquals(EffectResourceType.BEAUTY,EffectResourcePack.loadZip(it).type) }
            online=false
            assertEquals(entry,store.catalog("https://example.com/catalog.json").single())
            val before=reads
            assertEquals(cache,store.obtain(entry) { EffectResourcePack.loadZip(it) });assertEquals(before,reads)
            assertThrows(Exception::class.java) { store.obtain(entry.copy(sha256="0".repeat(64))) { EffectResourcePack.loadZip(it) } }
            assertTrue(cache.exists())
            assertThrows(IllegalArgumentException::class.java) { store.obtain(entry.copy(sha256="../bad")) {} }
            val bad=BeautyResourceStore(File(folder,"bad"),BeautyResourceTransport { _,_->bytes })
            assertThrows(IllegalArgumentException::class.java) { bad.obtain(entry.copy(sha256="0".repeat(64))) {} }
            assertNull(bad.cached(entry))
            assertThrows(IllegalStateException::class.java) { bad.obtain(entry) { error("unsupported runtime") } }
            assertNull(bad.cached(entry))
        } finally { folder.deleteRecursively() }
    }
    @Test fun catalogRejectsUnsafeUrlsVersionsAndFalseNumbers() {
        val minimal="""{"catalogVersion":1,"resources":[]}"""
        assertTrue(BeautyResourceStore.parseCatalog(minimal.toByteArray()).isEmpty())
        for(bad in listOf(minimal.replace(":1",":true"),minimal.replace(":1",":2"),minimal.replace("[]","null")))
            assertThrows(Exception::class.java) { BeautyResourceStore.parseCatalog(bad.toByteArray()) }
    }
}
