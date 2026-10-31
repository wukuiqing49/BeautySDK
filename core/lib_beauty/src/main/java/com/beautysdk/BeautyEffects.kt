package com.beautysdk

import kotlin.math.*

/** 基础色调枚举：无滤镜、暖色、冷色；不代表 LUT 或完整调色系统。 */
enum class BeautyFilter { NONE, WARM, COOL }
/** GPUPixel扩展参数；中性值不增加处理。基础渲染器不支持时明确拒绝，避免假生效。
 * brightness/chinLength/mouthSize为-1..1；contrast/saturation为0..2且1中性；其他强度0..1。
 * 红润是全图肤色倾向调色，不是人脸分割或腮红贴妆。
 */
data class BeautyAdjustments(
    val rosy: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpening: Float = 0f,
    val slimFace: Float = 0f,
    val bigEyes: Float = 0f,
    /** 局部口红强度，0关闭；需要稠密唇部关键点，仅GPUPixel支持。 */
    val lipstick: Float = 0f,
    /** 局部腮红强度，0关闭；需要稠密脸颊关键点，仅GPUPixel支持。 */
    val blusher: Float = 0f,
    /** 自定义局部瘦鼻，0..1；非上游GPUPixel原生效果。 */
    val slimNose: Float = 0f,
    /** 自定义下巴长短，-1缩短、0关闭、1拉长；二维局部形变。 */
    val chinLength: Float = 0f,
    /** 自定义嘴部大小，-1缩小、0关闭、1放大；不改变表情状态。 */
    val mouthSize: Float = 0f,
    /** 程序化上睫毛强度，需要稠密眼睑点；0关闭，仅GPUPixel后端支持。 */
    val eyelashes: Float = 0f,
    val makeupColors: MakeupColors,
) {
    @JvmOverloads constructor(rosy: Float = 0f, brightness: Float = 0f, contrast: Float = 1f, saturation: Float = 1f, sharpening: Float = 0f, slimFace: Float = 0f, bigEyes: Float = 0f, lipstick: Float = 0f, blusher: Float = 0f, slimNose: Float = 0f, chinLength: Float = 0f, mouthSize: Float = 0f, eyelashes: Float = 0f) :
        this(rosy, brightness, contrast, saturation, sharpening, slimFace, bigEyes, lipstick, blusher, slimNose, chinLength, mouthSize, eyelashes, MakeupColors())

    /** 保留旧copy描述符；修改强度时保留当前色号。 */
    fun copy(rosy: Float = this.rosy, brightness: Float = this.brightness, contrast: Float = this.contrast, saturation: Float = this.saturation, sharpening: Float = this.sharpening, slimFace: Float = this.slimFace, bigEyes: Float = this.bigEyes, lipstick: Float = this.lipstick, blusher: Float = this.blusher, slimNose: Float = this.slimNose, chinLength: Float = this.chinLength, mouthSize: Float = this.mouthSize, eyelashes: Float = this.eyelashes):BeautyAdjustments =
        BeautyAdjustments(rosy, brightness, contrast, saturation, sharpening, slimFace, bigEyes, lipstick, blusher, slimNose, chinLength, mouthSize, eyelashes, makeupColors)

    init {
        require(rosy in 0f..1f && brightness in -1f..1f && contrast in 0f..2f && saturation in 0f..2f &&
            sharpening in 0f..1f && slimFace in 0f..1f && bigEyes in 0f..1f &&
            lipstick in 0f..1f && blusher in 0f..1f && eyelashes in 0f..1f &&
            slimNose in 0f..1f && chinLength in -1f..1f && mouthSize in -1f..1f)
    }
    /** 美型与贴妆独立调度，避免只开口红仍执行一次无效形变。 */
    val hasReshape: Boolean get() = slimFace>0f || bigEyes>0f || slimNose>0f || chinLength!=0f || mouthSize!=0f
    val hasMakeup: Boolean get() = lipstick>0f || blusher>0f || eyelashes>0f
    val needsFace: Boolean get() = hasReshape || hasMakeup
    val hasColor: Boolean get() = rosy!=0f || brightness!=0f || contrast!=1f || saturation!=1f || sharpening!=0f
    val isIdentity: Boolean get() = !hasColor && !needsFace
}

