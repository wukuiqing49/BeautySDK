package com.beautysdk.resources

import java.util.Locale

/** 所有资源展示共用语言回退；保留脚本优先级，最后使用包声明的默认语言。 */
internal fun resourceLocaleCandidates(locale:String,defaultLocale:String):List<String> {
    val tag=Locale.forLanguageTag(locale)
    return buildList {
        add(tag.toLanguageTag())
        if(tag.script.isNotEmpty())add(tag.language+"-"+tag.script)
        else if(tag.language!="zh"||tag.country !in setOf("TW","HK","MO"))add(tag.language)
        add(defaultLocale)
    }.distinct()
}
