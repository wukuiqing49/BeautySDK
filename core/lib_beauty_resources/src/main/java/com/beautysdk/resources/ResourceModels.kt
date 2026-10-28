package com.beautysdk.resources

import com.beautysdk.*
import java.util.Locale

/** 稳定产品分类；分类不等于后端执行器或已支持能力。 */
enum class ResourceCategory { BEAUTY, RESHAPE, MAKEUP, FILTER, STICKER, BACKGROUND, MOTION, THEME }
data class ResourceText(val name:String,val description:String="")
/** 只读资源描述，revision为发布版本，packageVersion为文件协议版本。 */
data class ResourceDescriptor(val id:String,val revision:Int,val category:ResourceCategory,val subcategory:String,
    val slot:String,val defaultLocale:String,val text:ResourceText,val localizations:Map<String,ResourceText>,
    val features:Set<String>,val scope:Set<String>) {
    /** 按宿主语言和父语言回退，不跨简繁脚本替换。 */
    fun localized(locale:String):ResourceText {
        val tag=Locale.forLanguageTag(locale).toLanguageTag()
        val candidates=mutableListOf(tag)
        val parsed=Locale.forLanguageTag(tag)
        if(parsed.script.isNotEmpty()) candidates+=parsed.language+"-"+parsed.script
        else if(parsed.language!="zh" || parsed.country !in setOf("TW","HK","MO")) candidates+=parsed.language
        candidates+=defaultLocale
        return candidates.firstNotNullOfOrNull { key->localizations.entries.firstOrNull { it.key.equals(key,true) }?.value } ?: text
    }
}
/** 资源能力是执行器能力，不会由包内声明自动开启模型。 */
data class ResourceCapabilities(val features:Set<String>) {
    fun requireSupported(d:ResourceDescriptor) { require(features.containsAll(d.features)) { "UNSUPPORTED: ${d.features-features}" } }
    companion object {
        @JvmStatic fun androidGpuPixel()=ResourceCapabilities(setOf("face.landmarks478","segmentation","beauty.parameters.v1","reshape.parameters.v1","makeup.parameters.v1","makeup.eyelash.texture.v1","filter.basic.v1","sticker.png.v1","background.v1","motion.v1","theme.v1"))
    }
}
/** 参数作用域统一表；新执行器需先扩充该表，不能接受任意反射字段。 */
object BeautyParameters {
    val neutral:Map<String,Float> = java.util.Collections.unmodifiableMap(linkedMapOf("beauty.smoothing" to 0f,"beauty.whitening" to 0f,"beauty.rosy" to 0f,
        "color.brightness" to 0f,"color.contrast" to 1f,"color.saturation" to 1f,"color.sharpening" to 0f,
        "reshape.slimFace" to 0f,"reshape.bigEyes" to 0f,"reshape.slimNose" to 0f,"reshape.chinLength" to 0f,"reshape.mouthSize" to 0f,
        "makeup.lipstick" to 0f,"makeup.blusher" to 0f,"makeup.eyelashes" to 0f))
    fun category(key:String)=when(key.substringBefore('.')) {"beauty"->ResourceCategory.BEAUTY;"color"->ResourceCategory.FILTER;"reshape"->ResourceCategory.RESHAPE;"makeup"->ResourceCategory.MAKEUP;else->error("Unknown parameter")}
    fun validate(values:Map<String,Float>) { values.forEach { (key,v)->
        require(key in neutral);val range=when(key){"color.brightness","reshape.chinLength","reshape.mouthSize"->-1f..1f;"color.contrast","color.saturation"->0f..2f;else->0f..1f};require(v in range) {"Invalid parameter $key"}
    } }
    fun read(s:BeautySettings):Map<String,Float> {val a=s.adjustments;return linkedMapOf("beauty.smoothing" to s.smoothing,"beauty.whitening" to s.whitening,"beauty.rosy" to a.rosy,
        "color.brightness" to a.brightness,"color.contrast" to a.contrast,"color.saturation" to a.saturation,"color.sharpening" to a.sharpening,
        "reshape.slimFace" to a.slimFace,"reshape.bigEyes" to a.bigEyes,"reshape.slimNose" to a.slimNose,"reshape.chinLength" to a.chinLength,"reshape.mouthSize" to a.mouthSize,
        "makeup.lipstick" to a.lipstick,"makeup.blusher" to a.blusher,"makeup.eyelashes" to a.eyelashes)}
    /** 只覆盖显式键，避免选睫毛把口红/磨皮清零。 */
    fun apply(base:BeautySettings,values:Map<String,Float>):BeautySettings {
        validate(values);val a=base.adjustments;fun v(k:String,old:Float)=values[k]?:old
        return base.copy(smoothing=v("beauty.smoothing",base.smoothing),whitening=v("beauty.whitening",base.whitening),adjustments=a.copy(
            rosy=v("beauty.rosy",a.rosy),brightness=v("color.brightness",a.brightness),contrast=v("color.contrast",a.contrast),saturation=v("color.saturation",a.saturation),sharpening=v("color.sharpening",a.sharpening),
            slimFace=v("reshape.slimFace",a.slimFace),bigEyes=v("reshape.bigEyes",a.bigEyes),slimNose=v("reshape.slimNose",a.slimNose),chinLength=v("reshape.chinLength",a.chinLength),mouthSize=v("reshape.mouthSize",a.mouthSize),
            lipstick=v("makeup.lipstick",a.lipstick),blusher=v("makeup.blusher",a.blusher),eyelashes=v("makeup.eyelashes",a.eyelashes)))
    }
}
/** 已准备的不可变资源；只在IO配置阶段解码，不在process回调加载。 */
class PreparedResource internal constructor(val descriptor:ResourceDescriptor,private val preview:ByteArray,
    internal val values:Map<String,Float>,internal val payload:BeautySettings?,internal val children:List<PreparedResource> = emptyList()) {
    fun previewPng()=preview.copyOf()
    fun applyTo(base:BeautySettings,capabilities:ResourceCapabilities):BeautySettings {
        capabilities.requireSupported(descriptor)
        if(descriptor.category==ResourceCategory.THEME) return children.fold(BeautySettings()) { state,child->child.applyTo(state,capabilities) }
        var result=BeautyParameters.apply(base,values)
        val p=payload ?: return result
        result=when(descriptor.category) {
            ResourceCategory.STICKER->result.copy(stickerPack=StickerPack((base.stickerPack?.layers.orEmpty()+p.stickerPack?.layers.orEmpty())))
            ResourceCategory.BACKGROUND->result.copy(scene=result.scene.copy(background=p.scene.background,backgroundColor=p.scene.backgroundColor,backgroundImage=p.scene.backgroundImage,blurRadius=p.scene.blurRadius,backgroundFocusX=p.scene.backgroundFocusX,backgroundFocusY=p.scene.backgroundFocusY))
            ResourceCategory.MOTION->result.copy(scene=result.scene.copy(motion=p.scene.motion,zoom=p.scene.zoom,periodMs=p.scene.periodMs,timeline=p.scene.timeline))
            ResourceCategory.FILTER->result.copy(filter=p.filter)
            ResourceCategory.MAKEUP->if(descriptor.subcategory=="EYELASH") result.copy(eyeMakeup=p.eyeMakeup) else result
            ResourceCategory.BEAUTY->if(descriptor.subcategory=="LEGACY") result.copy(filter=p.filter,eyeMakeup=p.eyeMakeup) else result
            else->result
        }
        return result
    }
}

