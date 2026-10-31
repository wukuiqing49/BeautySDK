package com.beautysdk.demo

import android.content.Context
import android.view.View
import android.widget.*
import com.beautysdk.resources.BeautyResourceLibrary
import com.beautysdk.resources.ResourceCategory

/** 参数选择区和固定滑杆；模型更新仅同步数值，不重建正在拖动的控件。 */
internal class ResourceParameterPanel(context:Context):ScrollView(context) {
    private val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val choices=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
    private val label=TextView(context).apply{setTextAppearance(R.style.CameraLabel);textSize=13f;minLines=2;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false;setPadding(0,dp(4),0,dp(4))}
    private val slider=SeekBar(context).apply{setPadding(dp(16),0,dp(16),0);splitTrack=false}
    private val colorControl=MakeupColorControl(context)
    private val reset=TextView(context).apply{setTextAppearance(R.style.EffectText);setText(R.string.ui_reset_parameter);tag="reset_parameter";minHeight=dp(48);gravity=android.view.Gravity.CENTER;isClickable=true;isFocusable=true}
    private var currentValues:Map<String,Float> = emptyMap()
    private var selection:String?=null
    private var requestedParameter:String?=null
    private var bindCurrent:(()->Unit)?=null
    private var key:Any?=null
    /** 预设与微调使用相同参数ID，不能沿用上一个部位的滑杆。 */
    fun focusParameter(id:String){requestedParameter=id}
    init {
        isVerticalScrollBarEnabled=false;isFillViewport=false;clipToPadding=false
        setPadding(0,0,0,dp(12))
        addView(content,LayoutParams(-1,-2))
        content.addView(HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;isFillViewport=false;addView(choices)},LinearLayout.LayoutParams(-1,dp(64)))
        content.addView(LinearLayout(context).apply{
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(label,LinearLayout.LayoutParams(0,-2,1f));addView(reset,LinearLayout.LayoutParams(dp(88),dp(48)))
        },LinearLayout.LayoutParams(-1,-2))
        content.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(colorControl,LinearLayout.LayoutParams(-1,dp(48)))
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    fun render(library:BeautyResourceLibrary,category:ResourceCategory,locale:String,values:Map<String,Float>,change:(Map<String,Float>)->Unit,colors:com.beautysdk.MakeupColors=com.beautysdk.MakeupColors(),changeColor:(String,com.beautysdk.MakeupColor)->Unit={_,_->}) {
        currentValues=values
        val parameters=library.categories.firstOrNull{it.id==category}?.effects.orEmpty().flatMap{e->e.parameters.map{e to it}}
        val nextKey=listOf(library,category,locale)
        if(key!=nextKey){key=nextKey;selection=parameters.firstOrNull()?.second?.id;choices.removeAllViews()}
        requestedParameter?.takeIf{id->parameters.any{it.second.id==id}}?.let{selection=it;requestedParameter=null}
        fun bind(){
            colorControl.bind(selection,colors,changeColor)
            val pair=parameters.firstOrNull{it.second.id==selection}
            if(pair==null){label.setText(R.string.ui_no_parameters);slider.visibility=GONE;reset.visibility=GONE;return}
            val (effect,p)=pair;slider.visibility=VISIBLE;reset.visibility=VISIBLE
            val name=library.text(effect.nameKey,locale)
            fun display(value:Float){label.text=context.getString(R.string.resource_parameter_value,name,library.text(p.nameKey,locale),value)}
            slider.setOnSeekBarChangeListener(null)
            slider.tag="library_parameter_${p.id}";slider.contentDescription=name
            slider.max=kotlin.math.ceil((p.max-p.min)/p.step).toInt()
            val value=currentValues[p.id]?:p.default
            if(!slider.isPressed)slider.progress=((value-p.min)/p.step).toInt().coerceIn(0,slider.max)
            display(if(slider.isPressed)(p.min+slider.progress*p.step).coerceIn(p.min,p.max)else value)
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar?)=Unit
                override fun onStopTrackingTouch(bar:SeekBar?)=Unit
                override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser){val v=(p.min+progress*p.step).coerceIn(p.min,p.max);display(v);change(mapOf(p.id to v))}}
            })
            reset.setOnClickListener{change(mapOf(p.id to p.default))}
            for(i in 0 until choices.childCount)choices.getChildAt(i).isSelected=choices.getChildAt(i).tag==selection
        }
        bindCurrent=::bind
        if(choices.childCount==0)parameters.forEach{(effect,p)->
            choices.addView(TextView(context,null,0,R.style.EffectParameter).apply{
                text=library.text(effect.nameKey,locale);tag=p.id;maxLines=2;gravity=android.view.Gravity.CENTER
                setOnClickListener{selection=p.id;bindCurrent?.invoke()}
                minimumHeight=dp(48);includeFontPadding=false
                setPadding(dp(12),0,dp(12),0)
            },LinearLayout.LayoutParams(-2,dp(48)).apply{setMargins(0,dp(4),dp(8),dp(4))})
        }
        bind()
    }
}
