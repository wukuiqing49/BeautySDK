package com.beautysdk.demo

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.beautysdk.demo.databinding.ItemEffectPresetBinding

/** 展示与点击回调；资源安装、选择和文件操作仍由公共资源模型负责。 */
internal data class ResourceCard(
    val id:String, val name:String, val status:String, val selected:Boolean,
    val preview:Bitmap?, val click:()->Unit, val more:(()->Unit)?=null, val symbol:Int=0,
)

internal class ResourceCardAdapter(private val management:Boolean):ListAdapter<ResourceCard,ResourceCardAdapter.Holder>(object:DiffUtil.ItemCallback<ResourceCard>() {
    override fun areItemsTheSame(a:ResourceCard,b:ResourceCard)=a.id==b.id
    override fun areContentsTheSame(a:ResourceCard,b:ResourceCard)=a.name==b.name&&a.status==b.status&&a.selected==b.selected&&a.symbol==b.symbol&&a.preview?.generationId==b.preview?.generationId
}) {
    inner class Holder(val binding:ItemEffectPresetBinding):RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder {
        val binding=ItemEffectPresetBinding.inflate(LayoutInflater.from(parent.context),parent,false)
        val dp=parent.resources.displayMetrics.density
        binding.root.layoutParams=RecyclerView.LayoutParams(-1,-2).apply {setMargins(0,0,0,(12*dp).toInt())}
        (binding.presetPreview.parent as View).let{frame->frame.layoutParams=frame.layoutParams.apply{height=((if(management)80 else 54)*dp).toInt()}}
        binding.presetName.textSize=if(management)13f else 12f
        val lines=if(management&&parent.resources.configuration.screenHeightDp>=480)2 else 1
        binding.presetName.minLines=1;binding.presetName.maxLines=lines
        return Holder(binding)
    }
    override fun onBindViewHolder(holder:Holder,position:Int) {
        val item=getItem(position);val b=holder.binding
        b.root.isSelected=item.selected;b.root.tag="resource_${item.id}"
        b.root.contentDescription=b.root.context.getString(R.string.resources_card,item.name,item.status)
        b.presetStatus.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        b.presetName.text=item.name
        b.presetPreview.setImageBitmap(item.preview)
        b.presetPreview.scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
        b.presetPreview.setPadding(0,0,0,0)
        (b.presetPreview.parent as View).setBackgroundResource(if(item.symbol!=0||item.id=="none")R.drawable.bg_effect_cover else R.drawable.bg_preset_preview)
        if(item.symbol!=0||item.id=="none"){
            b.presetPreview.scaleType=android.widget.ImageView.ScaleType.FIT_CENTER
            b.presetPreview.setImageResource(if(item.id=="none")R.drawable.ic_effect_off else item.symbol)
            val height=b.presetPreview.parent.let{it as View}.layoutParams.height
            val density=b.root.resources.displayMetrics.density
            val inset=((height-(if(management)32 else 26)*density)/2).toInt().coerceAtLeast(0)
            b.presetPreview.setPadding(inset,inset,inset,inset)
        }
        b.presetCheck.visibility=if(item.selected)View.VISIBLE else View.GONE
        b.presetStatus.text=if(item.status==b.root.context.getString(R.string.ui_paused))b.root.context.getString(R.string.ui_paused_short)else item.status
        // 卡片状态通过角标与可访问性说明表达，避免选中/暂停状态撑高某一行。
        b.presetStatus.visibility=View.GONE
        b.root.setOnClickListener {bindingAdapterPosition(holder)?.click?.invoke()}
        b.root.setOnLongClickListener {bindingAdapterPosition(holder)?.more?.let{it();true}?:false}
    }
    private fun bindingAdapterPosition(holder:Holder):ResourceCard? = holder.bindingAdapterPosition.takeIf{it!=RecyclerView.NO_POSITION}?.let{currentList.getOrNull(it)}
}
