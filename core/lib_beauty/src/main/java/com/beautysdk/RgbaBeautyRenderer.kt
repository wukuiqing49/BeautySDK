package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import java.nio.ByteBuffer
import kotlin.math.*

/** CPU 参考渲染器：RGBA 输入与独占输出；保持尺寸、stride 语义与旋转镜像；基础效果保留 alpha，PNG 贴纸按 source-over 累计 alpha。
 * 用于 CPU 帧集成和算法验证，大分辨率实时直播应优先使用 GlBeautyRenderer。
 */
class RgbaBeautyRenderer : BeautyRenderer {
    companion object {
        // RGB 整数平方距离只有 0..3*255²，查表与原 exp 权重一致，避免每帧数十万次 pow/exp。
        private val similarity = FloatArray(3 * 255 * 255 + 1) { exp(-it * (24f / (255f * 255f))) }
    }
    /** 对 RGBA 执行边缘权重磨皮、美白、色调及挂件叠加；非旁路返回独占紧凑缓冲，保持原始方向元数据。 */
    override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame {
        if (!BeautyLicensing.allowsSettings(settings)) return frame
        require(frame is RgbaFrame) { "RgbaBeautyRenderer only accepts RGBA frames" }
        require(settings.scene.isIdentity) { "Use BeautySceneRenderer for scene effects" }
        require(settings.sticker3D == null) { "3D stickers require GlSticker3DRenderer and texture input" }
        require(settings.adjustments.isIdentity) { "Select GPUPixel for extended beauty adjustments" }
        if (settings.isIdentity) return frame
        val info = frame.info; val w = info.width; val h = info.height
        val input = frame.readOnlyPixels()
        val output = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(w, h), 4))
        val placement = StickerGeometry.place(face, info)
        val scale = if (settings.animateStickers) 1f + .04f * sin((info.timestampNs % 1_000_000_000) * (2 * PI / 1e9)).toFloat() else 1f
        // 复用累加器，避免每个像素分配数组触发高频 GC；输出仍独立归调用方持有。
        val sums = FloatArray(3)
        val color = FloatArray(4)
        val images = settings.stickerPack?.layers.orEmpty().mapNotNull { layer ->
            layer.pose(placement, info)?.let { PlacedImageSticker(layer, it, info, scale) }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val src = y * frame.rowStrideBytes + x * 4
            val centerR = input.get(src).toInt() and 255
            val centerG = input.get(src + 1).toInt() and 255
            val centerB = input.get(src + 2).toInt() and 255
            sums.fill(0f); var weights = 0f
            if (settings.smoothing > 0) for (dy in -1..1) for (dx in -1..1) {
                val p = (y + dy).coerceIn(0, h - 1) * frame.rowStrideBytes + (x + dx).coerceIn(0, w - 1) * 4
                val r = input.get(p).toInt() and 255; val g = input.get(p + 1).toInt() and 255
                val b = input.get(p + 2).toInt() and 255
                val dr = r - centerR; val dg = g - centerG; val db = b - centerB
                val weight = similarity[dr * dr + dg * dg + db * db]
                sums[0] += r * weight; sums[1] += g * weight; sums[2] += b * weight; weights += weight
            }
            val hat = settings.hat && StickerGeometry.coversHat((x + .5f) / w, (y + .5f) / h, placement.hat, info, scale)
            val sticker = (settings.glasses && StickerGeometry.covers((x + .5f) / w, (y + .5f) / h, placement.glasses, info, true, scale)) ||
                (settings.moustache && StickerGeometry.covers((x + .5f) / w, (y + .5f) / h, placement.moustache, info, false, scale))
            for (c in 0..2) {
                var value = (when (c) { 0 -> centerR; 1 -> centerG; else -> centerB }).toFloat()
                if (weights > 0) value += (sums[c] / weights - value) * settings.smoothing * .65f
                value += (255 - value) * settings.whitening * .18f
                val tint = when (settings.filter) { BeautyFilter.NONE -> 0; BeautyFilter.WARM -> if (c == 0) 8 else if (c == 2) -6 else 0
                    BeautyFilter.COOL -> if (c == 2) 8 else if (c == 0) -6 else 0 }
                color[c] = if (hat) (if (c == 2) 160f else 48f) else if (sticker) 24f else (value + tint).coerceIn(0f, 255f)
            }
            color[3] = (input.get(src + 3).toInt() and 255) / 255f
            // 非预乘 source-over；透明底也正确累加 alpha，透明像素不污染背景。
            for (image in images) {
                val pixel = image.sample(x + .5f, y + .5f)
                val alpha = (pixel ushr 24) / 255f
                if (alpha == 0f) continue
                val outAlpha = alpha + color[3] * (1f - alpha)
                for (c in 0..2) {
                    val channel = (pixel ushr (16 - c * 8)) and 255
                    color[c] = (channel * alpha + color[c] * color[3] * (1f - alpha)) / outAlpha
                }
                color[3] = outAlpha
            }
            for (c in 0..2) output.put(color[c].roundToInt().coerceIn(0,255).toByte())
            output.put((color[3] * 255f).roundToInt().coerceIn(0,255).toByte())
        }
        output.flip()
        return RgbaFrame(info, output, w * 4)
    }
    /** CPU 渲染器没有需显式释放的原生或 GL 资源。 */
    override fun close() = Unit
}
