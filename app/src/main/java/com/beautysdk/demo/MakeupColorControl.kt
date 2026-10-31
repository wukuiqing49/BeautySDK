package com.beautysdk.demo

import android.content.Context
import android.graphics.Color
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import com.beautysdk.*

/** 单一妆区颜色入口，独立于强度；输入校验通过后才提交。 */
internal class MakeupColorControl(context:Context):AppCompatButton(context) {
    init {tag="makeup_color";isAllCaps=false;minHeight=(48*resources.displayMetrics.density).toInt()}
    fun bind(parameter:String?,colors:MakeupColors,change:(String,MakeupColor)->Unit) {
        val region=when(parameter){"makeup.lipstick"->"lipstick";"makeup.blusher"->"blusher";else->null}
        visibility=if(region==null)GONE else VISIBLE
        if(region==null)return
        val c=if(region=="lipstick")colors.lipstick else colors.blusher
        val rgb=Color.rgb((c.red*255).toInt(),(c.green*255).toInt(),(c.blue*255).toInt()) and 0xffffff
        val hex=String.format(java.util.Locale.ROOT,"#%06X",rgb)
        text=context.getString(R.string.makeup_color_value,hex)
        fun custom() {
            val input=EditText(context).apply{setSingleLine();setText(hex);selectAll();hint="#RRGGBB";inputType=android.text.InputType.TYPE_CLASS_TEXT;filters=arrayOf(android.text.InputFilter.LengthFilter(7))}
            val dialog=AlertDialog.Builder(context).setTitle(R.string.makeup_color_title).setView(input)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create()
            dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener positive@{
                val value=input.text.toString().trim()
                if(!value.matches(Regex("#[0-9a-fA-F]{6}"))){input.error=context.getString(R.string.makeup_color_invalid);return@positive}
                change(region,MakeupColor.fromRgb(value.drop(1).toInt(16)));dialog.dismiss()
            }}
            dialog.show()
        }
        setOnClickListener {
            val swatches=if(region=="lipstick")intArrayOf(0xC45060,0xA63C4C,0xD66A62,0xA85F70,0x9F6259)else intArrayOf(0xCC475C,0xD88170,0xB96478,0xCB846A,0xAB6779)
            val labels=resources.getStringArray(R.array.makeup_color_names)
            AlertDialog.Builder(context).setTitle(R.string.makeup_color_title)
                .setItems(labels){_,which->if(which<swatches.size)change(region,MakeupColor.fromRgb(swatches[which]))else custom()}
                .setNegativeButton(android.R.string.cancel,null).show()
        }
    }
}
