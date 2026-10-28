package com.beautysdk

import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

/** 不可变的非预乘 ARGB 素材副本；不引用 Bitmap，不依赖宿主资源生命周期。 */
class StickerImage private constructor(val width: Int, val height: Int, argb: IntArray, copy: Boolean) {
    constructor(width: Int,height: Int,argb: IntArray): this(width,height,argb,true)
    private val colors = if(copy) argb.copyOf() else argb
    internal companion object {
        /** 仅供SDK解码器转交刚创建、不会再写入的独占数组；公开构造仍防御复制。 */
        fun fromOwnedPixels(width:Int,height:Int,pixels:IntArray)=StickerImage(width,height,pixels,false)
    }
    init {
        require(width in 1..2048 && height in 1..2048)
        require(colors.size == width * height)
    }
    /** 按源图整数坐标读取非预乘 ARGB；调用方负责保证坐标在图片范围内。 */
    internal fun pixel(x: Int, y: Int) = colors[y * width + x]
    /** 配置加载/纹理上传时取得防御副本；不要在逐帧渲染中调用。 */
    fun copyArgb(): IntArray = colors.copyOf()
}

/** 图片挂件绑定位置：双眼中点或上唇，依赖相应的真实语义锚点。 */
enum class StickerAnchor { EYES, UPPER_LIP }

/**
 * 图片左上原点、x 向右 y 向下；pivot 可在图片外，例如帽子的 pivotY=1.45。
 * widthInAnchorSpans 为整张图片宽度/双眼或嘴角距离，按显示画面的眼线或嘴线旋转。
 */
data class ImageSticker @JvmOverloads constructor(
    val image: StickerImage,
    val anchor: StickerAnchor,
    val widthInAnchorSpans: Float = 2.5f,
    val pivotX: Float = .5f,
    val pivotY: Float = .5f,
    /** 相对锚点跨度的局部偏移；不使用固定屏幕像素。 */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** 素材相对锚点方向的附加顺时针角度，单位度。 */
    val rotationDegrees: Float = 0f,
) {
    init {
        require(widthInAnchorSpans.isFinite() && widthInAnchorSpans in .1f..10f)
        require(pivotX.isFinite() && pivotY.isFinite() && pivotX in -4f..4f && pivotY in -4f..4f)
        require(offsetX.isFinite() && offsetY.isFinite() && offsetX in -4f..4f && offsetY in -4f..4f)
        require(rotationDegrees.isFinite() && rotationDegrees in -180f..180f)
    }
    /** 根据图片锚点类型选择眼线或上唇姿态，缺少所需锚点时返回 null。 */
    internal fun pose(placement: StickerPlacement, info: FrameInfo): StickerPose? {
        val base=when(anchor) {
            StickerAnchor.EYES -> placement.glasses
            StickerAnchor.UPPER_LIP -> placement.moustache
        } ?: return null
        if(offsetX==0f && offsetY==0f && rotationDegrees==0f) return base
        val c=cos(base.angleRadians); val s=sin(base.angleRadians)
        val handedness=if(info.mirrored) -1f else 1f
        val localY=offsetY*handedness
        return StickerPose(FacePoint(base.center.x+(c*offsetX-s*localY)*base.spanPixels/info.width,
            base.center.y+(s*offsetX+c*localY)*base.spanPixels/info.height),base.spanPixels,
            base.angleRadians+rotationDegrees*(kotlin.math.PI/180).toFloat()*handedness)
    }
}

/** 每帧最多三层，从列表首项向末项依次覆盖；构造时复制列表，可跨线程整体替换。 */
class StickerPack(layers: List<ImageSticker>) {
    val layers: List<ImageSticker> = Collections.unmodifiableList(layers.toList())
    init { require(layers.size <= 3) { "At most three image sticker layers" } }
}

/** 预先计算仿射逆变换，避免 CPU 对每个像素重复计算三角函数。 */
internal class PlacedImageSticker(
    private val layer: ImageSticker, pose: StickerPose, private val info: FrameInfo, scale: Float,
) {
    private val cx = pose.center.x * info.width
    private val cy = pose.center.y * info.height
    private val c = cos(pose.angleRadians)
    private val s = sin(pose.angleRadians)
    private val span = pose.spanPixels * scale * layer.widthInAnchorSpans
    /** 把原始帧像素位置逆映射到素材局部坐标，双线性取样；素材范围外返回完全透明。 */
    fun sample(x: Float, y: Float): Int {
        val dx = x - cx; val dy = y - cy
        val u = (c * dx + s * dy) / span + layer.pivotX
        // 左右镜像改变坐标手性，只反转局部 y，不反转图片上的文字或不对称细节。
        val v = (-s * dx + c * dy) * (if (info.mirrored) -1f else 1f) / span *
            layer.image.width / layer.image.height + layer.pivotY
        if (u < 0f || u >= 1f || v < 0f || v >= 1f) return 0
        // 与GL_LINEAR相同的半像素中心及CLAMP_TO_EDGE规则，避免参考路径语义分叉。
        val image=layer.image
        val px=u*image.width-.5f;val py=v*image.height-.5f
        val x0=kotlin.math.floor(px).toInt();val y0=kotlin.math.floor(py).toInt()
        val fx=px-x0;val fy=py-y0
        val p00=image.pixel(x0.coerceIn(0,image.width-1),y0.coerceIn(0,image.height-1))
        val p10=image.pixel((x0+1).coerceIn(0,image.width-1),y0.coerceIn(0,image.height-1))
        val p01=image.pixel(x0.coerceIn(0,image.width-1),(y0+1).coerceIn(0,image.height-1))
        val p11=image.pixel((x0+1).coerceIn(0,image.width-1),(y0+1).coerceIn(0,image.height-1))
        var color=0
        for(c in 0..3) {
            val shift=c*8
            fun channel(p: Int)=(p ushr shift) and 255
            val top=channel(p00)*(1-fx)+channel(p10)*fx
            val bottom=channel(p01)*(1-fx)+channel(p11)*fx
            val value=kotlin.math.round(top*(1-fy)+bottom*fy).toInt().coerceIn(0,255)
            color=color or (value shl shift)
        }
        return color
    }
}
