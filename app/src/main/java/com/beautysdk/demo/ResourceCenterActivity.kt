package com.beautysdk.demo

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.beautysdk.demo.databinding.ActivityResourceCenterBinding
import com.beautysdk.resources.ResourceCategory
import com.wkq.base.activity.BaseActivity

/** 独立资源管理页面；不创建相机、GL或模型。配置通过资源状态存储交给相机。 */
class ResourceCenterActivity : BaseActivity<ActivityResourceCenterBinding>() {
    private lateinit var center:ResourceCenterController
    private var online=false
    private var category=ResourceCategory.BEAUTY
    private var gestureSelected=false
    private var renderedGesture=false
    override fun onCreate(savedInstanceState:Bundle?){
        category=runCatching{ResourceCategory.valueOf(savedInstanceState?.getString("resource_category")?:"BEAUTY")}.getOrDefault(ResourceCategory.BEAUTY)
        gestureSelected=savedInstanceState?.getBoolean("resource_gesture",false)?:false
        online=savedInstanceState?.getBoolean("resource_online")?:savedOnline()
        super.onCreate(savedInstanceState)
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("resource_category",category.name);outState.putBoolean("resource_gesture",gestureSelected);outState.putBoolean("resource_online",online);super.onSaveInstanceState(outState)}
    override fun initView() {
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) {view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        binding.resourceBack.setOnClickListener {finish()}
        center=ResourceCenterController(this,binding.resourceCenter,management=true,orderChanged=::categories) {_,_->}
        center.category(category)
        center.start()
        binding.resourceAdd.setOnClickListener{center.importResource()}
        binding.resourceMore.setOnClickListener{center.showMore(it)}
        fun source(value:Boolean){online=value;binding.resourceLocalTab.isSelected=!value;binding.resourceOnlineTab.isSelected=value;center.online(value)}
        binding.resourceLocalTab.setOnClickListener{source(false)}
        binding.resourceOnlineTab.setOnClickListener{source(true)}
        center.presentation3D(getSharedPreferences("camera_stickers",MODE_PRIVATE).getInt("sticker_3d",0)!=0)
        source(online)
        categories(ResourceCategory.entries)
    }
    private fun savedOnline()=getPreferences(MODE_PRIVATE).getBoolean("online",false)
    override fun onStop(){getPreferences(MODE_PRIVATE).edit().putBoolean("online",online).apply();super.onStop()}
    private var renderedNames:List<String?> = emptyList()
    private var rendered:Pair<List<ResourceCategory>,ResourceCategory>?=null
    private fun categories(order:List<ResourceCategory>) {
        val hasGestures=::center.isInitialized&&center.hasGestureResources
        if(!hasGestures&&gestureSelected&&center.settings!=null){gestureSelected=false;center.category(category)}
        if(hasGestures&&gestureSelected)center.gestureCategory()
        val names=order.map{if(::center.isInitialized)center.categoryName(it)else null}+(if(hasGestures)listOf(center.gestureCategoryName())else emptyList())
        if(rendered==(order to category)&&renderedNames==names&&renderedGesture==gestureSelected)return
        renderedGesture=gestureSelected
        val sameNames=renderedNames==names
        renderedNames=names
        rendered=order to category
        val titles=listOf(R.string.effect_category_beauty,R.string.effect_category_reshape,R.string.effect_category_makeup,
            R.string.effect_category_color,R.string.effect_category_sticker,R.string.effect_category_background,
            R.string.effect_category_motion,R.string.resources_theme)
        if(sameNames&&binding.resourceCategories.childCount==names.size){
            for(i in order.indices)binding.resourceCategories.getChildAt(i).isSelected=!gestureSelected&&order[i]==category
            if(hasGestures)binding.resourceCategories.getChildAt(order.size).isSelected=gestureSelected
            return
        }
        binding.resourceCategories.removeAllViews()
        order.forEach {kind ->
            binding.resourceCategories.addView(TextView(this,null,0,R.style.EffectText).apply {
                text=(if(::center.isInitialized)center.categoryName(kind)else null)?:getString(titles[kind.ordinal]);isSelected=!gestureSelected&&kind==category
                alpha=1f
                val dp=resources.displayMetrics.density
                layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,(48*dp).toInt())
                setPadding((10*dp).toInt(),0,(10*dp).toInt(),0)
                setOnClickListener {gestureSelected=false;category=kind;center.category(kind);categories(order);binding.resourceCategories.post{isSelected=true;requestRectangleOnScreen(android.graphics.Rect(0,0,width,height),true)}}
            })
        }
        if(hasGestures)binding.resourceCategories.addView(TextView(this,null,0,R.style.EffectText).apply {
            text=center.gestureCategoryName();tag="gesture_category";isSelected=gestureSelected;alpha=1f
            val dp=resources.displayMetrics.density;layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,(48*dp).toInt());setPadding((10*dp).toInt(),0,(10*dp).toInt(),0)
            setOnClickListener{gestureSelected=true;center.gestureCategory();categories(order);binding.resourceCategories.post{binding.resourceCategories.getChildAt(binding.resourceCategories.childCount-1)?.requestRectangleOnScreen(android.graphics.Rect(0,0,width,height),true)}}
        })
    }
    override fun initData()=Unit
}
