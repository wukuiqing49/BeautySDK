package com.beautysdk.resources

import com.beautysdk.*
import org.json.JSONObject

/** 颜色和强度分别保存；每次操作只覆盖指定妆区。 */
object MakeupColorOverrides {
    fun validate(colors:Map<String,MakeupColor>) {require(colors.keys.all {it=="lipstick" || it=="blusher"})}
    fun apply(base:BeautySettings,colors:Map<String,MakeupColor>):BeautySettings {
        validate(colors);if(colors.isEmpty())return base
        val current=base.adjustments.makeupColors
        return base.copy(adjustments=base.adjustments.copy(makeupColors=MakeupColors(
            colors["lipstick"]?:current.lipstick,colors["blusher"]?:current.blusher)))
    }
    internal fun read(o:JSONObject):Map<String,MakeupColor> = o.keys().asSequence().associateWith {key->
        val color=o.getJSONObject(key);color.fields("red","green","blue")
        fun channel(name:String):Float {val n=color.get(name);require(n is Number);return n.toFloat()}
        MakeupColor(channel("red"),channel("green"),channel("blue"))
    }.also(::validate)
    internal fun json(colors:Map<String,MakeupColor>):JSONObject {
        validate(colors);return JSONObject().also {out->colors.forEach {(key,c)->out.put(key,JSONObject().put("red",c.red).put("green",c.green).put("blue",c.blue))}}
    }
}
