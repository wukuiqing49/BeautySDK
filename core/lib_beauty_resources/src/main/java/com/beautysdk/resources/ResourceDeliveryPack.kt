package com.beautysdk.resources

import java.net.URL

/** 制作端完整交付包，只接受扁平白名单文件，不执行网络请求。 */
data class DeliveryResource(val entry:CatalogResource,val bytes:ByteArray)
data class ResourceDeliveryResult(val resources:List<InstalledResource>,val defaults:BeautyResourceDefaults,val defaultsJson:ByteArray)
class ResourceDeliveryPack private constructor(val resources:List<DeliveryResource>,val defaults:BeautyResourceDefaults,val defaultsJson:ByteArray) {
    companion object {
        fun read(bytes:ByteArray):ResourceDeliveryPack {
            val files=ResourceArchive.readDelivery(bytes)
            val entries=BeautyResourceManager.parseCatalog(requireNotNull(files["catalog.json"]){"DELIVERY_CATALOG_MISSING"})
            require(entries.size in 1..99){"DELIVERY_RESOURCE_LIMIT"}
            val json=requireNotNull(files["defaults.json"]){"DELIVERY_DEFAULTS_MISSING"}
            val defaults=BeautyResourceDefaults.parse(json)
            val names=mutableSetOf("catalog.json","defaults.json")
            val resources=entries.map {entry->
                val stem=entry.manifest.id+"_"+entry.manifest.revision
                val zip=stem+".zip";val png=stem+".png"
                require(URL(entry.url).path.substringAfterLast('/')==zip&&URL(entry.previewUrl).path.substringAfterLast('/')==png){"DELIVERY_FILENAME_MISMATCH"}
                require(names.add(zip)&&names.add(png)){"DELIVERY_DUPLICATE"}
                val data=requireNotNull(files[zip]);require(data.size==entry.bytes&&sha256(data)==entry.sha256){"DIGEST_MISMATCH"}
                val resource=ResourceArchive.load(data)
                require(resource.descriptor==entry.manifest){"CATALOG_MISMATCH"}
                require(requireNotNull(files[png]).contentEquals(resource.previewPng())){"DELIVERY_PREVIEW_MISMATCH"}
                DeliveryResource(entry,data)
            }
            require(files.keys==names){"DELIVERY_EXTRA_FILES"}
            val byHash=entries.associateBy{it.sha256}
            for((slot,hash)in defaults.state.selections)if(hash!=null)require(byHash[hash]?.manifest?.slot==slot){"DELIVERY_DEFAULT_MISSING"}
            require(defaults.state.selections["theme"]==null||defaults.state.selections.count{it.value!=null}==1){"DELIVERY_DEFAULT_CONFLICT"}
            return ResourceDeliveryPack(resources,defaults,json)
        }
    }
}
