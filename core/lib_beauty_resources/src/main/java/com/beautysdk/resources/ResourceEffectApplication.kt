package com.beautysdk.resources

import com.beautysdk.*

/** 配置阶段执行。主题与独立槽位共享唯一顺序，各类型只写自己的字段。 */
internal object ResourceEffectApplication {
    fun ordered(resources:List<PreparedResource>) = resources.sortedWith(compareBy(
        { if(it.descriptor.subcategory=="LEGACY") 0 else 1 }, { it.descriptor.slot }))

    fun apply(resource:PreparedResource,base:BeautySettings,capabilities:ResourceCapabilities):BeautySettings {
        if(resource.descriptor.category==ResourceCategory.THEME)
            return ordered(resource.children).fold(BeautySettings()) { state,child->child.applyTo(state,capabilities) }
        val result=MakeupColorOverrides.apply(BeautyParameters.apply(base,resource.values),resource.colors)
        val payload=resource.payload ?: return result
        return when(resource.descriptor.category) {
            ResourceCategory.STICKER -> applySticker(result,payload)
            ResourceCategory.BACKGROUND -> applyBackground(result,payload)
            ResourceCategory.MOTION -> applyMotion(result,payload)
            ResourceCategory.MAKEUP -> applyMakeup(resource.descriptor,result,payload)
            ResourceCategory.FILTER -> result.copy(filter=payload.filter)
            ResourceCategory.BEAUTY -> applyLegacy(resource.descriptor,result,payload)
            else -> result
        }
    }
    private fun applySticker(base:BeautySettings,payload:BeautySettings) = base.copy(
        stickerPack=StickerPack(base.stickerPack?.layers.orEmpty()+payload.stickerPack?.layers.orEmpty()))
    private fun applyBackground(base:BeautySettings,payload:BeautySettings) = base.copy(scene=base.scene.copy(
        background=payload.scene.background,backgroundColor=payload.scene.backgroundColor,
        backgroundImage=payload.scene.backgroundImage,blurRadius=payload.scene.blurRadius,
        backgroundFocusX=payload.scene.backgroundFocusX,backgroundFocusY=payload.scene.backgroundFocusY))
    private fun applyMotion(base:BeautySettings,payload:BeautySettings) = base.copy(scene=base.scene.copy(
        motion=payload.scene.motion,zoom=payload.scene.zoom,periodMs=payload.scene.periodMs,timeline=payload.scene.timeline))
    private fun applyMakeup(d:ResourceDescriptor,base:BeautySettings,payload:BeautySettings) =
        if(d.subcategory=="EYELASH") base.copy(eyeMakeup=payload.eyeMakeup) else base
    // 旧整套协议集中兼容，不能让旧材料空值清掉独立睫毛。
    private fun applyLegacy(d:ResourceDescriptor,base:BeautySettings,payload:BeautySettings) =
        if(d.subcategory=="LEGACY") base.copy(filter=payload.filter,eyeMakeup=payload.eyeMakeup ?: base.eyeMakeup) else base
}
