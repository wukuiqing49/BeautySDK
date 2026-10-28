package com.beautysdk

import java.util.Collections

/**
 * 跨端图片上睫毛。root以PNG左上为原点，9点按u递增，对应眼角到眼角的9个上眼睑点。
 * length是伸出距离/眼宽；mirrorRight=true让左右眼外眼角使用同一素材侧，形成镜像款。
 * 配置与图片不可变；只在资源切换时解码/上传，不持有Bitmap或相机对象。
 */
class EyeMakeup(val image: StickerImage, root: List<FacePoint>, val length: Float = .2f,
    val mirrorRight: Boolean = true) {
    val root: List<FacePoint> = Collections.unmodifiableList(root.toList())
    init {
        require(length in .02f.. .5f && root.size==9)
        require(root.all {it.x in 0f..1f && it.y in .3f..1f})
        require(root.zipWithNext().all {(a,b)->b.x-a.x>=.01f-.000001f})
        require(root.last().x-root.first().x>=.5f-.000001f)
    }
}
