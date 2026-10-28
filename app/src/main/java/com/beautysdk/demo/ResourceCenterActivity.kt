package com.beautysdk.demo

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
    private var category=ResourceCategory.BEAUTY
    private var gestureSelected=false
    private var renderedGesture=false
    override fun initView() {
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) {view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        binding.resourceBack.setOnClickListener {finish()}
        center=ResourceCenterController(this,binding.resourceCenter,management=true,orderChanged=::categories) {_,_->}
        categories(ResourceCategory.entries)
    }
    private var renderedNames:List<String?> = emptyList()
    private var rendered:Pair<List<ResourceCategory>,ResourceCategory>?=null
    private fun categories(order:List<ResourceCategory>) {
        val hasGestures=::center.isInitialized&&center.hasGestureResources
        if(!hasGestures&&gestureSelected){gestureSelected=false;center.category(category)}
        val names=order.map{if(::center.isInitialized)center.categoryName(it)else null}+(if(hasGestures)listOf(center.gestureCategoryName())else emptyList())
        if(rendered==(order to category)&&renderedNames==names&&renderedGesture==gestureSelected)return
        renderedGesture=gestureSelected
        renderedNames=names
        rendered=order to category
        val titles=listOf(R.string.effect_category_beauty,R.string.effect_category_reshape,R.string.effect_category_makeup,
            R.string.effect_category_color,R.string.effect_category_sticker,R.string.effect_category_background,
            R.string.effect_category_motion,R.string.resources_theme)
        binding.resourceCategories.removeAllViews()
        order.forEach {kind ->
            binding.resourceCategories.addView(TextView(this,null,0,R.style.CameraAction).apply {
                text=(if(::center.isInitialized)center.categoryName(kind)else null)?:getString(titles[kind.ordinal]);isSelected=!gestureSelected&&kind==category
                alpha=if(isSelected)1f else .5f
                val dp=resources.displayMetrics.density
                layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,(48*dp).toInt())
                setPadding((16*dp).toInt(),0,(16*dp).toInt(),0)
                setOnClickListener {gestureSelected=false;category=kind;center.category(kind);categories(order)}
            })
        }
        if(hasGestures)binding.resourceCategories.addView(TextView(this,null,0,R.style.CameraAction).apply {
            text=center.gestureCategoryName();tag="gesture_category";isSelected=gestureSelected;alpha=if(isSelected)1f else .5f
            val dp=resources.displayMetrics.density;layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,(48*dp).toInt());setPadding((16*dp).toInt(),0,(16*dp).toInt(),0)
            setOnClickListener{gestureSelected=true;center.gestureCategory();categories(order)}
        })
    }
    override fun initData()=Unit
}
