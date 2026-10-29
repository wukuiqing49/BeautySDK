// Generated from protocol/parameter-contract.json; edit the contract and regenerate.
package com.beautysdk.resources

internal object ParameterContract {
    data class Definition(val id:String,val category:ResourceCategory,val min:Float,val max:Float,val neutral:Float,val step:Float)
    val definitions = listOf(
        Definition("beauty.smoothing", ResourceCategory.BEAUTY, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("beauty.whitening", ResourceCategory.BEAUTY, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("beauty.rosy", ResourceCategory.BEAUTY, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("color.brightness", ResourceCategory.FILTER, -1.0f, 1.0f, 0.0f, 0.01f),
        Definition("color.contrast", ResourceCategory.FILTER, 0.0f, 2.0f, 1.0f, 0.01f),
        Definition("color.saturation", ResourceCategory.FILTER, 0.0f, 2.0f, 1.0f, 0.01f),
        Definition("color.sharpening", ResourceCategory.FILTER, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("reshape.slimFace", ResourceCategory.RESHAPE, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("reshape.bigEyes", ResourceCategory.RESHAPE, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("reshape.slimNose", ResourceCategory.RESHAPE, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("reshape.chinLength", ResourceCategory.RESHAPE, -1.0f, 1.0f, 0.0f, 0.01f),
        Definition("reshape.mouthSize", ResourceCategory.RESHAPE, -1.0f, 1.0f, 0.0f, 0.01f),
        Definition("makeup.lipstick", ResourceCategory.MAKEUP, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("makeup.blusher", ResourceCategory.MAKEUP, 0.0f, 1.0f, 0.0f, 0.01f),
        Definition("makeup.eyelashes", ResourceCategory.MAKEUP, 0.0f, 1.0f, 0.0f, 0.01f)
    ).associateBy { it.id }
}
