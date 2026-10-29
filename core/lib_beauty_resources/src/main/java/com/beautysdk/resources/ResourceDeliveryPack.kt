package com.beautysdk.resources

import java.net.URL

/** 制作端完整交付包，兼容旧平铺包及分类目录清单，不执行网络请求。 */
data class DeliveryGestureResource(val descriptor:LibraryGestureResource,val bytes:ByteArray,val preview:ByteArray)
data class DeliveryResource(val entry:CatalogResource,val bytes:ByteArray)
data class ResourceDeliveryResult(val resources:List<InstalledResource>,val defaults:BeautyResourceDefaults,val defaultsJson:ByteArray,val library:BeautyResourceLibrary?=null,val gestures:List<DeliveryGestureResource> = emptyList())
class ResourceDeliveryPack private constructor(val resources:List<DeliveryResource>,val defaults:BeautyResourceDefaults,val defaultsJson:ByteArray,val library:BeautyResourceLibrary?=null,val gestures:List<DeliveryGestureResource> = emptyList()) {
    companion object {
        fun read(bytes:ByteArray):ResourceDeliveryPack {
            val files=ResourceArchive.readDelivery(bytes)
            val library=files["library.json"]?.let(BeautyResourceLibrary::parse)
            val catalog=BeautyResourceManager.parseUnifiedCatalog(requireNotNull(files["catalog.json"]){"DELIVERY_CATALOG_MISSING"})
            val entries=catalog.resources
            require(entries.size in 1..99){"DELIVERY_RESOURCE_LIMIT"}
            val json=requireNotNull(files["defaults.json"]){"DELIVERY_DEFAULTS_MISSING"}
            val defaults=BeautyResourceDefaults.parse(json)
            val names=mutableSetOf("catalog.json","defaults.json")
            if(library!=null){names.add("library.json");require(library.resources.size==entries.size)}
            val resources=entries.map {entry->
                val stem=entry.manifest.id+"_"+entry.manifest.revision
                val ref=library?.resources?.single{it.id==entry.manifest.id}
                if(ref!=null)require(ref.revision==entry.manifest.revision&&ref.category==entry.manifest.category)
                val zip=ref?.packagePath?:stem+".zip";val png=ref?.preview?:stem+".png"
                require((if(ref==null)URL(entry.url).path.substringAfterLast('/')==zip&&URL(entry.previewUrl).path.substringAfterLast('/')==png else URL(entry.url).toURI().path.endsWith("/"+zip)&&URL(entry.previewUrl).toURI().path.endsWith("/"+png))){"DELIVERY_FILENAME_MISMATCH"}
                require(names.add(zip)&&names.add(png)){"DELIVERY_DUPLICATE"}
                val data=requireNotNull(files[zip]);require(data.size==entry.bytes&&sha256(data)==entry.sha256){"DIGEST_MISMATCH"}
                val resource=ResourceArchive.load(data)
                if(ref!=null){
                    val expanded=ResourceArchive.read(data)
                    require(ref.preview==ref.directory+"preview.png"&&ref.manifest==ref.directory+"manifest.json")
                    for((path,content)in expanded){val target=ref.directory+path;names.add(target);require(files[target]?.contentEquals(content)==true){"DELIVERY_EXPANDED_MISMATCH"}}
                }
                require(resource.descriptor==entry.manifest){"CATALOG_MISMATCH"}
                require(requireNotNull(files[png]).contentEquals(resource.previewPng())){"DELIVERY_PREVIEW_MISMATCH"}
                DeliveryResource(entry,data)
            }
            val gestures=library?.gestureResources.orEmpty().map {ref->
                val data=requireNotNull(files[ref.packagePath]);val expanded=ref.validatePack(data)
                require(names.add(ref.packagePath)&&names.add(ref.preview)){"DELIVERY_DUPLICATE"}
                for((path,content)in expanded){val target=ref.directory+path;require(names.add(target));require(files[target]?.contentEquals(content)==true){"GESTURE_EXPANDED_MISMATCH"}}
                val preview=requireNotNull(files[ref.preview]);require(sha256(preview)==ref.previewSha256){"GESTURE_PREVIEW_MISMATCH"};ResourceArchive.validatePreview(preview)
                DeliveryGestureResource(ref,data,preview)
            }
            if(org.json.JSONObject(files.getValue("catalog.json").toString(Charsets.UTF_8)).getInt("resourceCatalogVersion")==2){
                require(catalog.gestures.size==gestures.size){"GESTURE_CATALOG_MISMATCH"}
                for(entry in catalog.gestures){val ref=requireNotNull(gestures.singleOrNull{it.descriptor.id==entry.descriptor.id}).descriptor
                    require(jsonEquivalent(org.json.JSONObject(ref.json.toString(Charsets.UTF_8)),org.json.JSONObject(entry.descriptor.json.toString(Charsets.UTF_8)))&&URL(entry.url).toURI().path.endsWith("/"+ref.packagePath)&&URL(entry.previewUrl).toURI().path.endsWith("/"+ref.preview)){"GESTURE_CATALOG_MISMATCH"}
                }
            }
            val defaultRules=gestures.filter{it.descriptor.defaultEnabled}.flatMap{com.beautysdk.GestureEffectArchive.loadZip(it.bytes.inputStream()).rules}
            require(defaultRules.size<=7&&defaultRules.map{it.gesture}.toSet().size==defaultRules.size&&defaultRules.map{it.id}.toSet().size==defaultRules.size){"GESTURE_DEFAULT_CONFLICT"}
            require(defaultRules.sumOf{it.image.width.toLong()*it.image.height}<=8388608){"GESTURE_PIXEL_BUDGET"}
            require(files.keys==names){"DELIVERY_EXTRA_FILES"}
            val byHash=entries.associateBy{it.sha256}
            for((slot,hash)in defaults.state.selections)if(hash!=null)require(byHash[hash]?.manifest?.slot==slot){"DELIVERY_DEFAULT_MISSING"}
            require(defaults.state.selections["theme"]==null||defaults.state.selections.count{it.value!=null}==1){"DELIVERY_DEFAULT_CONFLICT"}
            return ResourceDeliveryPack(resources,defaults,json,library,gestures)
        }
    }
}
