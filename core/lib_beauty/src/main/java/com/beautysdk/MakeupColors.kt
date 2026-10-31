package com.beautysdk

/** 归一化sRGB妆色，颜色不包含强度；透明度仍由各自强度及区域覆盖控制。 */
data class MakeupColor(val red:Float,val green:Float,val blue:Float) {
    init { require(red in 0f..1f && green in 0f..1f && blue in 0f..1f) }
    companion object {
        @JvmStatic fun fromRgb(rgb:Int):MakeupColor {
            require(rgb in 0..0xffffff)
            return MakeupColor(((rgb ushr 16) and 255)/255f,((rgb ushr 8) and 255)/255f,(rgb and 255)/255f)
        }
    }
}
/** 两个区域独立选色，默认值与旧Shader相同，旧资源视觉保持原值。 */
data class MakeupColors(
    val lipstick:MakeupColor=MakeupColor(.90f,.32f,.38f),
    val blusher:MakeupColor=MakeupColor(.80f,.28f,.36f),
)
