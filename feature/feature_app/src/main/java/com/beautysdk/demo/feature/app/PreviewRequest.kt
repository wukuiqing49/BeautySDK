package com.beautysdk.demo.feature.app

    /** 配置快照带分析代次，关闭再开启挂件时不复用旧检测。 */
internal data class PreviewRequest(val options: CameraDemoOptions, val original: Boolean, val revision: Long, val customPack: com.beautysdk.StickerPack? = null) {
        private val resolved = options.effects.themeSettings ?: options.effects.resources.fold(com.beautysdk.BeautySettings(adjustments=options.effects.adjustments,scene=options.effects.scene)) { base,pack -> pack.applyTo(base) }
        val needsAnalysis: Boolean get() = !original && options.faceAnalysisEnabled && options.backend != DemoBackend.BEAUTY_ONLY &&
            (options.observeFaces || if(options.effects.themeSettings!=null) resolved.requiresFaceAnalysis else (resolved.scene.needsFace || (options.beautyBackend == DemoBeautyBackend.GPUPIXEL && resolved.adjustments.needsFace) ||
            (!resolved.stickerPack?.layers.isNullOrEmpty() || (customPack?.layers?.isNotEmpty() ?: with(options.effects) { (glasses && glassesStyle != 0) || moustache || (hat && headStyle != 0) }))))
        val needsGestures: Boolean get() = !original && options.gestureEnabled
        val needsSegmentation: Boolean get() = !original && resolved.scene.needsSegmentation
    }
