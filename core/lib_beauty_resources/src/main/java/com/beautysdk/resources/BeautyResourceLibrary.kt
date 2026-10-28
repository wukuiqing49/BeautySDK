package com.beautysdk.resources

import org.json.JSONObject
import java.util.Locale

/** JSON驱动的展示与参数定义；执行仍受BeautyParameters白名单约束。 */
data class LibraryParameter(val id:String,val nameKey:String,val min:Float,val max:Float,val step:Float,val default:Float) {
    fun validate(value:Float) { require(value.isFinite()&&value in min..max);BeautyParameters.validate(mapOf(id to value)) }
}
data class LibraryEffect(val id:String,val nameKey:String,val parameters:List<LibraryParameter>)
data class LibraryCategory(val id:ResourceCategory,val nameKey:String,val order:Int,val effects:List<LibraryEffect>)
data class LibraryResource(val id:String,val revision:Int,val category:ResourceCategory,val directory:String,val packagePath:String,val preview:String,val manifest:String,val name:String,val localizations:Map<String,ResourceText>)
class BeautyResourceLibrary private constructor(val defaultLocale:String,val localizations:Map<String,Map<String,String>>,val categories:List<LibraryCategory>,val resources:List<LibraryResource>,val json:ByteArray,val gestureCategory:LibraryGestureCategory?=null,val gestureResources:List<LibraryGestureResource> = emptyList()) {
    fun text(key:String,locale:String):String {
        val tag=Locale.forLanguageTag(locale)
        val candidates=listOf(tag.toLanguageTag(),tag.language,defaultLocale)
        return candidates.firstNotNullOfOrNull { candidate->localizations.entries.firstOrNull{it.key.equals(candidate,true)}?.value?.get(key) }?:error("LIBRARY_TEXT_MISSING: $key")
    }
    fun resourceName(resource:LibraryResource,locale:String):String {
        val tag=Locale.forLanguageTag(locale)
        return listOf(tag.toLanguageTag(),tag.language,defaultLocale).firstNotNullOfOrNull {candidate->resource.localizations.entries.firstOrNull{it.key.equals(candidate,true)}?.value?.name}?:resource.name
    }
    /** 参数修改保留其他槽位与用户值；JSON不能绕过SDK执行白名单。 */
    fun updateParameters(state:BeautyResourceState,values:Map<String,Float>):BeautyResourceState {
        val definitions=categories.flatMap{it.effects}.flatMap{it.parameters}.associateBy{it.id}
        values.forEach{(id,value)->requireNotNull(definitions[id]){"LIBRARY_PARAMETER_MISSING: $id"}.validate(value)}
        return state.copy(parameters=state.parameters+values)
    }
    fun parameters(category:ResourceCategory)=categories.firstOrNull{it.id==category}?.effects.orEmpty().flatMap{it.parameters}
    companion object {
        fun parse(bytes:ByteArray):BeautyResourceLibrary {
            require(bytes.size<=1048576);val o=JSONObject(bytes.toString(Charsets.UTF_8))
            val version=o.integer("libraryVersion",1,2)
            val fields=listOf("libraryVersion","defaultLocale","localeMode","localizations","defaults","categories","resources")
            o.fields(*(fields+if(version==2)listOf("gestureResources")else emptyList()).toTypedArray())
            require(o.text("defaults")=="defaults.json")
            require(o.text("localeMode") in setOf("FOLLOW_HOST","FIXED"))
            val locale=o.text("defaultLocale");val ls=o.getJSONObject("localizations");require(ls.length() in 1..30)
            val translations=ls.keys().asSequence().associateWith {language->
                require(language.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*")))
                val table=ls.getJSONObject(language);require(table.length()<=500)
                table.keys().asSequence().associateWith {key->table.text(key).also{require(it.isNotBlank()&&it.length<=500&&!it.any(Char::isISOControl))}}
            }
            require(locale in translations)
            val keys=mutableSetOf<String>();val parameterIds=mutableSetOf<String>()
            fun label(x:JSONObject):String=x.text("nameKey").also{keys+=it}
            val ca=o.getJSONArray("categories");require(ca.length() in 1..if(version==2)9 else 8)
            val objects=(0 until ca.length()).map{ca.getJSONObject(it)}
            val gestureObjects=objects.filter{it.text("id")=="GESTURE"};require(gestureObjects.size==if(version==2)1 else 0)
            val gestureCategory=gestureObjects.singleOrNull()?.let{g->g.fields("id","nameKey","order","effects");require(g.getJSONArray("effects").length()==0);LibraryGestureCategory(label(g),g.integer("order",0,10000))}
            val categories=objects.filter{it.text("id")!="GESTURE"}.map {c->c.fields("id","nameKey","order","effects")
                val id=ResourceCategory.valueOf(c.text("id"));val label=label(c);val effects=c.getJSONArray("effects");require(effects.length()<=50)
                LibraryCategory(id,label,c.integer("order",0,10000),(0 until effects.length()).map {j->val e=effects.getJSONObject(j);e.fields("id","nameKey","parameters")
                    val effectId=e.text("id");require(effectId in BeautyParameters.neutral&&BeautyParameters.category(effectId)==id)
                    val pa=e.getJSONArray("parameters");require(pa.length()==1)
                    LibraryEffect(effectId,label(e),(0 until pa.length()).map {k->val p=pa.getJSONObject(k);p.fields("id","nameKey","type","control","min","max","step","default")
                        require(p.text("type")=="float"&&p.text("control")=="slider"&&p.text("id")==effectId&&parameterIds.add(effectId))
                        fun number(key:String):Float {val v=p.get(key);require(v is Number);return v.toFloat().also{require(it.isFinite())}}
                        val a=LibraryParameter(effectId,label(p),number("min"),number("max"),number("step"),number("default"))
                        require(a.min<a.max&&a.step>0&&(a.max-a.min)/a.step<=10000);a.validate(a.min);a.validate(a.max);a.validate(a.default);a
                    })
                })
            }
            require(categories.map{it.id}.toSet().size==categories.size&&keys.all{it in translations.getValue(locale)})
            val rs=o.getJSONArray("resources");require(rs.length() in 1..99)
            val resources=(0 until rs.length()).map {i->val r=rs.getJSONObject(i)
                r.fields("id","revision","category","subcategory","name","localizations","directory","package","preview","manifest")
                val dir=r.text("directory");require(dir.endsWith('/')&&safeLibraryPath(dir+"x"))
                fun path(key:String)=r.text(key).also{require(safeLibraryPath(it)&&it.startsWith(dir)&&!it.removePrefix(dir).contains('/'))}
                val displayName=r.text("name");require(displayName.isNotBlank()&&displayName.length<=80&&!displayName.any(Char::isISOControl))
                val rt=r.getJSONObject("localizations");require(rt.length()<=30)
                val resourceTexts=rt.keys().asSequence().associateWith {tag->
                    require(tag.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*")))
                    val t=rt.getJSONObject(tag);t.fields("name","description");val name=t.text("name");val description=t.text("description")
                    require(name.isNotBlank()&&name.length<=80&&description.length<=500&&!(name+description).any(Char::isISOControl));ResourceText(name,description)
                }
                LibraryResource(r.text("id").also{require(validId(it))},r.integer("revision",1,Int.MAX_VALUE),ResourceCategory.valueOf(r.text("category")),dir,path("package"),path("preview"),path("manifest"),displayName,resourceTexts)
            }
            require(resources.map{it.id}.toSet().size==resources.size&&resources.map{it.directory.lowercase(Locale.ROOT)}.toSet().size==resources.size)
            require(resources.all{r->categories.any{it.id==r.category}})
            val gestures=if(version==2)o.getJSONArray("gestureResources").let{a->require(a.length() in 1..8);(0 until a.length()).map{LibraryGestureResource.parse(a.getJSONObject(it))}}else emptyList()
            val allIds=resources.map{it.id}+gestures.map{it.id};require(allIds.toSet().size==allIds.size)
            val directories=resources.map{it.directory}+gestures.map{it.directory};require(directories.map{it.lowercase(Locale.ROOT)}.toSet().size==directories.size)
            return BeautyResourceLibrary(locale,translations,categories.sortedBy{it.order},resources,bytes.copyOf(),gestureCategory,gestures)
        }
    }
}
internal fun safeLibraryPath(path:String):Boolean=path.length<=320&&!path.any{it.isISOControl()||it in "\\<>:\"|?*"}&&path.split('/').all{it.isNotEmpty()&&it!="."&&it!=".."&&!it.endsWith('.')&&!it.endsWith(' ')}
