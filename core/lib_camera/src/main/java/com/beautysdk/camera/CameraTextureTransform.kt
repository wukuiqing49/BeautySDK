package com.beautysdk.camera

/** 相机纹理坐标约定：逻辑坐标左上原点，显示时顺时针旋转、最后水平镜像。
 * 与 Android Matrix 解耦，便于在 JVM 验证裁剪、变换顺序，避免设备上才发现倒置。
 */
object CameraTextureTransform {
    /** 抵消直接相机 Surface 已包含的传感器旋转及前摄镜像，保留裁剪和 GL 垂直翻转。
     * CameraX 1.4.2 SurfaceOutputImpl 的预测矩阵为 F * R * H；这里右乘 H * R逆，
     * 随后 cropped 再处理 F，使输出纹理与 ImageAnalysis 原始坐标一致。
     * 仅 hasCameraTransform=true 时使用；不可取消生产者矩阵里的裁剪/采样边界修正。
     */
    fun compensateCamera(surface: FloatArray, sensorRotation: Int, front: Boolean,
                         out: FloatArray = FloatArray(16)): FloatArray {
        val inverseOrientation = multiply(displayToRaw(0, front), displayToRaw(sensorRotation, false))
        return multiply(surface, inverseOrientation, out)
    }

    /** ST 生产者矩阵 × 左上坐标到完整缓冲区的 crop 映射，列主序。 */
    fun cropped(surface: FloatArray,width: Int,height: Int,left: Int,top: Int,right: Int,bottom: Int,out: FloatArray = FloatArray(16)): FloatArray {
        require(surface.size == 16 && surface.all { it.isFinite() })
        require(width > 0 && height > 0 && left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top)
        require(out.size == 16 && out !== surface)
        val xScale=(right-left).toFloat()/width; val yScale=-(bottom-top).toFloat()/height
        val xOffset=left.toFloat()/width; val yOffset=1f-top.toFloat()/height
        for (row in 0..3) {
            out[row]=surface[row]*xScale; out[4+row]=surface[4+row]*yScale
            out[8+row]=surface[8+row]
            out[12+row]=surface[row]*xOffset+surface[4+row]*yOffset+surface[12+row]
        }
        return out
    }
    /** 从转正并镜像后的显示坐标，逆变换回原始帧坐标。 */
    fun displayToRaw(rotation: Int,mirrored: Boolean): FloatArray {
        require(rotation in listOf(0,90,180,270))
        fun point(x: Float,y: Float): Pair<Float,Float> {
            val u = if (mirrored) 1f-x else x
            return when (rotation) {
                90 -> y to 1f-u
                180 -> 1f-u to 1f-y
                270 -> 1f-y to u
                else -> u to y
            }
        }
        val origin = point(0f,0f); val x = point(1f,0f); val y = point(0f,1f)
        return identity().apply {
            this[0] = x.first-origin.first; this[1] = x.second-origin.second
            this[4] = y.first-origin.first; this[5] = y.second-origin.second
            this[12] = origin.first; this[13] = origin.second
        }
    }
    fun multiply(a: FloatArray,b: FloatArray,out: FloatArray = FloatArray(16)): FloatArray {
        require(a.size == 16 && b.size == 16)
        require(out.size == 16 && out !== a && out !== b)
        for (index in 0..15) {
            val row = index%4; val col = index/4
            var sum = 0f
            for (k in 0..3) sum += a[k*4+row]*b[col*4+k]
            out[index] = sum
        }
        return out
    }
    private fun identity() = FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f }
}
