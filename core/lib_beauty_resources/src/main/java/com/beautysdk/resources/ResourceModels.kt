package com.beautysdk.resources

import com.beautysdk.*
import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
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
        val candidates=resourceLocaleCandidates(locale,defaultLocale)
        return candidates.firstNotNullOfOrNull { key->localizations.entries.firstOrNull { it.key.equals(key,true) }?.value } ?: text
    }
}
/** 资源能力是执行器能力，不会由包内声明自动开启模型。 */
data class ResourceCapabilities(val features:Set<String>) {
    /** 可用于UI置灰和接入诊断；不加载模型、不改变资源选择。 */
    fun missingFeatures(d:ResourceDescriptor):Set<String> = java.util.Collections.unmodifiableSet((d.features-features).toSortedSet())
    fun requireSupported(d:ResourceDescriptor) {
        val missing=missingFeatures(d)
        if(missing.isNotEmpty())throw UnsupportedResourceException(d.id,missing)
    }
    companion object {
        /** 历史全能力配置：要求宿主实际接好了人脸、场景及分割组件，不是自动探测或质量认证。 */
        @JvmStatic fun androidGpuPixel()=ResourceCapabilities(setOf("face.landmarks478","segmentation","beauty.parameters.v1","reshape.parameters.v1","makeup.parameters.v1","makeup.eyelash.texture.v1","filter.basic.v1","sticker.png.v1","background.v1","motion.v1","theme.v1"))
        /** 根据宿主实际装配声明能力；faceAnalysis要求478点后端，不接受仅有人脸框的检测器。
         * 默认仅不依赖人脸/场景的GPUPixel处理，不代表运行时自动探测或质量认证。
         */
        @JvmStatic @JvmOverloads fun configuredGpuPixel(
            faceAnalysis:Boolean=false,sceneRenderer:Boolean=false,segmentation:Boolean=false,
        ):ResourceCapabilities {
            require(!segmentation||sceneRenderer){"Segmentation requires scene renderer"}
            val available=linkedSetOf("beauty.parameters.v1","filter.basic.v1","theme.v1")
            if(faceAnalysis)available.addAll(listOf("face.landmarks478","reshape.parameters.v1","makeup.parameters.v1","makeup.eyelash.texture.v1","sticker.png.v1"))
            if(sceneRenderer)available.add("motion.v1")
            if(segmentation)available.addAll(listOf("segmentation","background.v1"))
            return ResourceCapabilities(java.util.Collections.unmodifiableSet(available))
        }
    }
}
/** 保持IllegalArgumentException兼容；宿主无需解析英文message判断缺失能力。 */
class UnsupportedResourceException(val resourceId:String,missing:Set<String>):IllegalArgumentException("UNSUPPORTED: ${missing.sorted()}") {
    val code:String get()="UNSUPPORTED_RESOURCE"
    val missingFeatures:Set<String> = java.util.Collections.unmodifiableSet(missing.toSortedSet())
}
/** 参数作用域统一表；新执行器需先扩充该表，不能接受任意反射字段。 */
object BeautyParameters {
    val neutral:Map<String,Float> = java.util.Collections.unmodifiableMap(ParameterContract.definitions.mapValues{it.value.neutral})
    fun category(key:String)=requireNotNull(ParameterContract.definitions[key]){"Unknown parameter"}.category
    fun validate(values:Map<String,Float>) { values.forEach { (key,v)->
        val p=requireNotNull(ParameterContract.definitions[key]){"Unknown parameter $key"}
        require(v.isFinite() && v in p.min..p.max) {"Invalid parameter $key"}
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
        val permissions = descriptor.features.mapNotNull { feature -> when(feature) {
            "beauty.parameters.v1" -> LicensedFeature.BEAUTY
            "filter.basic.v1" -> LicensedFeature.FILTER
            "face.landmarks478" -> LicensedFeature.FACE
            "reshape.parameters.v1" -> LicensedFeature.RESHAPE
            "makeup.parameters.v1", "makeup.eyelash.texture.v1" -> LicensedFeature.MAKEUP
            "sticker.png.v1" -> LicensedFeature.STICKER
            "background.v1", "segmentation" -> LicensedFeature.BACKGROUND
            "motion.v1" -> LicensedFeature.MOTION
            else -> null // 容器theme由每个子资源分别校验，不额外赋予权限。
        } }.toSet()
        BeautyLicensing.requireFeatures(permissions)
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

