package com.beautysdk.demo

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 只管理弹框的窗口、尺寸和挂载寿命；资源选择与视频状态不进入这里。 */
internal class EffectPanelHost(
    private val activity:AppCompatActivity,
    private val root:ViewGroup,
    private val panel:View,
    private val scroll:View,
    private val dismissed:()->Unit,
) {
    var dialog:BottomSheetDialog?=null;private set
    fun show() {
        if(dialog?.isShowing==true){resize();return}
        (panel.parent as? ViewGroup)?.removeView(panel)
        panel.visibility=View.VISIBLE;scroll.visibility=View.VISIBLE
        val next=BottomSheetDialog(activity);dialog=next
        next.setContentView(panel)
        next.behavior.isDraggable=false;next.behavior.skipCollapsed=true
        next.behavior.state=BottomSheetBehavior.STATE_EXPANDED
        scroll.isNestedScrollingEnabled=false
        resize();next.setOnShowListener{resize()}
        next.setOnDismissListener {
            (panel.parent as? ViewGroup)?.removeView(panel)
            panel.visibility=View.GONE;scroll.visibility=View.GONE
            root.addView(panel);dialog=null;dismissed()
        }
        next.window?.setDimAmount(.12f);next.show()
        next.window?.navigationBarColor=ContextCompat.getColor(activity,R.color.camera_surface)
        ViewCompat.setOnApplyWindowInsetsListener(panel){view,insets->
            val bottom=insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom+(8*activity.resources.displayMetrics.density).toInt()
            if(view.paddingBottom!=bottom)view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,bottom)
            insets
        }
        ViewCompat.requestApplyInsets(panel)
    }
    fun resize() {
        val current=dialog?:return
        if(root.height<=0){root.doOnLayout{resize()};return}
        val landscape=root.width>root.height;val dp=activity.resources.displayMetrics.density
        val height=maxOf((root.height*if(landscape).92f else .48f).toInt(),(240*dp).toInt()).coerceAtMost(root.height)
        if(panel.layoutParams.height!=height)panel.layoutParams=panel.layoutParams.apply{this.height=height}
        current.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let{sheet->
            if(sheet.layoutParams.height!=height)sheet.layoutParams=sheet.layoutParams.apply{this.height=height}
        }
        if(landscape){current.window?.setLayout(minOf((420*dp).toInt(),(root.width*.56f).toInt()),ViewGroup.LayoutParams.MATCH_PARENT);current.window?.setGravity(android.view.Gravity.END or android.view.Gravity.BOTTOM)}
    }
}
