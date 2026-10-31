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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.appcompat.widget.PopupMenu

/** 分类资源面板。资源管理与状态保存调用公共库；列表不参与视频帧线程。 */
class ResourceCenterController(private val activity:androidx.appcompat.app.AppCompatActivity,private val binding:ViewResourceCenterBinding,private val management:Boolean=false,private val orderChanged:(List<ResourceCategory>)->Unit={},private val changed:(BeautySettings,Boolean)->Unit) {
    private val model=ViewModelProvider(activity)[ResourceCenterModel::class.java]
    private var category=ResourceCategory.BEAUTY
    var onDisableCategory:((ResourceCategory)->Unit)?=null
    var onGestureApplied:(()->Unit)?=null
    var settings:BeautySettings?=null;private set
    var enabled=true;private set
    // View仍可能持有已逐出的Bitmap，不主动recycle；仅限制缓存保留的像素内存。
    private val thumbnails=object:LinkedHashMap<String,android.graphics.Bitmap>(16,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,android.graphics.Bitmap>?):Boolean =
            size>32 || values.sumOf{it.allocationByteCount.toLong()}>8L*1024*1024
    }
    private val picker=if(management)activity.registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(model::import)}else null
    private val libraryControls=ResourceParameterPanel(activity)
    private val cards=ResourceCardAdapter(management)
    private var onlineOnly=false
    private var suspended=false
    private var using3D=false
    private var detail:AlertDialog?=null
    private var pendingTransfer:String?=null
    private var renderedKey:List<Any?>?=null
    private var parametersOnly=false
    private var gestureCategorySelected=false
    private var lastError:String?=null
    init {
        binding.resourceManagement.visibility=View.GONE
        binding.resourceGrid.adapter=cards
        binding.resourceGrid.itemAnimator=null
        binding.resourceGrid.layoutManager=GridLayoutManager(activity,if(management)3 else 4)
        binding.resourceGrid.addItemDecoration(object:androidx.recyclerview.widget.RecyclerView.ItemDecoration(){
            override fun getItemOffsets(outRect:android.graphics.Rect,view:View,parent:androidx.recyclerview.widget.RecyclerView,state:androidx.recyclerview.widget.RecyclerView.State){
                val position=parent.getChildAdapterPosition(view)
                if(position==androidx.recyclerview.widget.RecyclerView.NO_POSITION)return
                val span=(parent.layoutManager as GridLayoutManager).spanCount
                val gap=(8*activity.resources.displayMetrics.density).toInt();val column=position%span
                // 外侧不再叠加边距，首末卡与面板16dp内容基线对齐。
                outRect.set(column*gap/span,0,gap-(column+1)*gap/span,0)
            }
        })
        binding.resourceGrid.addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->
            val dp=v.width/activity.resources.displayMetrics.density
            val largeFont=activity.resources.configuration.fontScale>1.15f
            val span=if(management)maxOf(2,(dp/(if(largeFont)116 else 100)).toInt())else maxOf(3,(dp/(if(largeFont)88 else 76)).toInt())
            (binding.resourceGrid.layoutManager as GridLayoutManager).let{if(it.spanCount!=span){it.spanCount=span;binding.resourceGrid.invalidateItemDecorations()}}
        }
        binding.resourceParameterHost.addView(libraryControls,android.widget.FrameLayout.LayoutParams(-1,-1))
        binding.resourceNone.visibility=View.GONE
        binding.resourceNone.setOnClickListener {if(gestureCategorySelected)model.clearGestureEffects()else model.disableCategory(category)}
        binding.resourceStatus.visibility=View.VISIBLE
        binding.resourceImport.setOnClickListener{picker?.launch(arrayOf("application/zip","application/octet-stream"))}
        binding.resourceGestureApply.setOnClickListener{model.applyGestureEffects()}
        binding.resourceGestureClear.setOnClickListener{model.clearGestureEffects()}
        binding.resourceApplyDelivery.setOnClickListener{model.applyDeliveryDefaults()}
        binding.resourceDefaults.setOnClickListener{model.resetDefaults()}
        binding.resourceReset.setOnClickListener{model.resetCategory(category)}
        binding.resourceCancel.setOnClickListener{model.cancel()}
        binding.resourceOfficial.setOnClickListener{model.downloadOfficial()}
        binding.resourceOnline.setOnClickListener{
            val input=EditText(activity).apply{setText(activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).getString("catalog_url",""));hint=activity.getString(R.string.resource_catalog_url);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
            AlertDialog.Builder(activity).setTitle(R.string.resources_online).setView(input).setPositiveButton(android.R.string.ok){_,_->val url=input.text.toString().trim();activity.getSharedPreferences("resource_network",android.content.Context.MODE_PRIVATE).edit().putString("catalog_url",url).apply();model.catalog(url)}.setNegativeButton(android.R.string.cancel,null).show()
        }
    }
    private var started=false
    /** 宿主完成字段赋值后订阅，避免LiveData立即回放时访问未初始化的控制器。 */
    fun start(){
        if(started)return
        started=true
        model.ui.observe(activity){ui->
            if(!ui.busy)pendingTransfer=null
            binding.resourceDeliveryReceipt.visibility=View.GONE
            binding.resourceDeliveryReceipt.text=ui.lastImportedDeliverySha256?.let{activity.getString(R.string.resources_delivery_receipt,it)}
            binding.resourceOfficial.isEnabled=!ui.busy
            binding.resourceGestureApply.isEnabled=!ui.busy
            binding.resourceGestureClear.isEnabled=!ui.busy
            binding.resourceGestureApply.visibility=if(management&&ui.pendingGestureEffects)View.VISIBLE else View.GONE
            binding.resourceGestureClear.visibility=if(management&&ui.gestureEffects!=null)View.VISIBLE else View.GONE
            binding.resourceGestureInfo.visibility=if(management&&ui.gestureEffects!=null)View.VISIBLE else View.GONE
            binding.resourceGestureInfo.text=activity.getString(R.string.resources_gesture_info,ui.gestureEffects?.rules?.size?:0)
            // 相机内的保存/生效状态不插入内容流，避免每次调参重新挤压滑杆和卡片。
            binding.resourceStatus.visibility=if(management)View.VISIBLE else View.GONE
            if(!management&&ui.error!=null&&ui.error!=lastError)Toast.makeText(activity,ui.error,Toast.LENGTH_LONG).show()
            lastError=ui.error
            binding.resourceApplyDelivery.visibility=if(management&&ui.deliveryDefaultsAvailable)View.VISIBLE else View.GONE
            binding.resourceApplyDelivery.isEnabled=!ui.busy
            if(ui.settings!=null&&ui.settings!==settings){enabled=ui.enabled;settings=ui.settings;changed(ui.settings,ui.enabled)}
            binding.resourceStatus.text=when {ui.error!=null->activity.getString(R.string.ui_operation_failed);ui.busy->ui.progress?.let{activity.getString(R.string.ui_downloading,it.completed/1024)}?:activity.getString(R.string.resources_loading);!management->activity.getString(R.string.resources_camera_hint);else->activity.getString(R.string.ui_resource_summary,ui.installed.size)}
            binding.resourceStatus.setOnClickListener{if(ui.error!=null)AlertDialog.Builder(activity).setTitle(R.string.ui_operation_failed).setMessage(ui.error).setPositiveButton(android.R.string.ok,null).show()}
            binding.resourceStatus.isClickable=ui.error!=null
            activity.findViewById<View>(R.id.resource_add)?.isEnabled=!ui.busy
            binding.resourceStatus.maxLines=if(activity.resources.configuration.screenHeightDp<480)1 else 2
            binding.resourceStatus.ellipsize=android.text.TextUtils.TruncateAt.END
            binding.resourceStatus.setPadding(0,0,0,(4*activity.resources.displayMetrics.density).toInt())
            binding.resourceCancel.isEnabled=ui.busy
            binding.resourceCancel.visibility=if(ui.busy)View.VISIBLE else View.GONE
            binding.resourceManagement.visibility=View.GONE
            listOf(binding.resourceOfficial,binding.resourceImport,binding.resourceOnline,binding.resourceReset,binding.resourceDefaults,binding.resourceGestureClear).forEach{it.visibility=View.GONE}
            binding.resourceEmpty.isClickable=onlineOnly
            binding.resourceEmpty.setOnClickListener{if(onlineOnly)binding.resourceOnline.performClick()}
            orderChanged(ui.order)
            if(ui.library!=null&&renderedKey!=null)updateLibraryValues(ui)
            render(ui)
        }
    }
    fun refresh()=model.refresh()
    fun scene(value:BeautyScene,background:Boolean,motion:Boolean)=model.scene(value,background,motion)
    fun resetAll(){model.resetDefaults()}
    fun resetCategory()=model.resetCategory(category)
    fun clearStickers()=model.clearStickers()
    fun enabled(value:Boolean)=model.enabled(value)
    fun category(value:ResourceCategory){val different=category!=value||gestureCategorySelected;gestureCategorySelected=false;category=value;render(model.ui.value?:ResourceCenterModel.Ui());if(different)binding.resourceGrid.scrollToPosition(0)}
    fun gestureCategory(){gestureCategorySelected=true;render(model.ui.value?:ResourceCenterModel.Ui())}
    val hasGestureResources:Boolean get()=model.ui.value?.let{it.gestureResources.isNotEmpty()||management&&it.onlineGestures.isNotEmpty()}==true
    fun gestureCategoryName():String {
        val ui=model.ui.value;val library=ui?.library;val category=library?.gestureCategory
        return if(library!=null&&category!=null)library.text(category.nameKey,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag())else activity.getString(R.string.resources_gesture_category)
    }
    fun importResource(){binding.resourceImport.performClick()}
    fun online(value:Boolean){if(onlineOnly!=value){onlineOnly=value;renderedKey=null;render(model.ui.value?:ResourceCenterModel.Ui());binding.resourceGrid.scrollToPosition(0)}}
    fun showMore(anchor:View){
        val popup=PopupMenu(activity,anchor)
        val ui=model.ui.value
        val actions=mutableListOf<Pair<Int,()->Unit>>(
            R.string.resources_official_download to {binding.resourceOfficial.performClick();Unit},
            R.string.resources_online to {binding.resourceOnline.performClick();Unit},
            R.string.resources_defaults to {confirm(R.string.ui_reset_all_message){model.resetDefaults()}},
            R.string.ui_import_details to {AlertDialog.Builder(activity).setTitle(R.string.ui_import_details).setMessage(binding.resourceDeliveryReceipt.text.takeIf{it.isNotBlank()}?:activity.getString(R.string.ui_no_import)).setPositiveButton(android.R.string.ok,null).show();Unit}
        )
        if(ui?.deliveryDefaultsAvailable==true)actions.add(0,R.string.resources_apply_delivery to {model.applyDeliveryDefaults()})
        if(ui?.pendingGestureEffects==true)actions.add(0,R.string.resources_gesture_apply to {model.applyGestureEffects()})
        if(ui?.gestureEffects!=null)actions.add(R.string.resources_gesture_clear to {model.clearGestureEffects()})
        if(ui?.busy==true)actions.add(android.R.string.cancel to {model.cancel()})
        actions.forEachIndexed{i,item->popup.menu.add(0,i,i,item.first).isEnabled=ui?.busy!=true||item.first in listOf(android.R.string.cancel,R.string.ui_import_details)}
        popup.setOnMenuItemClickListener{actions[it.itemId].second();true};popup.show()
    }
    fun presentation3D(active:Boolean){
        val pause=active&&category in setOf(ResourceCategory.STICKER,ResourceCategory.RESHAPE)
        if(using3D!=active||suspended!=pause){using3D=active;suspended=pause;renderedKey=null;render(model.ui.value?:ResourceCenterModel.Ui())}
    }
    private fun confirm(message:Int,action:()->Unit){AlertDialog.Builder(activity).setMessage(message).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok){_,_->action()}.show()}
    private fun details(name:String,status:String,bitmap:android.graphics.Bitmap?,selected:Boolean,apply:()->Unit,disable:(()->Unit)?,delete:(()->Unit)?,hash:String?=null) {
        detail?.dismiss()
        val dp=activity.resources.displayMetrics.density
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding((24*dp).toInt(),(8*dp).toInt(),(24*dp).toInt(),0)}
        content.addView(ImageView(activity).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundResource(R.drawable.bg_preset_preview)},LinearLayout.LayoutParams(-1,(180*dp).toInt()))
        content.addView(TextView(activity).apply{text=status;setTextAppearance(R.style.CameraLabel)},LinearLayout.LayoutParams(-1,-2))
        if(disable!=null)content.addView(Button(activity).apply{setText(R.string.resources_disable);isEnabled=selected;setOnClickListener{detail?.dismiss();disable()}},LinearLayout.LayoutParams(-1,-2))
        if(hash!=null)content.addView(TextView(activity).apply{
            setTextAppearance(R.style.CameraLabel);setText(R.string.ui_resource_information);minHeight=(48*dp).toInt();isClickable=true;isFocusable=true
            setOnClickListener{text=activity.getString(R.string.ui_resource_digest,hash);isClickable=false;setTextIsSelectable(true)}
        },LinearLayout.LayoutParams(-1,-2))
        detail=AlertDialog.Builder(activity).setTitle(name).setView(ScrollView(activity).apply{addView(content)}).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(if(delete==null)R.string.resources_download else R.string.ui_use){_,_->apply()}
            .apply{if(management&&delete!=null)setNeutralButton(R.string.resources_delete){_,_->confirm(if(selected)R.string.ui_delete_active else R.string.ui_delete_message,delete)}}.create()
        detail?.show()
        if(model.ui.value?.busy==true){detail?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=false;detail?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled=false}
    }
    private fun renderGestureResources(ui:ResourceCenterModel.Ui){
        binding.resourceParameterHost.visibility=View.GONE;binding.resourceGrid.visibility=View.VISIBLE
        val locale=ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()
        val order=ui.library?.gestureResources?.mapIndexed{i,r->r.sha256 to i}?.toMap().orEmpty()
        val onlineHashes=ui.onlineGestures.map{it.descriptor.sha256}.toSet()
        val rows=mutableListOf<ResourceCard>()
        if(!management)rows+=ResourceCard("none",activity.getString(R.string.ui_none),activity.getString(R.string.ui_disable_category),ui.gestureEffects==null,null,{model.clearGestureEffects()})
        for(resource in ui.gestureResources.filter{!onlineOnly||it.hash in onlineHashes}.sortedBy{order[it.hash]?:Int.MAX_VALUE}){
            val active=resource.hash in ui.gestureSelection
            val name=(ui.library?.gestureResources?.firstOrNull{it.sha256==resource.hash}?:resource.descriptor).localizedName(locale,ui.library?.defaultLocale?:"zh-CN")
            val status=activity.getString(if(active)R.string.ui_in_use else R.string.ui_installed)
            val bitmap=ui.gesturePreviews[resource.hash]?.let{thumbnails.getOrPut(resource.hash){modelPreview(it)}}
            val apply={if(!active)model.selectGesture(resource);onGestureApplied?.invoke();Unit}
            val rules=org.json.JSONArray(resource.descriptor.rulesJson)
            val labels=activity.resources.getStringArray(R.array.gesture_names)
            val triggers=(0 until rules.length()).map{labels[GestureKind.valueOf(rules.getJSONObject(it).getString("gesture")).ordinal]}.joinToString(activity.getString(R.string.resources_list_separator))
            val description=activity.getString(R.string.ui_gesture_description,status,triggers)
            val more={details(name,description,bitmap,active,apply,{if(active)model.selectGesture(resource)},{model.deleteGesture(resource)},resource.hash);Unit}
            rows+=ResourceCard(resource.hash,name,status,active,bitmap,if(management)more else apply,more)
        }
        if(management&&onlineOnly)for(entry in ui.onlineGestures.filter{e->ui.gestureResources.none{it.hash==e.descriptor.sha256}}){
            val d=entry.descriptor;val name=d.localizedName(locale);val status=transferStatus(d.sha256,ui)
            val bitmap=ui.gesturePreviews[d.sha256]?.let{thumbnails.getOrPut(d.sha256){modelPreview(it)}}
            rows+=ResourceCard(d.sha256,name,status,false,bitmap,{details(name,status,bitmap,false,{pendingTransfer=d.sha256;model.downloadGesture(entry)},null,null,d.sha256)})
        }
        submit(rows)
    }
    /** 显式提交一个字段；无关开关和页面重绘不再反向写入全量参数。 */
    fun parameter(key:String,value:Float){model.parameters(mapOf(key to value))}
    fun parameterMode(value:Boolean){if(parametersOnly!=value){parametersOnly=value;renderedKey=null;render(model.ui.value?:ResourceCenterModel.Ui())}}
    val hasLibrary:Boolean get()=model.ui.value?.library!=null
    fun categoryName(value:ResourceCategory):String? {
        val ui=model.ui.value?:return null;val library=ui.library?:return null
        val c=library.categories.firstOrNull{it.id==value}?:return null
        return library.text(c.nameKey,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag())
    }
    private fun updateLibraryValues(ui:ResourceCenterModel.Ui) {
        val library=ui.library?:return
        libraryControls.render(library,category,ui.locale?:activity.resources.configuration.locales[0].toLanguageTag(),ui.settings?.let(BeautyParameters::read).orEmpty(),model::parameters,ui.settings?.adjustments?.makeupColors?:com.beautysdk.MakeupColors(),model::makeupColor)
    }
    private fun renderLibraryControls(ui:ResourceCenterModel.Ui) {
        binding.resourceParameterHost.visibility=if(!management&&ui.library!=null&&parametersOnly)View.VISIBLE else View.GONE
        if(binding.resourceParameterHost.visibility==View.VISIBLE)updateLibraryValues(ui)
    }
    private fun transferStatus(hash:String,ui:ResourceCenterModel.Ui):String = if(ui.busy&&pendingTransfer==hash)ui.progress?.let{activity.getString(R.string.ui_downloading,it.completed/1024)}?:activity.getString(R.string.resources_loading)else activity.getString(R.string.resources_download)
    private fun submit(rows:List<ResourceCard>){
        cards.submitList(rows)
        binding.resourceEmpty.visibility=if(rows.isEmpty()&&binding.resourceParameterHost.visibility!=View.VISIBLE)View.VISIBLE else View.GONE
        binding.resourceEmpty.setText(if(onlineOnly)R.string.ui_online_empty else R.string.resources_empty)
    }
    private fun render(ui:ResourceCenterModel.Ui){
        suspended=using3D&&category in setOf(ResourceCategory.STICKER,ResourceCategory.RESHAPE)
        val key=listOf(ui.settings,category,ui.installed,ui.online,ui.selection,ui.locale,ui.previews,ui.library,parametersOnly,gestureCategorySelected,ui.gestureResources,ui.gestureSelection,ui.gesturePreviews,ui.onlineGestures,onlineOnly,suspended,ui.enabled,ui.busy,ui.progress,pendingTransfer)
        if(key==renderedKey)return
        renderedKey=key
        thumbnails.keys.retainAll(ui.installed.map{it.hash}.toSet()+ui.online.map{it.sha256}+ui.gestureResources.map{it.hash}+ui.onlineGestures.map{it.descriptor.sha256})
        if(gestureCategorySelected){renderGestureResources(ui);return}
        renderLibraryControls(ui)
        binding.resourceGrid.visibility=if(!management&&parametersOnly&&ui.library!=null)View.GONE else View.VISIBLE
        val resourceOrder=ui.library?.resources?.mapIndexed{i,r->r.id to i}?.toMap().orEmpty()
        val onlineHashes=ui.online.map{it.sha256}.toSet()
        val local=ui.installed.filter{it.descriptor.category==category&&(!onlineOnly||it.hash in onlineHashes)}.sortedBy{resourceOrder[it.descriptor.id]?:Int.MAX_VALUE}
        val rows=mutableListOf<ResourceCard>()
        val locale=ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()
        val names=local.associate{resource->resource.hash to (ui.library?.let{library->library.resources.firstOrNull{it.id==resource.descriptor.id}?.let{library.resourceName(it,locale)}}?:resource.descriptor.localized(locale).name)}
        val duplicates=names.values.groupingBy{it}.eachCount()
        val nameIndices=mutableMapOf<String,Int>()
        if(!management&&!parametersOnly){
            val settings=ui.settings
            val neutral=BeautyParameters.neutral.filterKeys{BeautyParameters.category(it)==category}
            val values=settings?.let(BeautyParameters::read).orEmpty()
            val noSelection=local.none{ui.selection[it.descriptor.slot]!=null}
            val sceneOff=when(category){
                ResourceCategory.BACKGROUND->settings?.scene?.background==BackgroundMode.NONE
                ResourceCategory.MOTION->settings?.scene?.motion==CameraMotion.NONE
                ResourceCategory.STICKER->settings!=null&&!using3D&&!settings.glasses&&!settings.moustache&&!settings.hat&&settings.stickerPack==null
                else->true
            }
            val off=settings!=null&&noSelection&&sceneOff&&neutral.all{(key,value)->values[key]==value}
            rows+=ResourceCard("none",activity.getString(R.string.ui_none),activity.getString(R.string.ui_disable_category),off,null,{onDisableCategory?.invoke(category);model.disableCategory(category)})
        }
        for(resource in local){
            val d=resource.descriptor
            val baseName=names.getValue(resource.hash)
            val index=(nameIndices[baseName]?:0)+1;nameIndices[baseName]=index
            val name=if((duplicates[baseName]?:0)>1)activity.getString(R.string.ui_resource_variant,baseName,index)else baseName
            val active=ui.selection[d.slot]==resource.hash
            val status=activity.getString(if(active&&(suspended||!ui.enabled))R.string.ui_paused else if(active)R.string.ui_in_use else R.string.ui_installed)
            val bitmap=ui.previews[resource.hash]?.let{thumbnails.getOrPut(resource.hash){modelPreview(it)}}
            val apply={
                if(!management)d.scope.singleOrNull()?.let(libraryControls::focusParameter)
                val action={model.select(resource,enableBeauty=category in setOf(ResourceCategory.BEAUTY,ResourceCategory.RESHAPE,ResourceCategory.MAKEUP,ResourceCategory.FILTER),toggleSticker=!management&&category==ResourceCategory.STICKER,toggleMakeup=!management&&category==ResourceCategory.MAKEUP);Unit}
                val applyAndNotify={action();if(!management&&category!=ResourceCategory.STICKER&&!(active&&category==ResourceCategory.MAKEUP))Toast.makeText(activity,R.string.ui_effect_applied,Toast.LENGTH_SHORT).show();Unit}
                if(category==ResourceCategory.THEME)confirm(R.string.ui_theme_replace,applyAndNotify)else applyAndNotify()
            }
            val more={details(name,status,bitmap,active,apply,{model.disable(d.slot)},{model.uninstall(resource)},resource.hash);Unit}
            val symbol=when(d.id){
                "beauty_daily"->R.drawable.ic_effect_smooth
                "beauty_clear"->R.drawable.ic_preset_clear
                "beauty_soft"->R.drawable.ic_preset_soft
                "beauty_bare","beauty_nude"->R.drawable.ic_preset_bare
                "beauty_fresh","beauty_vital"->R.drawable.ic_preset_fresh
                else->0
            }
            rows+=ResourceCard(resource.hash,name,status,active,bitmap,if(management)more else apply,more,symbol)
        }
        if(management&&onlineOnly)for(entry in ui.online.filter{e->e.manifest.category==category&&ui.installed.none{it.hash==e.sha256}}){
            val name=entry.manifest.localized(ui.locale?:activity.resources.configuration.locales[0].toLanguageTag()).name
            val bitmap=ui.previews[entry.sha256]?.let{thumbnails.getOrPut(entry.sha256){modelPreview(it)}}
            val status=transferStatus(entry.sha256,ui)
            rows+=ResourceCard(entry.sha256,name,status,false,bitmap,{details(name,status,bitmap,false,{pendingTransfer=entry.sha256;model.download(entry)},null,null,entry.sha256)})
        }
        submit(rows)
    }
    // 封面只在列表配置时解码并缓存，不在相机回调读取；最高采样到256px。
    private fun modelPreview(bytes:ByteArray):android.graphics.Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1;while(bounds.outWidth/sample>256||bounds.outHeight/sample>256)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888)
    }
}
