package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** 真实HTTPS目录联调，使用隔离缓存，不改变用户资源或选择。网络失败单独报告。 */
class PublishedCatalogDeviceTest {
 @Test fun publishedCatalogDownloadsAndLoadsVerifiedResource(){
  val inst=InstrumentationRegistry.getInstrumentation();val folder=File(inst.targetContext.cacheDir,"published_"+java.util.UUID.randomUUID())
  try {val manager=BeautyResourceManager(folder)
   val entries=manager.catalog("https://beautystickerworkshop.pages.dev/resources/catalog.json")
   assertTrue(entries.isNotEmpty());val entry=entries.first{it.manifest.category==ResourceCategory.BEAUTY}
   assertTrue(manager.preview(entry).isNotEmpty());val installed=manager.download(entry);assertEquals(entry.sha256,installed.hash)
   manager.acquire(installed.hash).use{assertNotNull(it.resource.applyTo(com.beautysdk.BeautySettings(),ResourceCapabilities.androidGpuPixel()))}
   inst.sendStatus(0,android.os.Bundle().apply{putString("stream","Published HTTPS catalog resources=${entries.size}; verified download and load succeeded.\n")})
  } finally {folder.deleteRecursively()}
 }
}
