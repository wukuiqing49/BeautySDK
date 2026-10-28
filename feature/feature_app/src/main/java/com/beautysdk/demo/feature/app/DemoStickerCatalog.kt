package com.beautysdk.demo.feature.app

import android.content.Context
import com.beautysdk.*

/** Demo 素材仓库：只在处理 worker 上使用，按需解码并缓存不可变像素，绝不逐帧解码 PNG。 */
internal class DemoStickerCatalog(context: Context, private val deferLoading: Boolean = false) {
    private val assets = context.applicationContext.assets
    private val cache = mutableMapOf<String, ImageSticker>()
    /** 内置样例与外部导入使用同一个SDK资源包读取器，避免两套manifest定位规则。 */
    private fun decode(id: String) = StickerResourcePack.loadZip(assets.open("sticker_packs/$id.zip")).toStickerPack().layers.single()
    @Volatile private var preloaded: Map<String,ImageSticker>? = null
    private var previousPreloaded: Map<String,ImageSticker>? = null
    /** 只加载当前选择，最多三层；逐个发布，坏素材不会阻止其他素材展示。 */
    fun preload(ids: Set<String>,onError: (Exception)->Unit) {
        var available=preloaded.orEmpty().filterKeys { it in ids }
        preloaded=available
        for(id in ids) if(id !in available) {
            try { available=available + (id to decode(id));preloaded=available }
            catch(error:Exception) { onError(error) }
        }
    }
    fun selectedIds(effects: DemoEffects): Set<String> = buildSet {
        if(effects.glasses && effects.glassesStyle in 1..2) add(if(effects.glassesStyle==1) "round_glasses" else "heart_glasses")
        if(effects.moustache) add("curled_moustache")
        if(effects.hat && effects.headStyle in 1..3) add(listOf("","blue_top_hat","gold_crown","cat_ears")[effects.headStyle])
    }
    private fun load(id: String): ImageSticker? = if(deferLoading) preloaded?.get(id)
        else cache.getOrPut(id) {
            if(cache.size>=3) cache.remove(cache.keys.first())
            decode(id)
        }

    private var previous: DemoEffects? = null
    private var previousSettings = BeautySettings()
    private var previousCustom: StickerPack? = null
    /** 在仓库所属处理线程清空素材及设置缓存；不处理宿主画面。 */
    fun clear() { cache.clear(); previous = null; previousCustom = null; previousPreloaded = null; previousSettings = BeautySettings() }
    /** 将页面选择转换为不可变美颜设置；配置不变时直接复用，素材仅在首次选择时解码。 */
    fun settings(effects: DemoEffects, customPack: StickerPack? = null): BeautySettings {
        effects.themeSettings?.let { return it }
        val available=preloaded
        if (previous == effects && previousCustom === customPack && previousPreloaded === available) return previousSettings
        previousPreloaded=available
        if(customPack!=null) return BeautySettings(effects.smooth,effects.white,
            if(effects.warm) BeautyFilter.WARM else BeautyFilter.NONE,stickerPack=customPack,adjustments=effects.adjustments,scene=effects.scene).let { base -> effects.resources.fold(base) { value,pack -> pack.applyTo(value) } }.also {
                previous=effects; previousCustom=customPack; previousSettings=it
            }
        previousCustom=null
        require(effects.glassesStyle in 0..2 && effects.headStyle in 0..3)
        val layers = mutableListOf<ImageSticker>()
        if (effects.glasses && effects.glassesStyle != 0)
            load(if (effects.glassesStyle == 1) "round_glasses" else "heart_glasses")?.let(layers::add)
        if (effects.moustache) load("curled_moustache")?.let(layers::add)
        if (effects.hat && effects.headStyle != 0)
            load(listOf("", "blue_top_hat", "gold_crown", "cat_ears")[effects.headStyle])?.let(layers::add)
        return BeautySettings(effects.smooth,effects.white,
            if (effects.warm) BeautyFilter.WARM else BeautyFilter.NONE,
            stickerPack = StickerPack(layers),adjustments=effects.adjustments,scene=effects.scene).let { base -> effects.resources.fold(base) { value,pack -> pack.applyTo(value) } }.also { previous = effects; previousSettings = it }
    }
}