/**
 * 不可变美颜配置，可跨线程整体替换；程序化挂件与图片素材独立，避免同时叠加相同效果。
 * @property smoothing 磨皮强度，0至1，0表示关闭；CPU和GL遵循相同参数语义。
 * @property whitening 美白强度，0至1，0表示关闭，不改变输入透明度。
 * @property filter 基础冷暖色调，NONE表示不偏移颜色。
 * @property glasses 程序化眼镜开关，需要有效双眼锚点。
 * @property moustache 程序化胡子开关，需要嘴角及真实上唇锚点。
 * @property animateStickers 是否按帧时间戳给挂件施加轻微周期缩放，不驱动人脸检测。
 * @property hat 程序化帽子开关，需要有效双眼锚点。
 * @property stickerPack 可选不可变图片素材包，按列表顺序叠加，缺锚点时隐藏。
 */
data class BeautySettings(
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val filter: BeautyFilter = BeautyFilter.NONE,
    val glasses: Boolean = false,
    val moustache: Boolean = false,
    val animateStickers: Boolean = false,
    /** 双眼锚点驱动的程序化礼帽；无有效双眼时隐藏。 */
    val hat: Boolean = false,
    /** 可选 PNG 解码后的素材包；与程序化效果独立，列表顺序即覆盖顺序。 */
    val stickerPack: StickerPack? = null,
    /** 可选GPUPixel调色及美型；宿主需选支持该能力的渲染器。 */
    val adjustments: BeautyAdjustments = BeautyAdjustments(),
    /** 场景特效由BeautySceneRenderer组合；默认关闭。 */
    val scene: BeautyScene = BeautyScene(),
    /** 可选图片睫毛资源；强度由adjustments.eyelashes控制，未提供时保留程序化款。 */
    val eyeMakeup: EyeMakeup? = null,
    /** 可选刚性三维网格；由GlSticker3DRenderer组合，默认不分配三维GL资源。 */
    val sticker3D: Sticker3D?,
) {
    /** 保留既有构造器和Kotlin默认参数JVM描述符；旧消费者不需要感知新增字段。 */
    @JvmOverloads constructor(smoothing: Float = 0f, whitening: Float = 0f, filter: BeautyFilter = BeautyFilter.NONE,
        glasses: Boolean = false, moustache: Boolean = false, animateStickers: Boolean = false,
        hat: Boolean = false, stickerPack: StickerPack? = null, adjustments: BeautyAdjustments = BeautyAdjustments(),
        scene: BeautyScene = BeautyScene(), eyeMakeup: EyeMakeup? = null) :
        this(smoothing,whitening,filter,glasses,moustache,animateStickers,hat,stickerPack,adjustments,scene,eyeMakeup,null)

    /** 旧copy调用保留当前三维选择；编译器生成的新copy同时允许显式替换sticker3D。 */
    fun copy(smoothing: Float = this.smoothing, whitening: Float = this.whitening, filter: BeautyFilter = this.filter,
        glasses: Boolean = this.glasses, moustache: Boolean = this.moustache, animateStickers: Boolean = this.animateStickers,
        hat: Boolean = this.hat, stickerPack: StickerPack? = this.stickerPack, adjustments: BeautyAdjustments = this.adjustments,
        scene: BeautyScene = this.scene, eyeMakeup: EyeMakeup? = this.eyeMakeup): BeautySettings =
        BeautySettings(smoothing,whitening,filter,glasses,moustache,animateStickers,hat,stickerPack,adjustments,scene,eyeMakeup,sticker3D)

    init { require(smoothing in 0f..1f && whitening in 0f..1f) }
    /** 仅依赖人脸的效果触发分析；基础磨皮、美白、色调不需要关键点。 */
    val requiresFaceAnalysis: Boolean get() = sticker3D != null || scene.needsFace || adjustments.needsFace || glasses || moustache || hat || !stickerPack?.layers.isNullOrEmpty()
    /** 所有效果都关闭时走旁路；仅开动画但没有挂件仍属旁路。 */
    val isIdentity: Boolean get() = smoothing == 0f && whitening == 0f && filter == BeautyFilter.NONE &&
        !glasses && !moustache && !hat && stickerPack?.layers.isNullOrEmpty() && sticker3D == null && adjustments.isIdentity && scene.isIdentity
}

