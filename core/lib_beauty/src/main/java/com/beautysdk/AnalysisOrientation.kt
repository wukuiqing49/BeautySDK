package com.beautysdk

/** 模型分析前按元数据转正，结果再逆变换回原始像素空间；不改变输出视频尺寸或像素。 */
class AnalysisOrientation private constructor(val image: AnalysisImage, private val original: FrameInfo) {
    /** 将检测图坐标还原为原始帧坐标，不重新处理像素。 */
    fun toRaw(point: FacePoint): FacePoint = AnalysisCoordinates(original).toRaw(point)
    /** 还原框、锚点和密集关键点。 */
    fun toRaw(face: FaceObservation): FaceObservation = AnalysisCoordinates(original).toRaw(face)
    companion object {
        /** 先按帧元数据顺时针旋转、再水平镜像，得到供模型分析的正向 RGB 图；不修改输入像素。 */
        @JvmStatic fun upright(source: AnalysisImage): AnalysisOrientation = upright(source, null)

        /** 可由单一分析线程传入等长临时缓冲以复用；返回图像只在下次写入该缓冲前有效。
         * 不允许与输入 pixels 共用同一数组，防止旋转重排覆盖尚未读取的像素。
         */
        @JvmStatic fun upright(source: AnalysisImage, destination: ByteArray?): AnalysisOrientation {
            val info = source.info
            if (info.rotationDegrees == 0 && !info.mirrored) return AnalysisOrientation(source,info)
            val swap = info.rotationDegrees == 90 || info.rotationDegrees == 270
            val w = if (swap) source.height else source.width
            val h = if (swap) source.width else source.height
            val data = destination ?: ByteArray(w*h*3)
            require(data.size == source.pixels.size && data !== source.pixels)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                // 热路径只用整数坐标，避免逐像素创建 Pair 和装箱对象。
                val rx: Int
                val ry: Int
                when (info.rotationDegrees) {
                    90 -> { rx = source.height-1-y; ry = x }
                    180 -> { rx = source.width-1-x; ry = source.height-1-y }
                    270 -> { rx = y; ry = source.width-1-x }
                    else -> { rx = x; ry = y }
                }
                val dx = if (info.mirrored) w-1-rx else rx
                val src = (y*source.width+x)*3; val dst = (ry*w+dx)*3
                data[dst]=source.pixels[src];data[dst+1]=source.pixels[src+1];data[dst+2]=source.pixels[src+2]
            }
            return AnalysisOrientation(AnalysisImage(FrameInfo(w,h,info.timestampNs),w,h,data),info)
        }
    }
}
