package com.beautysdk.demo

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton

/** 保留RadioGroup的互斥选择；再次激活已选项时由宿主关闭贴纸。 */
class ToggleStickerRadioButton @JvmOverloads constructor(
    context:Context,attrs:AttributeSet?=null,
    defStyleAttr:Int=androidx.appcompat.R.attr.radioButtonStyle,
):AppCompatRadioButton(context,attrs,defStyleAttr) {
    var onReselect:(()->Unit)?=null

    override fun toggle() {
        val deselect=onReselect
        if(isChecked&&deselect!=null)deselect() else super.toggle()
    }
}
