package com.beautysdk.demo

import android.widget.ArrayAdapter
import android.widget.Spinner

/** 完整换行显示分析方案/素材名称，避免系统单行 Spinner 把调试信息省略。 */
internal fun Spinner.useDemoEntries(array: Int) {
    adapter = ArrayAdapter.createFromResource(context,array,R.layout.item_demo_spinner).also {
        it.setDropDownViewResource(R.layout.item_demo_spinner)
    }
}
