package com.beautysdk.demo.feature.app

    /** 配置快照带分析代次，关闭再开启挂件时不复用旧检测。 */
internal data class PreviewRequest(val options: CameraDemoOptions, val original: Boolean, val revision: Long, val customPack: com.beautysdk.StickerPack? = null) {
        private val resolved = options.effects.themeSettings ?: options.effects.resources.fold(com.beautysdk.BeautySettings(adjustments=options.effects.adjustments,scene=options.effects.scene)) { base,pack -> pack.applyTo(base) }
        // 以最终生效资源判定需求；完整主题和空工坊覆盖仍优先，不能唤醒被替代的旧胡子。
        private val hasMouthSticker: Boolean get() {
            if(options.sticker3D!=null)return false
            fun mouth(pack:com.beautysdk.StickerPack?)=pack?.layers?.any {it.anchor==com.beautysdk.StickerAnchor.UPPER_LIP}==true
            if(options.effects.themeSettings!=null)return resolved.moustache || mouth(resolved.stickerPack)
            return mouth(resolved.stickerPack) || if(customPack!=null)mouth(customPack) else options.effects.moustache
        }
        val automaticGpuAnalysis: Boolean get() = options.sticker3D!=null ||
            (options.beautyBackend==DemoBeautyBackend.GPUPIXEL && (resolved.adjustments.hasMakeup || hasMouthSticker))
        val prefersGpuAnalysis: Boolean get() = options.mediaPipeGpu || automaticGpuAnalysis
        val needsFreshFaceRedraw: Boolean get() = needsAnalysis &&
            options.beautyBackend == DemoBeautyBackend.GPUPIXEL && (resolved.adjustments.hasMakeup || hasMouthSticker)
        val needsAnalysis: Boolean get() = !original && options.faceAnalysisEnabled && options.backend != DemoBackend.BEAUTY_ONLY &&
            (options.sticker3D != null || options.observeFaces || if(options.effects.themeSettings!=null) resolved.requiresFaceAnalysis else (resolved.scene.needsFace || (options.beautyBackend == DemoBeautyBackend.GPUPIXEL && resolved.adjustments.needsFace) ||
            (!resolved.stickerPack?.layers.isNullOrEmpty() || (customPack?.layers?.isNotEmpty() ?: with(options.effects) { (glasses && glassesStyle != 0) || moustache || (hat && headStyle != 0) }))))
        val needsGestures: Boolean get() = !original && options.gestureEnabled
        val needsSegmentation: Boolean get() = !original && resolved.scene.needsSegmentation
    }
