package com.beautysdk.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** 图片自身裁剪，硬件窗口与软件绘制截图使用相同圆角，不擦除父背景。 */
class RoundedPreviewImageView @JvmOverloads constructor(
    context:Context, attrs:AttributeSet?=null, defStyleAttr:Int=0,
):AppCompatImageView(context,attrs,defStyleAttr) {
    private val corners=Path()
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val radius=resources.getDimension(R.dimen.effect_card_radius)
        corners.reset();corners.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
    }
    override fun onDraw(canvas:Canvas) {
        val checkpoint=canvas.save()
        canvas.clipPath(corners)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
