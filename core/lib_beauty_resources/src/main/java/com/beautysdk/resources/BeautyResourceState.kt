package com.beautysdk.resources

import com.beautysdk.*
import org.json.JSONObject
import java.io.File

/** 用户保存显式null表示关闭；只有用户更改过的字段覆盖默认值。 */
data class BeautyResourceState(val parameters:Map<String,Float> = emptyMap(),val selections:Map<String,String?> = emptyMap(),val enabled:Boolean=true,val makeupColors:Map<String,MakeupColor>) {
    constructor():this(emptyMap(),emptyMap(),true,emptyMap())
    constructor(parameters:Map<String,Float> = emptyMap(),selections:Map<String,String?> = emptyMap(),enabled:Boolean=true):this(parameters,selections,enabled,emptyMap())
    fun copy(parameters:Map<String,Float> = this.parameters,selections:Map<String,String?> = this.selections,enabled:Boolean=this.enabled):BeautyResourceState =
        BeautyResourceState(parameters,selections,enabled,makeupColors)
}
data class BeautyResourceDefaults(val locale:String,val followHost:Boolean,val state:BeautyResourceState,val categoryOrder:List<ResourceCategory>) {
    fun merge(saved:BeautyResourceState?,host:BeautyResourceState?=null,installed:List<InstalledResource> = emptyList()):BeautyResourceState {
        var s=state
        for(next in listOfNotNull(saved,host)) {
            val chosen=next.selections.values.filterNotNull().toSet()
            val descriptors=installed.filter{it.hash in chosen}.map{it.descriptor}
            val scope=if(descriptors.any{it.category==ResourceCategory.THEME})BeautyParameters.neutral.keys else descriptors.flatMap{it.scope}.toSet()
            val inherited=when {
                next.selections["theme"]!=null->emptyMap()
                next.selections.keys.any{it!="theme"}->s.selections-"theme"
                else->s.selections
            }
            s=BeautyResourceState((s.parameters-scope)+next.parameters,inherited+next.selections,next.enabled,(s.makeupColors-scope.map {it.removePrefix("makeup.")}.toSet())+next.makeupColors)
        }
        return s
    }
    companion object {
        fun parse(bytes:ByteArray):BeautyResourceDefaults {
            require(bytes.size<=65536);val o=JSONObject(bytes.toString(Charsets.UTF_8));o.fields("defaultsVersion","defaultLocale","localeMode","enabled","parameters","selections","categoryOrder");o.integer("defaultsVersion",1,1)
            val mode=o.text("localeMode");require(mode in setOf("FOLLOW_HOST","FIXED"));val locale=o.text("defaultLocale");require(java.util.Locale.forLanguageTag(locale).language.isNotBlank())
            require(o.get("enabled") is Boolean)
            val order=o.strings("categoryOrder").map(ResourceCategory::valueOf);require(order.size==ResourceCategory.entries.size&&order.toSet()==ResourceCategory.entries.toSet())
            return BeautyResourceDefaults(locale,mode=="FOLLOW_HOST",BeautyResourceState(parseParameters(o.getJSONObject("parameters")),readSelections(o.getJSONObject("selections")),o.getBoolean("enabled")),order)
        }
        internal fun readSelections(o:JSONObject):Map<String,String?> = o.keys().asSequence().associateWith {key->
            require(key.matches(Regex("[a-z][a-z0-9_.]{0,63}")))
            if(o.isNull(key))null else o.text(key).also {require(it.matches(Regex("[0-9a-f]{64}"))){"Selection must pin content hash"}}
        }
    }
}

/** 恢复时缺失的包只关闭对应槽位，参数和其他资源保留；调用方展示missingSlots，不自动改写用户存档。 */
data class ResourceRestoreResult(val state:BeautyResourceState,val missingSlots:Set<String>)
fun BeautyResourceState.resolveInstalled(installed:List<InstalledResource>):ResourceRestoreResult {
    val available=installed.associateBy{it.hash}
    val missing=selections.filter{(slot,hash)->hash!=null&&available[hash]?.descriptor?.slot!=slot}.keys
    // 素材缺失不能隐式回退程序化睫毛；只关闭已知缺失妆区，其他手动参数保留。
    val neutral=missing.mapNotNull {slot->when(slot) {
        "makeup.eyelash"->"makeup.eyelashes" to 0f
        "makeup.lipstick"->"makeup.lipstick" to 0f
        "makeup.blusher"->"makeup.blusher" to 0f
        else->null
    }}.toMap()
    return ResourceRestoreResult(copy(parameters=parameters+neutral,
        selections=selections.mapValues{(slot,hash)->if(slot in missing)null else hash}),missing)
}

