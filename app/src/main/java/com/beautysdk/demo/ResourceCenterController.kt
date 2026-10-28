package com.beautysdk.demo

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AlertDialog
import com.beautysdk.*
import com.beautysdk.resources.*
import com.beautysdk.demo.databinding.ViewResourceCenterBinding

/** 分类资源面板。资源管理与状态保存调用公共库；列表不参与视频帧线程。 */
class ResourceCenterController(private val activity:androidx.appcompat.app.AppCompatActivity,private val binding:ViewResourceCenterBinding,private val management:Boolean=false,private val orderChanged:(List<ResourceCategory>)->Unit={},private val changed:(BeautySettings,Boolean)->Unit) {
    private val model=ViewModelProvider(activity)[ResourceCenterModel::class.java]
    private var category=ResourceCategory.BEAUTY
    private var baseline:Map<String,Float>?=null
    private val pendingManual=mutableMapOf<String,Float>()
    var settings:BeautySettings?=null;private set
    var enabled=true;private set
    private val thumbnails=mutableMapOf<String,android.graphics.Bitmap>()
    private val picker=activity.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(model::import)}
    init {
        binding.resourceManagement.visibility=if(management)View.VISIBLE else View.GONE
        binding.resourceNone.visibility=if(management)View.GONE else View.VISIBLE
        binding.resourceNone.setOnClickListener {model.disableCategory(category)}
        binding.resourceStatus.visibility=View.VISIBLE
        binding.resourceImport.setOnClickListener{picker.launch(arrayOf("application/zip","application/octet-stream"))}
        binding.resourceApplyDelivery.setOnClickListener{model.applyDeliveryDefaults()}
        binding.resourceDefaults.setOnClickListener{model.resetDefaults()}
        binding.resourceReset.setOnClickListener{model.resetCategory(category)}
        binding.resourceCancel.setOnClickListener{model.cancel()}
        binding.resourceOnline.setOnClickListener{
            val input=EditText(activity).apply{setText(activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).getString("catalog_url",""));hint=activity.getString(R.string.resource_catalog_url);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
            AlertDialog.Builder(activity).setTitle(R.string.resources_online).setView(input).setPositiveButton(android.R.string.ok){_,_->val url=input.text.toString().trim();activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).edit().putString("catalog_url",url).apply();model.catalog(url)}.setNegativeButton(android.R.string.cancel,null).show()
        }
        model.ui.observe(activity){ui->
            binding.resourceStatus.visibility=if(management||ui.busy||ui.error!=null)View.VISIBLE else View.GONE
            binding.resourceApplyDelivery.visibility=if(management&&ui.deliveryDefaultsAvailable)View.VISIBLE else View.GONE
            binding.resourceApplyDelivery.isEnabled=!ui.busy
            if(ui.settings!=null&&ui.settings!==settings){enabled=ui.enabled;settings=ui.settings;baseline=BeautyParameters.read(ui.settings);pendingManual.clear();changed(ui.settings,ui.enabled)}
            binding.resourceStatus.text=when {ui.error!=null->activity.getString(R.string.resources_error,ui.error);ui.busy->ui.progress?.let{activity.getString(R.string.resources_progress,it.stage.name,it.completed/1024)}?:activity.getString(R.string.resources_loading);!management->activity.getString(R.string.resources_camera_hint);else->activity.getString(R.string.resources_ready,ui.installed.size)}
            binding.resourceCancel.isEnabled=ui.busy&&ui.settings!=null
            orderChanged(ui.order)
            render(ui)
        }
    }
    fun refresh()=model.refresh()
    fun scene(value:BeautyScene,background:Boolean,motion:Boolean)=model.scene(value,background,motion)
    fun resetAll(){pendingManual.clear();model.resetDefaults()}
    fun resetCategory()=model.resetCategory(category)
    fun clearStickers()=model.clearStickers()
    fun enabled(value:Boolean)=model.enabled(value)
    fun category(value:ResourceCategory){category=value;render(model.ui.value?:ResourceCenterModel.Ui())}
    /** 仅更改UI实际修改的参数；加载资源时同步基线，不会清空其他部位效果。 */
    fun manual(settings:BeautySettings){val values=BeautyParameters.read(settings);val old=baseline;baseline=values;if(old!=null){val changes=values.filter{old[it.key]!=it.value};if(changes.isNotEmpty()){pendingManual.putAll(changes);model.parameters(pendingManual.toMap())}}}
    private var renderedKey:List<Any?>?=null
    private fun render(ui:ResourceCenterModel.Ui){
        val key=listOf(category,ui.installed,ui.online,ui.selection,ui.locale,ui.previews)
        if(key==renderedKey)return
        renderedKey=key
        binding.resourceGrid.removeAllViews()
        // 已删除且不在在线目录中的封面不再由页面持有，避免多次导入/删除后缓存持续增长。
        thumbnails.keys.retainAll(ui.installed.map{it.hash}.toSet()+ui.online.map{it.sha256})
        val local=ui.installed.filter{it.descriptor.category==category};val byHash=ui.installed.associateBy{it.hash}
        binding.resourceEmpty.visibility=if(local.isEmpty() && (!management || ui.online.none{it.manifest.category==category}))View.VISIBLE else View.GONE
        for(resource in local){val d=resource.descriptor;val text=d.localized(ui.locale?:activity.resources.configuration.locales[0].toLanguageTag());val active=ui.selection[d.slot]==resource.hash
            card(text.name,if(active)activity.getString(if(management)R.string.resources_selected else R.string.resources_tap_to_disable)else activity.getString(R.string.resources_installed),resource.hash,active){
                if(!management&&active)model.disable(d.slot)else model.select(resource,enableBeauty=!management&&category in setOf(ResourceCategory.BEAUTY,ResourceCategory.RESHAPE,ResourceCategory.MAKEUP,ResourceCategory.FILTER))
            }.also {card->
                val bitmap=thumbnails.getOrPut(resource.hash){modelPreview(requireNotNull(ui.previews[resource.hash]))}
                (card.getChildAt(0) as ImageView).setImageBitmap(bitmap)
                card.setOnLongClickListener{if(management)AlertDialog.Builder(activity).setItems(arrayOf(activity.getString(R.string.resources_disable),activity.getString(R.string.resources_delete))){_,i->if(i==0)model.disable(d.slot)else model.uninstall(resource)}.show() else model.disable(d.slot);true}
            }
        }
        for(entry in ui.online.filter{management&&it.manifest.category==category&&it.sha256 !in byHash}){val d=entry.manifest;val view=card(d.localized(ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()).name,activity.getString(R.string.resources_download),entry.sha256){model.download(entry)}
            ui.previews[entry.sha256]?.let{bytes->(view.getChildAt(0) as ImageView).setImageBitmap(thumbnails.getOrPut(entry.sha256){modelPreview(bytes)})}}
        val count=binding.resourceGrid.childCount
        if(count>0)repeat((3-count%3)%3) {offset ->
            binding.resourceGrid.addView(android.widget.Space(activity),GridLayout.LayoutParams().apply {
                width=0;height=0;columnSpec=GridLayout.spec((count+offset)%3,1f);rowSpec=GridLayout.spec((count+offset)/3)
            })
        }
    }
    // 封面只在列表配置时解码并缓存，不在相机回调读取；最高采样到256px。
    private fun modelPreview(bytes:ByteArray):android.graphics.Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1;while(bounds.outWidth/sample>256||bounds.outHeight/sample>256)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888)
    }
    private fun card(name:String,status:String,hash:String,selected:Boolean=false,click:()->Unit):LinearLayout {
        val density=activity.resources.displayMetrics.density
        val compact=!management || activity.resources.configuration.screenHeightDp<500
        val card=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding((8*density).toInt(),(8*density).toInt(),(8*density).toInt(),(8*density).toInt());setBackgroundResource(if(management)R.drawable.bg_camera_chip else R.drawable.bg_effect_parameter);isSelected=selected;minimumHeight=((if(!management)112 else if(compact)104 else 140)*density).toInt();isClickable=true;isFocusable=true;contentDescription=activity.getString(R.string.resources_card,name,status);tag="resource_$hash";setOnClickListener{click()};layoutParams=GridLayout.LayoutParams().apply{width=0;height=ViewGroup.LayoutParams.WRAP_CONTENT;columnSpec=GridLayout.spec(binding.resourceGrid.childCount%3,1f);rowSpec=GridLayout.spec(binding.resourceGrid.childCount/3);setMargins((3*density).toInt(),(4*density).toInt(),(3*density).toInt(),(4*density).toInt())}}
        // 浅色封面底板使透明黑色睫毛/胡子在深色面板上仍可辨认。
        card.addView(ImageView(activity).apply{setBackgroundColor(0xffedf2f7.toInt());scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=name;layoutParams=LinearLayout.LayoutParams(if(management)-1 else (72*density).toInt(),((if(management&&compact)48 else 72)*density).toInt()).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL}})
        card.addView(TextView(activity).apply{text=name;setPadding(0,(8*density).toInt(),0,0);setTextColor(androidx.core.content.ContextCompat.getColor(activity,if(selected)R.color.camera_accent else R.color.camera_text));textSize=12f;gravity=android.view.Gravity.CENTER;maxLines=if(compact)1 else 2;ellipsize=android.text.TextUtils.TruncateAt.END})
        card.addView(TextView(activity).apply{visibility=if(management)View.VISIBLE else View.GONE;text=status;setTextColor(androidx.core.content.ContextCompat.getColor(activity,if(selected)R.color.camera_accent else R.color.camera_muted));textSize=11f;gravity=android.view.Gravity.CENTER;maxLines=if(compact)1 else 2;ellipsize=android.text.TextUtils.TruncateAt.END})
        binding.resourceGrid.addView(card);return card
    }
}
