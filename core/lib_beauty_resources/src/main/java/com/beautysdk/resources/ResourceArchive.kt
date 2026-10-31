package com.beautysdk.resources

import com.beautysdk.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

/** 统一JSON严格读取；拒绝伪数字、未知字段及隐式类型转换。 */
internal fun JSONObject.fields(vararg names:String) { require(keys().asSequence().toSet()==names.toSet()) {"Unknown or missing fields"} }
internal fun JSONObject.text(key:String)=get(key).also {require(it is String)} as String
internal fun JSONObject.integer(key:String,min:Int,max:Int):Int {val n=get(key);require(n is Number);val d=n.toDouble();require(d.isFinite()&&d%1==0.0&&d in min.toDouble()..max.toDouble());return d.toInt()}
internal fun JSONObject.strings(key:String):List<String> {val a=getJSONArray(key);return (0 until a.length()).map {a.get(it).also {v->require(v is String)} as String}.also {require(it.toSet().size==it.size)} }
internal fun validId(s:String)=s.matches(Regex("[a-z][a-z0-9_]{0,47}"))
internal fun parseParameters(o:JSONObject):Map<String,Float> = o.keys().asSequence().associateWith {key->val v=o.get(key);require(v is Number);v.toFloat()}.also(BeautyParameters::validate)
internal fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
internal fun bounded(input:InputStream,max:Int,cancel:()->Unit={}):ByteArray {val out=ByteArrayOutputStream();val b=ByteArray(8192);while(true){cancel();val n=input.read(b);if(n<0)break;require(out.size().toLong()+n<=max);out.write(b,0,n)};return out.toByteArray()}
/** 固定一层主题、严格根路径，无任意解压路径。最大32MiB，最多40文件，单图4MiB。 */
object ResourceArchive {
    fun read(bytes:ByteArray):LinkedHashMap<String,ByteArray> = readEntries(bytes,40,65536,16777216)
    /** 完整交付包单独限额，单项/套装原校验规则不放宽。 */
    internal fun readDelivery(bytes:ByteArray):LinkedHashMap<String,ByteArray> {
        val files=readEntries(bytes,2000,1048576,33554432,true);val prefix="beauty_resources/"
        if(files.keys.none{it.startsWith(prefix)})return files
        require(files.keys.all{it.startsWith(prefix)}){"DELIVERY_MIXED_ROOT"}
        return files.entries.associateTo(linkedMapOf()){it.key.removePrefix(prefix) to it.value}
    }
    private fun readEntries(bytes:ByteArray,maxFiles:Int,maxJson:Int,maxNested:Int,delivery:Boolean=false):LinkedHashMap<String,ByteArray> {
        require(bytes.size<=33554432);val files=linkedMapOf<String,ByteArray>();var total=0L
        ZipInputStream(bytes.inputStream()).use {z->while(true){val e=z.nextEntry?:break
            require(!e.isDirectory&&(if(delivery)safeLibraryPath(e.name) else e.name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")))&&e.name !in files&&files.keys.none {it.equals(e.name,true)}&&files.size<maxFiles)
            val limit=if(e.name.endsWith(".png"))4194304 else if(e.name.endsWith(".json"))maxJson else maxNested
            val data=bounded(z,limit);total+=data.size;require(total<=33554432);files[e.name]=data;z.closeEntry()
        }};return files
    }
    fun write(files:Map<String,ByteArray>):ByteArray {val out=ByteArrayOutputStream();ZipOutputStream(out).use {z->files.forEach {(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}};return out.toByteArray()}
    /** 资源声明必须与执行内容一致，不能用标签把不支持能力伪装成可用。 */
    fun descriptor(o:JSONObject):ResourceDescriptor {
        o.fields("packageVersion","id","revision","category","subcategory","slot","defaultLocale","name","description","localizations","preview","effect","requirements","scope")
        require(o.integer("packageVersion",1,1)==1);val id=o.text("id");require(validId(id))
        val category=ResourceCategory.valueOf(o.text("category"));val sub=o.text("subcategory");val slot=o.text("slot")
        require(sub.matches(Regex("[A-Z_]{1,32}"))&&slot.matches(Regex("[a-z][a-z0-9_.]{0,63}")))
        require(o.text("preview")=="preview.png"&&o.text("effect")=="effect.json")
        fun text(x:JSONObject):ResourceText {val name=x.text("name");val desc=x.text("description");require(name.isNotBlank()&&name.length<=80&&desc.length<=500&&!(name+desc).any {it.isISOControl()});return ResourceText(name,desc)}
        val locale=o.text("defaultLocale");require(locale.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*")))
        val ls=o.getJSONObject("localizations");require(ls.length()<=30)
        val translations=ls.keys().asSequence().associateWith {key->require(key.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*")));ls.getJSONObject(key).also {it.fields("name","description")}.let(::text)}
        val req=o.getJSONObject("requirements");req.fields("features");val features=req.strings("features").toSet();require(features.size<=20)
        val scope=o.strings("scope").toSet();require(scope.all {it in BeautyParameters.neutral})
        require(category!=ResourceCategory.MAKEUP || sub in setOf("LIPSTICK","BLUSHER","EYELASH")) {"Unsupported makeup type"}
        if(category==ResourceCategory.MAKEUP)require(scope==setOf("makeup."+if(sub=="EYELASH")"eyelashes" else sub.lowercase(java.util.Locale.ROOT)))
        if(sub!="LEGACY")require(scope.all {BeautyParameters.category(it)==category})
        val expectedSlot=when(category){ResourceCategory.MAKEUP->"makeup."+sub.lowercase(java.util.Locale.ROOT);ResourceCategory.STICKER->"sticker."+sub.lowercase(java.util.Locale.ROOT);else->category.name.lowercase(java.util.Locale.ROOT)}
        require(slot==expectedSlot)
        return ResourceDescriptor(id,o.integer("revision",1,Int.MAX_VALUE),category,sub,slot,locale,text(o),translations.toMap(),features,scope)
    }
    fun load(bytes:ByteArray):PreparedResource=loadInternal(bytes,false)
    private fun loadInternal(bytes:ByteArray,nested:Boolean):PreparedResource {
        val files=read(bytes);val m=JSONObject(requireNotNull(files["manifest.json"]).toString(Charsets.UTF_8));val d=descriptor(m)
        val preview=requireNotNull(files["preview.png"]);StickerResourcePack.validateStaticPng(preview);pngPixels(preview)
        val e=JSONObject(requireNotNull(files["effect.json"]).toString(Charsets.UTF_8))
        e.fields("effectVersion","effectType","payload");e.integer("effectVersion",1,1)
        val kind=e.text("effectType");val payload=e.getJSONObject("payload")
        if(kind=="THEME") {
            require(d.category==ResourceCategory.THEME&&!nested&&d.scope.isEmpty());payload.fields("resources");val refs=payload.getJSONArray("resources");require(refs.length() in 1..20)
            val names=mutableSetOf("manifest.json","preview.png","effect.json");var pixels=pngPixels(preview)
            val children=(0 until refs.length()).map {i->val r=refs.getJSONObject(i);r.fields("file","sha256");val name=r.text("file");require(name.matches(Regex("resource_[0-9]+\\.zip"))&&names.add(name));val data=requireNotNull(files[name]);require(sha256(data)==r.text("sha256"));val childFiles=read(data);for((path,img)in childFiles)if(path.endsWith(".png")){val size=pngPixels(img);pixels+=size;require(pixels<=8388608)};loadInternal(data,true)}
            require(files.keys==names&&children.map{it.descriptor.slot}.toSet().size==children.size)
            require(d.features==children.flatMap {it.descriptor.features}.toSet()+"theme.v1")
            return PreparedResource(d,preview,emptyMap(),null,children)
        }
        if(kind=="PARAMETERS") {
            val colors=if(payload.has("colors"))MakeupColorOverrides.read(payload.getJSONObject("colors"))else emptyMap()
            payload.fields(*if(payload.has("colors"))arrayOf("parameters","colors")else arrayOf("parameters"))
            require(files.keys==setOf("manifest.json","preview.png","effect.json"))
            val values=parseParameters(payload.getJSONObject("parameters"))
            require(values.keys==d.scope&&d.category in setOf(ResourceCategory.BEAUTY,ResourceCategory.RESHAPE,ResourceCategory.MAKEUP,ResourceCategory.FILTER))
            if(colors.isNotEmpty())require(d.category==ResourceCategory.MAKEUP&&colors.keys==setOf(d.subcategory.lowercase(java.util.Locale.ROOT)))
            require(d.features==featuresFor(d.category,values.keys,false)+(if(colors.isNotEmpty())setOf("makeup.colors.v1")else emptySet()))
            return PreparedResource(d,preview,values,null,colors=colors)
        }
        require(kind=="LEGACY");val legacy=linkedMapOf("manifest.json" to payload.toString().toByteArray())
        files.filterKeys {it !in setOf("manifest.json","effect.json")}.forEach {(k,v)->legacy[k]=v}
        val oldBytes=write(legacy)
        val settings=if(d.category==ResourceCategory.STICKER) {require(payload.integer("schemaVersion",1,1)==1);BeautySettings(stickerPack=StickerResourcePack.loadZip(oldBytes.inputStream()).toStickerPack())}
            else EffectResourcePack.loadZip(oldBytes.inputStream()).applyTo(BeautySettings())
        val type=payload.optString("type")
        require(when(d.category){ResourceCategory.STICKER->type=="";ResourceCategory.BACKGROUND->type=="BACKGROUND";ResourceCategory.MOTION->type=="MOTION";else->type=="BEAUTY"})
        val values=BeautyParameters.read(settings).filterKeys {it in d.scope}
        if(d.category==ResourceCategory.MAKEUP&&d.subcategory=="EYELASH")require(settings.eyeMakeup!=null&&d.scope==setOf("makeup.eyelashes"))
        require(d.features==featuresFor(d.category,d.scope,settings.eyeMakeup!=null))
        return PreparedResource(d,preview,values,settings)
    }
    fun featuresFor(category:ResourceCategory,scope:Set<String>,eye:Boolean):Set<String> {
        val features=mutableSetOf<String>()
        if(category==ResourceCategory.STICKER)features+=setOf("sticker.png.v1","face.landmarks478")
        if(category==ResourceCategory.BACKGROUND)features+=setOf("background.v1","segmentation")
        if(category==ResourceCategory.MOTION)features+=setOf("motion.v1","face.landmarks478")
        if(category==ResourceCategory.FILTER)features+="filter.basic.v1"
        for(k in scope)when(BeautyParameters.category(k)){
            ResourceCategory.BEAUTY->features+="beauty.parameters.v1"
            ResourceCategory.FILTER->features+="filter.basic.v1"
            ResourceCategory.RESHAPE->features+=setOf("reshape.parameters.v1","face.landmarks478")
            ResourceCategory.MAKEUP->features+=setOf("makeup.parameters.v1","face.landmarks478")
            else->Unit
        }
        if(eye)features+="makeup.eyelash.texture.v1"
        return features
    }
    fun validatePreview(data:ByteArray){com.beautysdk.StickerResourcePack.validateStaticPng(data);pngPixels(data)}
    private fun pngPixels(data:ByteArray):Long {require(data.size>=24);val b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN);val w=b.getInt(16);val h=b.getInt(20);require(w in 1..2048&&h in 1..2048);return w.toLong()*h}
}
