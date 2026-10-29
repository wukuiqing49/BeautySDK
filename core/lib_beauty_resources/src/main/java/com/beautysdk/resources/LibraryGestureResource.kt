package com.beautysdk.resources

import com.beautysdk.GestureEffectArchive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 手势素材独立于BeautySettings槽位；预览、默认启用和绑定规则随总清单交付。 */
data class LibraryGestureCategory(val nameKey:String,val order:Int)
class LibraryGestureResource private constructor(val id:String,val revision:Int,val name:String,val localizations:Map<String,ResourceText>,val directory:String,val packagePath:String,val preview:String,val manifest:String,val sha256:String,val bytes:Int,val previewSha256:String,val defaultEnabled:Boolean,val rulesJson:String,val json:ByteArray) {
    fun localizedName(locale:String,defaultLocale:String="zh-CN"):String {
        return resourceLocaleCandidates(locale,defaultLocale).firstNotNullOfOrNull{candidate->localizations.entries.firstOrNull{it.key.equals(candidate,true)}?.value?.name}?:name
    }
    internal fun validatePack(data:ByteArray):Map<String,ByteArray> {
        require(data.size==bytes&&sha256(data)==sha256){"GESTURE_DIGEST_MISMATCH"}
        GestureEffectArchive.loadZip(data.inputStream())
        val entries=ResourceArchive.read(data)
        val actual=JSONObject(requireNotNull(entries["gestures.json"]).toString(Charsets.UTF_8)).getJSONArray("rules")
        require(jsonEquivalent(actual,JSONArray(rulesJson))){"GESTURE_RULES_MISMATCH"}
        return entries
    }
    companion object {
        fun parse(bytes:ByteArray):LibraryGestureResource=parse(JSONObject(bytes.toString(Charsets.UTF_8)))
        internal fun parse(o:JSONObject):LibraryGestureResource {
            o.fields("id","revision","category","name","localizations","directory","package","preview","manifest","sha256","bytes","previewSha256","defaultEnabled","rules")
            require(o.text("category")=="GESTURE")
            val id=o.text("id");require(validId(id));val name=o.text("name");require(name.isNotBlank()&&name.length<=80&&!name.any(Char::isISOControl))
            val dir=o.text("directory");require(dir.endsWith('/')&&safeLibraryPath(dir+"x"))
            fun path(k:String)=o.text(k).also{require(safeLibraryPath(it)&&it.startsWith(dir)&&!it.removePrefix(dir).contains('/'))}
            val preview=path("preview");val manifest=path("manifest");require(preview==dir+"preview.png"&&manifest==dir+"gestures.json")
            val translations=o.getJSONObject("localizations");require(translations.length()<=30)
            val texts=translations.keys().asSequence().associateWith {tag->
                require(tag.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*")))
                val t=translations.getJSONObject(tag);t.fields("name","description");val n=t.text("name");val d=t.text("description")
                require(n.isNotBlank()&&n.length<=80&&d.length<=500&&!(n+d).any(Char::isISOControl));ResourceText(n,d)
            }
            fun hash(k:String)=o.text(k).also{require(it.matches(Regex("[0-9a-f]{64}")))}
            val enabled=o.get("defaultEnabled");require(enabled is Boolean)
            val rules=o.getJSONArray("rules");require(rules.length() in 1..7&&rules.toString().length<=65536)
            return LibraryGestureResource(id,o.integer("revision",1,Int.MAX_VALUE),name,texts,dir,path("package"),preview,manifest,hash("sha256"),o.integer("bytes",1,33554432),hash("previewSha256"),enabled,rules.toString(),o.toString().toByteArray())
        }
    }
}
internal fun jsonEquivalent(a:Any?,b:Any?):Boolean=when {
    a is JSONObject&&b is JSONObject->a.keys().asSequence().toSet()==b.keys().asSequence().toSet()&&a.keys().asSequence().all{jsonEquivalent(a.get(it),b.get(it))}
    a is JSONArray&&b is JSONArray->a.length()==b.length()&&(0 until a.length()).all{jsonEquivalent(a.get(it),b.get(it))}
    a is Number&&b is Number->a.toDouble()==b.toDouble()
    else->a==b
}
