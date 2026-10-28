package com.beautysdk

import java.nio.ByteBuffer
import kotlin.math.*

/** 虚拟背景模式。分割未就绪/过期时旁路背景，绝不以人脸框冒充人像蒙版。 */
enum class BackgroundMode { NONE, BLUR, COLOR, IMAGE }
/** 数字运镜，保持输出分辨率；PUSH_IN/PAN 循环运动，FACE_FOLLOW 为有限范围人脸居中。 */
enum class CameraMotion { NONE, PUSH_IN, PAN, FACE_FOLLOW, KEYFRAMES }
/** 公共场景配置，不包含相机或直播厂商类型。图片在工作线程解码后传入，不逐帧加载。
 * 背景图片按显示方向居中裁切；颜色取不透明RGB。zoom仅用于运镜，范围1..2。
 */
data class BeautyScene @JvmOverloads constructor(
    val background: BackgroundMode = BackgroundMode.NONE,
    val backgroundColor: Int = 0xff26334d.toInt(),
    val backgroundImage: StickerImage? = null,
    val blurRadius: Float = 12f,
    val motion: CameraMotion = CameraMotion.NONE,
    val zoom: Float = 1.25f,
    val periodMs: Long = 4000,
    /** 图片焦点，按正向图片归一化坐标；仅加载时调整，不重采样原图。 */
    val backgroundFocusX: Float = .5f,
    val backgroundFocusY: Float = .5f,
    val timeline: MotionTimeline? = null,
) {
    init {
        require(blurRadius in 0f..32f && zoom in 1f..2f && periodMs in 1000..60000)
        require(backgroundFocusX in 0f..1f && backgroundFocusY in 0f..1f)
        require(motion != CameraMotion.KEYFRAMES || timeline != null)
        require(background != BackgroundMode.IMAGE || backgroundImage != null)
    }
    val needsSegmentation get() = background != BackgroundMode.NONE
    val needsFace get() = motion == CameraMotion.FACE_FOLLOW
    val isIdentity get() = !needsSegmentation && motion == CameraMotion.NONE
}

/** 不可变原始坐标单通道人像置信度，0背景、255前景；复制输入，跨线程只读共享。
 * info记录采样时刻与原始几何，mask允许低分辨率，不能保存外部借用缓冲。
 */
class ForegroundMask private constructor(val info: FrameInfo, val width: Int, val height: Int, private val data: ByteArray,
    /** 运动校正对齐到的输入时刻；info.timestampNs仍是模型的真实采样时间。 */
    val alignmentTimestampNs: Long,
    /** 原始归一化空间中的蒙版平移，采样时使用p-offset；像素快照本身不重采样。 */
    val offsetX:Float,val offsetY:Float) {
    constructor(info: FrameInfo, width: Int, height: Int, pixels: ByteArray):this(info,width,height,pixels.copyOf(),info.timestampNs,0f,0f)
    /** 从当前位置到limit批量复制一次；不改变调用者游标，不持有可复用的原生内存。 */
    @JvmOverloads constructor(info: FrameInfo, width: Int, height: Int, pixels: ByteBuffer,
        alignmentTimestampNs: Long=info.timestampNs,offsetX:Float=0f,offsetY:Float=0f):
        this(info,width,height,copyPixels(width,height,pixels),alignmentTimestampNs,offsetX,offsetY)
    init {
        require(width in 1..2048 && height in 1..2048 && width.toLong()*height == data.size.toLong())
        require(alignmentTimestampNs>=info.timestampNs)
        require(offsetX.isFinite() && offsetY.isFinite() && abs(offsetX)<=.25f && abs(offsetY)<=.25f)
    }
    companion object {
        private fun copyPixels(width: Int,height: Int,pixels: ByteBuffer):ByteArray {
            require(width in 1..2048 && height in 1..2048 && width.toLong()*height == pixels.remaining().toLong())
            return ByteArray(pixels.remaining()).also { pixels.duplicate().get(it) }
        }
    }
    fun pixels(): ByteBuffer = ByteBuffer.wrap(data).asReadOnlyBuffer()
    /** 较早的视频帧不能使用面向未来分析帧的位移；共享不可变原始像素，恢复模型采样几何。 */
    fun withoutMotionAlignment():ForegroundMask =
        if(alignmentTimestampNs==info.timestampNs && offsetX==0f && offsetY==0f)this
        else ForegroundMask(info,width,height,data,info.timestampNs,0f,0f)
    /** 禁止未来结果、过期和旋转镜像/尺寸不匹配；换源还需宿主reset清除。 */
    fun isUsable(frame: FrameInfo, maxAgeNs: Long = 250_000_000): Boolean {
        require(maxAgeNs >= 0)
        return frame.width==info.width && frame.height==info.height && frame.rotationDegrees==info.rotationDegrees &&
            frame.mirrored==info.mirrored && frame.timestampNs>=alignmentTimestampNs && frame.timestampNs-info.timestampNs<=maxAgeNs
    }
    /** 宿主明确保证同一裁剪视场时，将小图蒙版声明映射到高清输出；不延长真实采样年龄。 */
    fun forScaledOutput(frame: FrameInfo, maxAgeNs: Long = 250_000_000): ForegroundMask? {
        require(maxAgeNs>=0)
        if(info.rotationDegrees!=frame.rotationDegrees || info.mirrored!=frame.mirrored ||
            frame.timestampNs<alignmentTimestampNs || frame.timestampNs-info.timestampNs>maxAgeNs ||
            abs(info.width.toDouble()/info.height-frame.width.toDouble()/frame.height)>.02)return null
        return if(info.width==frame.width && info.height==frame.height)this
            else ForegroundMask(info.copy(width=frame.width,height=frame.height),width,height,data,alignmentTimestampNs,offsetX,offsetY)
    }
}
/** 非阻塞获取已完成蒙版；由算法模块实现，核心不依赖具体分割器。 */
fun interface ForegroundMaskProvider { fun snapshot(info: FrameInfo): ForegroundMask? }

