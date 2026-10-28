package com.beautysdk

/** 仅转换检测坐标，不持有或复制图像；顺序与输入的旋转、镜像一致。 */
class AnalysisCoordinates(private val original: FrameInfo) {
    /** 将转正分析空间中的点或整个人脸逆变换回原始归一化坐标；整脸转换同时处理框、锚点和密集关键点。 */
    fun toRaw(point: FacePoint): FacePoint {
        val x = if (original.mirrored) 1f - point.x else point.x
        val y = point.y
        return when (original.rotationDegrees) {
            90 -> FacePoint(y, 1f - x)
            180 -> FacePoint(1f - x, 1f - y)
            270 -> FacePoint(1f - y, x)
            else -> FacePoint(x, y)
        }
    }
    /** 将转正分析空间中的点或整个人脸逆变换回原始归一化坐标；整脸转换同时处理框、锚点和密集关键点。 */
    fun toRaw(face: FaceObservation): FaceObservation {
        val b = face.bounds
        val corners = listOf(FacePoint(b.left,b.top),FacePoint(b.right,b.top),
            FacePoint(b.left,b.bottom),FacePoint(b.right,b.bottom)).map(::toRaw)
        return FaceObservation(FaceBounds(corners.minOf { it.x },corners.minOf { it.y },
            corners.maxOf { it.x },corners.maxOf { it.y }), face.anchors.mapValues { toRaw(it.value) },
            face.landmarks.map(::toRaw))
    }
}
