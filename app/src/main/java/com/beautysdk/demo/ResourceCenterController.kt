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
    private val picker=if(management)activity.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(model::import)}else null
    private val libraryControls=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    private var renderedKey:List<Any?>?=null
    private var parametersOnly=false
    private var gestureCategorySelected=false
    init {
        binding.resourceManagement.visibility=if(management)View.VISIBLE else View.GONE
        binding.resourceNone.visibility=if(management)View.GONE else View.VISIBLE
        binding.resourceNone.setOnClickListener {model.disableCategory(category)}
        binding.resourceStatus.visibility=View.VISIBLE
        binding.resourceImport.setOnClickListener{picker?.launch(arrayOf("application/zip","application/octet-stream"))}
        binding.resourceGestureApply.setOnClickListener{model.applyGestureEffects()}
        binding.resourceGestureClear.setOnClickListener{model.clearGestureEffects()}
        binding.resourceApplyDelivery.setOnClickListener{model.applyDeliveryDefaults()}
        binding.resourceDefaults.setOnClickListener{model.resetDefaults()}
        binding.resourceReset.setOnClickListener{model.resetCategory(category)}
        binding.resourceCancel.setOnClickListener{model.cancel()}
        binding.resourceOnline.setOnClickListener{
            val input=EditText(activity).apply{setText(activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).getString("catalog_url",""));hint=activity.getString(R.string.resource_catalog_url);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
            AlertDialog.Builder(activity).setTitle(R.string.resources_online).setView(input).setPositiveButton(android.R.string.ok){_,_->val url=input.text.toString().trim();activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).edit().putString("catalog_url",url).apply();model.catalog(url)}.setNegativeButton(android.R.string.cancel,null).show()
        }
        model.ui.observe(activity){ui->
            binding.resourceGestureApply.isEnabled=!ui.busy
            binding.resourceGestureClear.isEnabled=!ui.busy
            binding.resourceGestureApply.visibility=if(management&&ui.pendingGestureEffects)View.VISIBLE else View.GONE
            binding.resourceGestureClear.visibility=if(management&&ui.gestureEffects!=null)View.VISIBLE else View.GONE
            binding.resourceGestureInfo.visibility=if(management&&ui.gestureEffects!=null)View.VISIBLE else View.GONE
            binding.resourceGestureInfo.text=activity.getString(R.string.resources_gesture_info,ui.gestureEffects?.rules?.size?:0)
            binding.resourceStatus.visibility=if(management||ui.busy||ui.error!=null)View.VISIBLE else View.GONE
            binding.resourceApplyDelivery.visibility=if(management&&ui.deliveryDefaultsAvailable)View.VISIBLE else View.GONE
            binding.resourceApplyDelivery.isEnabled=!ui.busy
            if(ui.settings!=null&&ui.settings!==settings){enabled=ui.enabled;settings=ui.settings;baseline=BeautyParameters.read(ui.settings);pendingManual.clear();changed(ui.settings,ui.enabled)}
            binding.resourceStatus.text=when {ui.error!=null->activity.getString(R.string.resources_error,ui.error);ui.busy->ui.progress?.let{activity.getString(R.string.resources_progress,it.stage.name,it.completed/1024)}?:activity.getString(R.string.resources_loading);!management->activity.getString(R.string.resources_camera_hint);else->activity.getString(R.string.resources_ready,ui.installed.size)}
            binding.resourceCancel.isEnabled=ui.busy&&ui.settings!=null
            orderChanged(ui.order)
            if(ui.library!=null&&renderedKey!=null)updateLibraryValues(ui)
            render(ui)
        }
    }
    fun refresh()=model.refresh()
    fun scene(value:BeautyScene,background:Boolean,motion:Boolean)=model.scene(value,background,motion)
    fun resetAll(){pendingManual.clear();model.resetDefaults()}
    fun resetCategory()=model.resetCategory(category)
    fun clearStickers()=model.clearStickers()
    fun enabled(value:Boolean)=model.enabled(value)
    fun category(value:ResourceCategory){gestureCategorySelected=false;category=value;render(model.ui.value?:ResourceCenterModel.Ui())}
    fun gestureCategory(){gestureCategorySelected=true;render(model.ui.value?:ResourceCenterModel.Ui())}
    val hasGestureResources:Boolean get()=model.ui.value?.gestureResources?.isNotEmpty()==true
    fun gestureCategoryName():String {
        val ui=model.ui.value;val library=ui?.library;val category=library?.gestureCategory
        return if(library!=null&&category!=null)library.text(category.nameKey,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag())else activity.getString(R.string.resources_gesture_category)
    }
    private fun renderGestureResources(ui:ResourceCenterModel.Ui){
        libraryControls.visibility=View.GONE;binding.resourceGrid.visibility=View.VISIBLE
        binding.resourceEmpty.visibility=if(ui.gestureResources.isEmpty())View.VISIBLE else View.GONE
        val locale=ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()
        val order=ui.library?.gestureResources?.mapIndexed{i,r->r.sha256 to i}?.toMap().orEmpty()
        for(resource in ui.gestureResources.sortedBy{order[it.hash]?:Int.MAX_VALUE}){
            val active=resource.hash in ui.gestureSelection;val name=(ui.library?.gestureResources?.firstOrNull{it.sha256==resource.hash}?:resource.descriptor).localizedName(locale,ui.library?.defaultLocale?:"zh-CN")
            val rules=org.json.JSONArray(resource.descriptor.rulesJson)
            val labels=activity.resources.getStringArray(R.array.gesture_names)
            val triggers=(0 until rules.length()).map{labels[GestureKind.valueOf(rules.getJSONObject(it).getString("gesture")).ordinal]}.joinToString(activity.getString(R.string.resources_list_separator))
            val status=activity.getString(if(active)R.string.resources_gesture_active else R.string.resources_gesture_available,triggers)
            card(name,status,resource.hash,active){model.selectGesture(resource)}.also{view->
                view.tag="gesture_resource_${resource.hash}"
                ui.gesturePreviews[resource.hash]?.let{data->(view.getChildAt(0) as ImageView).setImageBitmap(thumbnails.getOrPut(resource.hash){modelPreview(data)})}
                view.setOnLongClickListener {AlertDialog.Builder(activity).setItems(arrayOf(activity.getString(R.string.resources_delete))){_,_->model.deleteGesture(resource)}.show();true}
            }
        }
    }
    /** 仅更改UI实际修改的参数；加载资源时同步基线，不会清空其他部位效果。 */
    fun manual(settings:BeautySettings){val values=BeautyParameters.read(settings);val old=baseline;baseline=values;if(old!=null){val changes=values.filter{old[it.key]!=it.value};if(changes.isNotEmpty()){pendingManual.putAll(changes);model.parameters(pendingManual.toMap())}}}
    fun parameterMode(value:Boolean){if(parametersOnly!=value){parametersOnly=value;renderedKey=null;render(model.ui.value?:ResourceCenterModel.Ui())}}
    val hasLibrary:Boolean get()=model.ui.value?.library!=null
    fun categoryName(value:ResourceCategory):String? {
        val ui=model.ui.value?:return null;val library=ui.library?:return null
        val c=library.categories.firstOrNull{it.id==value}?:return null
        return library.text(c.nameKey,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag())
    }
    private fun updateLibraryValues(ui:ResourceCenterModel.Ui) {
        val values=ui.settings?.let(BeautyParameters::read)?:return
        val library=ui.library?:return;val locale=ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()
        val parameters=library.categories.firstOrNull{it.id==category}?.effects.orEmpty().flatMap{e->e.parameters.map{e to it}}
        parameters.forEachIndexed {i,(effect,p)->
            val bar=libraryControls.getChildAt(i*2+1) as? SeekBar?:return@forEachIndexed
            val value=values[p.id]?:p.default
            if(!bar.isPressed)bar.progress=((value-p.min)/p.step).toInt().coerceIn(0,bar.max)
            (libraryControls.getChildAt(i*2) as? TextView)?.text=activity.getString(R.string.resource_parameter_value,library.text(effect.nameKey,locale),library.text(p.nameKey,locale),value)
        }
    }
    private fun renderLibraryControls(ui:ResourceCenterModel.Ui) {
        if(libraryControls.parent==null)(binding.resourceGrid.parent as ViewGroup).addView(libraryControls,0)
        libraryControls.removeAllViews()
        val library=ui.library
        libraryControls.visibility=if(!management&&library!=null&&parametersOnly)View.VISIBLE else View.GONE
        if(management||library==null)return
        val locale=ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()
        for(effect in library.categories.firstOrNull{it.id==category}?.effects.orEmpty())for(p in effect.parameters) {
            val label=TextView(activity).apply {setTextAppearance(R.style.EffectParameter);maxLines=2}
            val bar=SeekBar(activity).apply {max=kotlin.math.ceil((p.max-p.min)/p.step).toInt()}
            fun display(value:Float){label.text=activity.getString(R.string.resource_parameter_value,library.text(effect.nameKey,locale),library.text(p.nameKey,locale),value)}
            val value=ui.settings?.let{BeautyParameters.read(it)[p.id]}?:p.default
            display(value);bar.progress=((value-p.min)/p.step).toInt().coerceIn(0,bar.max)
            bar.tag="library_parameter_${p.id}"
            bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
                override fun onStopTrackingTouch(seekBar:SeekBar?)=Unit
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser){val next=(p.min+progress*p.step).coerceIn(p.min,p.max);p.validate(next);display(next);model.parameters(mapOf(p.id to next))}}
            })
            libraryControls.addView(label);libraryControls.addView(bar)
        }
        val parameters=library.parameters(category)
        if(parameters.isNotEmpty())libraryControls.addView(Button(activity).apply {setText(R.string.resource_parameter_defaults);setOnClickListener{model.parameters(parameters.associate{it.id to it.default})}})
    }
    private fun render(ui:ResourceCenterModel.Ui){
        val key=listOf(category,ui.installed,ui.online,ui.selection,ui.locale,ui.previews,ui.library,parametersOnly,gestureCategorySelected,ui.gestureResources,ui.gestureSelection,ui.gesturePreviews)
        if(key==renderedKey)return
        renderedKey=key
        binding.resourceGrid.removeAllViews()
        // 已删除且不在在线目录中的封面不再由页面持有，避免多次导入/删除后缓存持续增长。
        thumbnails.keys.retainAll(ui.installed.map{it.hash}.toSet()+ui.online.map{it.sha256}+ui.gestureResources.map{it.hash})
        if(gestureCategorySelected){renderGestureResources(ui);return}
        renderLibraryControls(ui)
        binding.resourceGrid.visibility=if(!management&&parametersOnly&&ui.library!=null)View.GONE else View.VISIBLE
        val resourceOrder=ui.library?.resources?.mapIndexed{i,r->r.id to i}?.toMap().orEmpty()
        val local=ui.installed.filter{it.descriptor.category==category}.sortedBy{resourceOrder[it.descriptor.id]?:Int.MAX_VALUE};val byHash=ui.installed.associateBy{it.hash}
        binding.resourceEmpty.visibility=if(!(parametersOnly&&ui.library!=null)&&local.isEmpty() && (!management || ui.online.none{it.manifest.category==category}))View.VISIBLE else View.GONE
        for(resource in local){val d=resource.descriptor;val text=d.localized(ui.locale?:activity.resources.configuration.locales[0].toLanguageTag());val active=ui.selection[d.slot]==resource.hash
            val displayName=ui.library?.let{library->library.resources.firstOrNull{it.id==d.id}?.let{library.resourceName(it,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag())}}?:text.name
            card(displayName,if(active)activity.getString(if(management)R.string.resources_selected else R.string.resources_tap_to_disable)else activity.getString(R.string.resources_installed),resource.hash,active){
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
