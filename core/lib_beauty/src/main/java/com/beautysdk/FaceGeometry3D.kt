package com.beautysdk

import java.util.Collections

/** x/y 为原始帧归一化坐标；z 向屏幕后方为正，单位为原始帧宽度，不是米或真实深度。 */
data class FacePoint3D(val x: Float, val y: Float, val z: Float) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

/** 单次真实模型采样的三维观测。关键点保留后端的固定拓扑顺序，不能按画面左右排序。
 * canonicalTransform 原样保留 MediaPipe 转正分析空间的矩阵，仅用于诊断/高级接入；
 * 它不是原始帧投影矩阵，不能直接作为渲染 MVP。坐标映射只修改 points，不修改该源矩阵。
 */
class FaceGeometry3D @JvmOverloads constructor(
    points: List<FacePoint3D>,
    val timestampNs: Long,
    canonicalTransform: FloatArray? = null,
) {
    val points: List<FacePoint3D> = Collections.unmodifiableList(points.toList())
    private val matrix = canonicalTransform?.copyOf()
    init {
        require(timestampNs >= 0 && points.size in 468..478)
        require(matrix == null || matrix.size == 16 && matrix.all { it.isFinite() })
    }
    fun copyCanonicalTransform(): FloatArray? = matrix?.copyOf()
    internal fun map(transform: (FacePoint3D) -> FacePoint3D) =
        FaceGeometry3D(points.map(transform), timestampNs, matrix)
}