/** 纯几何运镜控制：按视频时间推进，不依赖墙钟。换源/回退/几何变化立即清理平滑历史。 */
class CameraMotionController {
    private var previous: FrameInfo? = null
    private var previousMotion=CameraMotion.NONE
    private var previousTimeline: MotionTimeline? = null
    private var start=0L
    private var x=.5f; private var y=.5f
    /** 返回原始空间采样矩形，left/top/width/height；均匀缩放不拉伸。 */
    fun crop(info: FrameInfo, scene: BeautyScene, face: FaceObservation?): FloatArray {
        val old=previous
        if(old==null || info.timestampNs<old.timestampNs || old.width!=info.width || old.height!=info.height ||
            old.rotationDegrees!=info.rotationDegrees || old.mirrored!=info.mirrored || previousMotion!=scene.motion || previousTimeline!==scene.timeline) {
            start=info.timestampNs;x=.5f;y=.5f
        }
        val phase=((info.timestampNs-start)%(scene.periodMs*1_000_000)).toDouble()/(scene.periodMs*1_000_000)*2*PI
        var zoom=scene.zoom
        var tx=.5f; var ty=.5f
        when(scene.motion) {
            CameraMotion.NONE -> zoom=1f
            CameraMotion.PUSH_IN -> zoom=1f+(scene.zoom-1f)*((1-cos(phase))*.5).toFloat()
            CameraMotion.PAN -> {
                // 先在正向画面水平移动，再还原到原始空间，兼容横竖屏及前摄镜像。
                val point=AnalysisCoordinates(info).toRaw(FacePoint(.5f+(.5f-.5f/zoom)*sin(phase).toFloat(),.5f))
                tx=point.x;ty=point.y
            }
            CameraMotion.KEYFRAMES -> {
                val pose=requireNotNull(scene.timeline).sample((info.timestampNs-start)/1_000_000)
                val point=AnalysisCoordinates(info).toRaw(FacePoint(pose.x,pose.y))
                tx=point.x;ty=point.y;zoom=pose.zoom
            }
            CameraMotion.FACE_FOLLOW -> {
                if(face!=null) { tx=(face.bounds.left+face.bounds.right)*.5f;ty=(face.bounds.top+face.bounds.bottom)*.5f }
                val dt=if(old==null) 0L else (info.timestampNs-old.timestampNs).coerceIn(0,100_000_000)
                val gain=(1-exp(-dt/180_000_000.0)).toFloat()
                x+=(tx-x)*gain;y+=(ty-y)*gain;tx=x;ty=y
            }
        }
        previous=info;previousMotion=scene.motion;previousTimeline=scene.timeline
        val side=1f/zoom;val margin=side*.5f
        return floatArrayOf(tx.coerceIn(margin,1-margin)-margin,ty.coerceIn(margin,1-margin)-margin,side,side)
    }
    /** 在换镜头/停止时调用；不沿用上一采集会话的人脸位置。 */
    fun reset() { previous=null;previousMotion=CameraMotion.NONE }
}
