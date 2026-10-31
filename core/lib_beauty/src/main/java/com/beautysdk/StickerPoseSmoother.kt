package com.beautysdk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** 渲染线程专用：只平滑三个挂件姿态，不复制人脸网格或图像，也不改变上游可见性。
 * 小幅变化采用约35ms时间常数；明显移动/旋转直接跟随，避免转头时长距离拖尾。
 * 嘴部贴纸中心直接跟随最新上唇锚点，避免局部跟踪后再叠加位置低通；尺度/角度仍抑制微抖。
 * 这里不保活：缺锚点立即返回null，结果有效期仍由分析运行时决定。
 */
internal class StickerPoseSmoother {
    private var previousInfo: FrameInfo? = null
    private var previous: StickerPlacement? = null

    /** 会话停止、效果旁路或换流时清理；下一份有效姿态直接显示，不从旧位置缓动。 */
    fun clear() { previousInfo=null; previous=null }

    fun update(current: StickerPlacement, info: FrameInfo): StickerPlacement {
        val oldInfo=previousInfo
        val old=previous
        val dt=oldInfo?.let { info.timestampNs-it.timestampNs } ?: 0L
        val output=if(old==null || oldInfo==null || !oldInfo.sameGeometry(info) ||
            dt<=0 || dt>BeautyPipelineDefaults.MAX_RESULT_AGE_NS) current
        else {
            val alpha=(1.0-exp(-dt.toDouble()/35_000_000.0)).toFloat()
            StickerPlacement(blend(old.glasses,current.glasses,info,alpha),
                blend(old.moustache,current.moustache,info,alpha)?.let { it.copy(center=current.moustache!!.center) },
                blend(old.hat,current.hat,info,alpha))
        }
        previousInfo=info; previous=output
        return output
    }

    private fun blend(old: StickerPose?, current: StickerPose?, info: FrameInfo, alpha: Float): StickerPose? {
        if(current==null || old==null || old.spanPixels<=0f || current.spanPixels<=0f) return current
        val span=maxOf(old.spanPixels,current.spanPixels)
        val displacement=hypot((current.center.x-old.center.x)*info.width,
            (current.center.y-old.center.y)*info.height)/span
        val scaleChange=abs(current.spanPixels-old.spanPixels)/span
        // 跨越±π时走最短旋转路径，不能绕一整圈。
        val angle=atanShortest(current.angleRadians-old.angleRadians)
        if(displacement>=.08f || scaleChange>=.08f || abs(angle)>=.10f) return current
        return StickerPose(FacePoint(old.center.x+(current.center.x-old.center.x)*alpha,
            old.center.y+(current.center.y-old.center.y)*alpha),
            old.spanPixels+(current.spanPixels-old.spanPixels)*alpha,
            old.angleRadians+angle*alpha)
    }

    private fun atanShortest(angle: Float): Float {
        val circle=(2*PI).toFloat()
        var value=angle%circle
        if(value>PI) value-=circle
        if(value< -PI) value+=circle
        return value
    }
}
