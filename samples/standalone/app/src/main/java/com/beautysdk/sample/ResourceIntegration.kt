package com.beautysdk.sample

import android.content.Context
import com.beautysdk.BeautySettings
import com.beautysdk.resources.BeautyEffectComposer
import com.beautysdk.resources.BeautyParameters
import com.beautysdk.resources.BeautyResourceManager
import com.beautysdk.resources.ComposedBeauty
import com.beautysdk.resources.ResourceCapabilities
import java.io.File

/** IO线程安装/准备；返回后在GL线程将settings赋给pipeline，并保留快照直到不再使用。 */
fun prepareBuiltinDefaults(context: Context, capabilities: ResourceCapabilities): ComposedBeauty {
    val manager = BeautyResourceManager(File(context.filesDir, "sample-resources"))
    val delivery = manager.installDeliveryFromAssets(context.assets)
    require(delivery.defaults.state.enabled) { "Default effects are disabled; explicitly enable before applying" }
    require(delivery.library?.gestureResources?.none { it.defaultEnabled } != false) {
        "Gesture defaults need a separate gesture runtime and player; this sample does not silently ignore them"
    }
    // 不默认声称全能力；调用方声明实际装配的组件，缺失能力时抛出结构化异常。
    return BeautyEffectComposer(manager, capabilities).prepare(delivery.defaults.state)
}

/** GL配置阶段调整单项；其他资源与参数保持原快照值。 */
fun adjustWhitening(settings: BeautySettings, strength: Float): BeautySettings =
    BeautyParameters.apply(settings, mapOf("beauty.whitening" to strength))
