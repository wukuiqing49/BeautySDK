package com.beautysdk

/** 不含时间戳的帧几何；尺寸始终描述真实缓冲区，不能用输出尺寸冒充检测尺寸。 */
data class FrameGeometry(val width: Int, val height: Int, val rotationDegrees: Int = 0,
                         val mirrored: Boolean = false) {
    init { FrameInfo(width,height,0,rotationDegrees,mirrored) }
    fun at(timestampNs: Long) = FrameInfo(width,height,timestampNs,rotationDegrees,mirrored)
    fun matches(info: FrameInfo) = width==info.width && height==info.height &&
        rotationDegrees==info.rotationDegrees && mirrored==info.mirrored
}

/** 转正且应用镜像后的画面中的归一化区域；source 表示裁剪，target 表示包含留边时的内容区。 */
data class FrameRegion(val left: Float = 0f, val top: Float = 0f,
                       val right: Float = 1f, val bottom: Float = 1f) {
    init { require(listOf(left,top,right,bottom).all { it.isFinite() } &&
        left>=0f && top>=0f && right<=1f && bottom<=1f && right>left && bottom>top) }
    internal fun contains(p: FacePoint) = p.x in left..right && p.y in top..bottom
}

/** 显式声明分析图到输出逻辑画面的映射，不执行图像裁剪或GPU读回。
 * sourceRegion（转正/镜像后）映射到targetRegion，再逆变换到输出原始坐标。
 * 宿主必须保证输出像素已经采用相同变换；TextureFrame.transform 仍是独立的纹理采样矩阵。
 * 默认完整区域仅适用于同视场缩放，不能用它猜测不同引擎的裁剪方式。
 */
data class AnalysisFrameMapping(val source: FrameGeometry, val target: FrameGeometry,
                                val sourceRegion: FrameRegion = FrameRegion(),
                                val targetRegion: FrameRegion = FrameRegion()) {
    private val targetCoordinates = AnalysisCoordinates(target.at(0))
    private fun upright(p: FacePoint): FacePoint {
        val rotated = when(source.rotationDegrees) {
            90 -> FacePoint(1f-p.y,p.x)
            180 -> FacePoint(1f-p.x,1f-p.y)
            270 -> FacePoint(p.y,1f-p.x)
            else -> p
        }
        return if(source.mirrored) FacePoint(1f-rotated.x,rotated.y) else rotated
    }
    /** 只转换坐标；边界外的点保留真实位置，不钳制到边缘伪造锚点。 */
    fun map(point: FacePoint): FacePoint {
        return mapUpright(upright(point))
    }
    private fun mapUpright(p: FacePoint): FacePoint {
        val x=targetRegion.left+(p.x-sourceRegion.left)/(sourceRegion.right-sourceRegion.left)*(targetRegion.right-targetRegion.left)
        val y=targetRegion.top+(p.y-sourceRegion.top)/(sourceRegion.bottom-sourceRegion.top)*(targetRegion.bottom-targetRegion.top)
        return targetCoordinates.toRaw(FacePoint(x,y))
    }
    /** 完全落在裁剪外的人脸丢弃；裁掉的锚点移除，依赖这些锚点的贴纸自然隐藏。 */
    fun map(face: FaceObservation): FaceObservation? {
        val b=face.bounds
        val rawCorners=listOf(FacePoint(b.left,b.top),FacePoint(b.right,b.top),
            FacePoint(b.left,b.bottom),FacePoint(b.right,b.bottom))
        val u=rawCorners.map(::upright)
        if(u.maxOf { it.x }<=sourceRegion.left || u.minOf { it.x }>=sourceRegion.right ||
            u.maxOf { it.y }<=sourceRegion.top || u.minOf { it.y }>=sourceRegion.bottom) return null
        // 先在源裁剪区域相交，再映射，防止人脸框伸进目标留黑边。
        val l=maxOf(u.minOf { it.x },sourceRegion.left); val r=minOf(u.maxOf { it.x },sourceRegion.right)
        val t=maxOf(u.minOf { it.y },sourceRegion.top); val btm=minOf(u.maxOf { it.y },sourceRegion.bottom)
        val corners=listOf(FacePoint(l,t),FacePoint(r,t),FacePoint(l,btm),FacePoint(r,btm)).map(::mapUpright)
        val left=corners.minOf { it.x }.coerceIn(0f,1f); val right=corners.maxOf { it.x }.coerceIn(0f,1f)
        val top=corners.minOf { it.y }.coerceIn(0f,1f); val bottom=corners.maxOf { it.y }.coerceIn(0f,1f)
        if(right<=left || bottom<=top) return null
        return FaceObservation(FaceBounds(left,top,right,bottom),
            face.anchors.filterValues { sourceRegion.contains(upright(it)) }.mapValues { map(it.value) },
            face.landmarks.map(::map))
    }
}
