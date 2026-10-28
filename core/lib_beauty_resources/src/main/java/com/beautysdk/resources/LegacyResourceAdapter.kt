package com.beautysdk.resources

import org.json.JSONObject
import org.json.JSONArray

/** 旧V1～V4资源和主题V1转换为统一外层；只在导入阶段执行，旧执行payload保持原语义。 */
object LegacyResourceAdapter {
    fun upgrade(bytes:ByteArray):ByteArray {
        val files=ResourceArchive.read(bytes)
        val manifest=files["manifest.json"]?.let {JSONObject(it.toString(Charsets.UTF_8))}
        if(manifest?.has("packageVersion")==true)return bytes
        if(files.containsKey("bundle.json")) {
            com.beautysdk.BeautyThemePack.loadZip(bytes.inputStream())
            val old=JSONObject(files.getValue("bundle.json").toString(Charsets.UTF_8));val refs=old.getJSONObject("resources")
            val children=listOf("sticker","background","motion","beauty").filter{!refs.isNull(it)}.map{upgrade(files.getValue(refs.getString(it)))}
            val descriptors=children.map{ResourceArchive.load(it).descriptor}
            val d=manifest("legacy_"+old.getString("id").take(41),old.getString("name"),ResourceCategory.THEME,"PRESET",emptySet(),descriptors.flatMap{it.features}.toSet()+"theme.v1")
            val entries=linkedMapOf("manifest.json" to d.toString().toByteArray(),"preview.png" to files.getValue("preview.png"))
            val items=JSONArray();children.forEachIndexed{i,b->val name="resource_$i.zip";entries[name]=b;items.put(JSONObject().put("file",name).put("sha256",sha256(b)))}
            entries["effect.json"]=JSONObject().put("effectVersion",1).put("effectType","THEME").put("payload",JSONObject().put("resources",items)).toString().toByteArray()
            return ResourceArchive.write(entries).also{ResourceArchive.load(it)}
        }
        val old=requireNotNull(manifest){"Missing manifest"};val version=old.getInt("schemaVersion")
        val category=if(version==1)ResourceCategory.STICKER else if(version==4)ResourceCategory.MAKEUP else ResourceCategory.valueOf(old.getString("type"))
        val sub=if(version==4)"EYELASH" else if(category==ResourceCategory.BEAUTY)"LEGACY" else "PRESET"
        val scope=if(version==4)setOf("makeup.eyelashes")else if(category==ResourceCategory.BEAUTY)BeautyParameters.neutral.keys else emptySet()
        val d=manifest("legacy_"+old.getString("id").take(41),old.getString("name"),category,sub,scope,ResourceArchive.featuresFor(category,scope,version==4))
        val entries=LinkedHashMap(files);entries["manifest.json"]=d.toString().toByteArray();entries["effect.json"]=JSONObject().put("effectVersion",1).put("effectType","LEGACY").put("payload",old).toString().toByteArray()
        return ResourceArchive.write(entries).also{ResourceArchive.load(it)}
    }
    private fun manifest(id:String,name:String,category:ResourceCategory,sub:String,scope:Set<String>,features:Set<String>)=JSONObject()
        .put("packageVersion",1).put("id",id).put("revision",1).put("category",category.name).put("subcategory",sub)
        .put("slot",category.name.lowercase(java.util.Locale.ROOT)+if(category in setOf(ResourceCategory.MAKEUP,ResourceCategory.STICKER))"."+sub.lowercase(java.util.Locale.ROOT) else "")
        .put("defaultLocale","zh-CN").put("name",name).put("description","").put("localizations",JSONObject())
        .put("preview","preview.png").put("effect","effect.json").put("requirements",JSONObject().put("features",JSONArray(features.sorted()))).put("scope",JSONArray(scope.toList()))
}