/** 挂件局部坐标：中心为原点，单位长度为双眼/嘴角距离，角度沿画面 y 向下。 */
data class StickerPose(val center: FacePoint, val spanPixels: Float, val angleRadians: Float)
/** 单帧各挂件姿态集合；null 表示无人脸或缺少该挂件所需的真实锚点。 */
data class StickerPlacement(val glasses: StickerPose?, val moustache: StickerPose?, val hat: StickerPose? = null)
/** 将人脸语义锚点转换为挂件局部几何，供 CPU 与 GPU 共用同一定位规则。 */
object StickerGeometry {
    /** 由归一化眼睛/嘴部锚点计算像素尺度和角度；无人脸、缺锚点或跨度过小时对应挂件不可用。 */
    fun place(face: FaceObservation?, info: FrameInfo): StickerPlacement {
        if (face == null) return StickerPlacement(null, null)
        val a = face.anchors
        /** 由两端锚点及中心构造局部姿态；使用实际帧宽高将归一化距离换算为像素。 */
        fun pose(left: FacePoint?, right: FacePoint?, center: FacePoint?): StickerPose? {
            if (left == null || right == null || center == null) return null
            val dx = (right.x - left.x) * info.width
            val dy = (right.y - left.y) * info.height
            val span = hypot(dx, dy)
            return if (span < 2f) null else StickerPose(center, span, atan2(dy, dx))
        }
        val left = a[FaceAnchor.LEFT_EYE]; val right = a[FaceAnchor.RIGHT_EYE]
        val mid = if (left != null && right != null) FacePoint((left.x + right.x) / 2, (left.y + right.y) / 2) else null
        val eyes = pose(left, right, mid)
        // 原图经旋转再镜像；镜像会改变局部坐标手性，补半圈保持帽顶朝向额头。
        val hat = eyes?.let { it.copy(angleRadians = it.angleRadians + if (info.mirrored) PI.toFloat() else 0f) }
        return StickerPlacement(eyes, pose(a[FaceAnchor.LEFT_MOUTH],
            a[FaceAnchor.RIGHT_MOUTH], a[FaceAnchor.UPPER_LIP]), hat)
    }

    /** 礼帽相对双眼中点绘制：帽檐与帽身均为实色形状，超出画面自然裁切。 */
    internal fun coversHat(x: Float, y: Float, pose: StickerPose?, info: FrameInfo, scale: Float): Boolean {
        if (pose == null) return false
        val dx = (x - pose.center.x) * info.width / (pose.spanPixels * scale)
        val dy = (y - pose.center.y) * info.height / (pose.spanPixels * scale)
        val u = cos(pose.angleRadians) * dx + sin(pose.angleRadians) * dy
        val v = -sin(pose.angleRadians) * dx + cos(pose.angleRadians) * dy
        return (abs(u) < 1.05f && v > -.95f && v < -.81f) ||
            (abs(u) < .68f && v > -1.85f && v <= -.95f)
    }

    /** 判断原始帧的归一化位置是否落入程序化眼镜或胡子形状，供 CPU 参考渲染使用。 */
    internal fun covers(x: Float, y: Float, pose: StickerPose?, info: FrameInfo, glasses: Boolean, scale: Float): Boolean {
        if (pose == null) return false
        val dx = (x - pose.center.x) * info.width / (pose.spanPixels * scale)
        val dy = (y - pose.center.y) * info.height / (pose.spanPixels * scale)
        val u = cos(pose.angleRadians) * dx + sin(pose.angleRadians) * dy
        val v = -sin(pose.angleRadians) * dx + cos(pose.angleRadians) * dy
        if (glasses) {
            val ring = abs(sqrt(((abs(u) - .5f) / .34f).pow(2) + (v / .27f).pow(2)) - 1) < .12f
            return ring || (abs(u) < .17f && abs(v) < .035f) || (abs(u) in .82f..1.04f && abs(v) < .035f)
        }
        return ((abs(u) - .22f) / .3f).pow(2) + ((v - .04f) / .12f).pow(2) < 1
    }
}

/** 美颜渲染接口：CPU处理RGBA，GL处理纹理；实现负责声明输入格式、线程和输出有效期。 */
interface BeautyRenderer : AutoCloseable {
    /** 合成一帧；输入只借用、不接管资源。输出所有权及有效期由具体 CPU/GL 实现声明。 */
    fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame
    /** 释放渲染器持有的资源；GL 实现必须在创建它的 EGL 上下文及线程调用。 */
    override fun close()
}