/** 仅配置变化时落盘；保存成功后调用者再发布，失败不切换当前状态。 */
class BeautyResourceStateStore(private val file:File) {
    fun read():BeautyResourceState? {
        if(!file.exists())return null
        val bytes=file.inputStream().use {bounded(it,65536)};val o=JSONObject(bytes.toString(Charsets.UTF_8));val version=o.integer("stateVersion",1,2);o.fields(*if(version==1)arrayOf("stateVersion","parameters","selections","enabled")else arrayOf("stateVersion","parameters","selections","enabled","makeupColors"));require(o.get("enabled") is Boolean)
        return BeautyResourceState(parseParameters(o.getJSONObject("parameters")),BeautyResourceDefaults.readSelections(o.getJSONObject("selections")),o.getBoolean("enabled"),if(version==2)MakeupColorOverrides.read(o.getJSONObject("makeupColors"))else emptyMap())
    }
    fun write(s:BeautyResourceState) {BeautyParameters.validate(s.parameters);BeautyResourceDefaults.readSelections(JSONObject(s.selections.mapValues{it.value?:JSONObject.NULL}));BeautyResourceManager.atomic(file,JSONObject().put("stateVersion",2).put("makeupColors",MakeupColorOverrides.json(s.makeupColors)).put("parameters",JSONObject(s.parameters)).put("selections",JSONObject(s.selections.mapValues{it.value?:JSONObject.NULL})).put("enabled",s.enabled).toString().toByteArray())}
}
/** 先准备所有资源再得到不可变快照；失败关闭本次新句柄，原快照仍有效。 */
class BeautyEffectComposer(private val manager:BeautyResourceManager,private val capabilities:ResourceCapabilities) {
    fun prepare(state:BeautyResourceState):ComposedBeauty {
        BeautyParameters.validate(state.parameters);val leases=mutableListOf<ResourceLease>()
        try {
            var settings=BeautySettings()
            // 主题先整套替换，用户随后选择的单项覆盖同槽位；主题的可覆盖拆分由制作端声明。
            val selected=state.selections.entries.filter{it.value!=null}.sortedBy {if(it.key=="theme")0 else 1}
            require(selected.none {it.key=="theme"}||selected.size==1){"Theme must be materialized before editing slots"}
            for((slot,hash)in selected) {
                val lease=manager.acquire(requireNotNull(hash));leases+=lease
                require(lease.resource.descriptor.slot==slot){"SLOT_MISMATCH"}
            }
            // 旧整套参数先作底层，独立槽位随后覆盖；不依赖选择/JSON键的插入顺序。
            for(resource in ResourceEffectApplication.ordered(leases.map{it.resource}))
                settings=resource.applyTo(settings,capabilities)
            settings=MakeupColorOverrides.apply(BeautyParameters.apply(settings,state.parameters),state.makeupColors)
            if(!state.enabled)settings=settings.copy(smoothing=0f,whitening=0f,filter=BeautyFilter.NONE,adjustments=BeautyAdjustments(),eyeMakeup=null)
            return ComposedBeauty(settings,leases)
        }catch(t:Throwable){leases.forEach{it.close()};throw t}
    }
    /** 单选槽位；从主题转自定义时展开子资源应先由宿主安装拆分后传入完整状态。 */
    fun select(state:BeautyResourceState,resource:InstalledResource):BeautyResourceState {
        val d=resource.descriptor;capabilities.requireSupported(d)
        if(d.category==ResourceCategory.THEME)return BeautyResourceState(selections=mapOf("theme" to resource.hash),enabled=state.enabled)
        val editable=materialize(state)
        val selections=editable.selections.filterKeys{it!="theme"}+ (d.slot to resource.hash)
        val parameters=editable.parameters.filterKeys {it !in d.scope}
        return editable.copy(parameters=parameters,selections=selections,makeupColors=editable.makeupColors-d.scope.map {it.removePrefix("makeup.")}.toSet())
    }
    /** 套装转自定义：拆分并固定全部子项，不丢失其他已搭配效果。 */
    fun materialize(state:BeautyResourceState):BeautyResourceState {
        val theme=state.selections["theme"]?:return state
        val children=manager.materializeTheme(theme)
        return state.copy(selections=children.associate {it.descriptor.slot to it.hash})
    }
}
/** 宿主在旧GPU提交已完成后释放旧快照；不把安装目录寿命绑在Activity上。 */
class ComposedBeauty internal constructor(val settings:BeautySettings,private val leases:List<ResourceLease>):AutoCloseable {override fun close(){leases.forEach{it.close()}}}
