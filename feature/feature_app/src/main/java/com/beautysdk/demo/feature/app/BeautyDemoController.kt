package com.beautysdk.demo.feature.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** 演示用检测方案：基础美颜不检测，MediaPipe提供关键点。 */
enum class DemoBackend { BEAUTY_ONLY, MEDIAPIPE }
/** 页面效果参数：强度为0至1；眼镜款式0至2，头饰款式0至3，0代表不选素材。 */
data class DemoEffects(val smooth: Float, val white: Float, val warm: Boolean, val glasses: Boolean, val moustache: Boolean, val hat: Boolean = false, val glassesStyle: Int = 1, val headStyle: Int = 1, val adjustments: BeautyAdjustments = BeautyAdjustments(), val scene: BeautyScene = BeautyScene(), val resources: List<EffectResourcePack> = emptyList(), val themeSettings: BeautySettings? = null)
/** 静态图处理结果，包含原图、合成图、人脸数及处理总耗时；Bitmap由演示宿主使用。 */
data class DemoImage(val original: Bitmap, val processed: Bitmap, val faceCount: Int, val elapsedMs: Long)

/** 静态图效果验证入口，不伪装成引擎或实时性能 Demo。图片只在内存使用。 */
class BeautyDemoController(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val stickers = DemoStickerCatalog(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private val analyzers = mutableMapOf<DemoBackend, FaceAnalyzer>()
    private var bitmap: Bitmap? = null

    /** 在独立 worker 读取静态图、同步检测并执行 CPU 参考合成，最终回调主线程；耗时不等同实时视频 FPS。 */
    fun render(uri: Uri?, backend: DemoBackend, effects: DemoEffects, callback: (Result<DemoImage>) -> Unit) {
        check(!closed)
        executor.execute {
            val result = runCatching {
                if (uri != null) bitmap = load(uri)
                val source = bitmap ?: sample().also { bitmap = it }
                val start = System.nanoTime()
                val rgba = ByteBuffer.allocate(source.width * source.height * 4)
                val pixels = IntArray(source.width * source.height)
                source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                pixels.forEach { color ->
                    rgba.put((color shr 16).toByte()); rgba.put((color shr 8).toByte())
                    rgba.put(color.toByte()); rgba.put((color ushr 24).toByte())
                }
                rgba.flip()
                val input = RgbaFrame(FrameInfo(source.width, source.height, start), rgba, source.width * 4)
                val analyzer = if (backend == DemoBackend.BEAUTY_ONLY) null else analyzers.getOrPut(backend) {
                    when (backend) {
                        DemoBackend.MEDIAPIPE -> MediaPipeFaceAnalyzer(app)
                        else -> error("No analyzer")
                    }
                }
                val faces = analyzer?.analyze(AnalysisImage.copyOf(input, 640)) ?: emptyList()
                val output = RgbaBeautyRenderer().use { renderer -> renderer.render(input, stickers.settings(effects), faces.firstOrNull()) as RgbaFrame }
                val data = output.readOnlyPixels()
                val colors = IntArray(pixels.size) {
                    val r = data.get().toInt() and 255; val g = data.get().toInt() and 255
                    val b = data.get().toInt() and 255; val a = data.get().toInt() and 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                DemoImage(source, Bitmap.createBitmap(colors, source.width, source.height, Bitmap.Config.ARGB_8888),
                    faces.size, (System.nanoTime() - start) / 1_000_000)
            }
            main.post { if (!closed) callback(result) }
        }
    }
    /** 限量读取图片、降采样并应用 EXIF 方向，避免原始大图直接占满内存。 */
    private fun load(uri: Uri): Bitmap {
        val bytes = app.contentResolver.openInputStream(uri)!!.use { stream ->
            val bytes = stream.readBytesLimited(20 * 1024 * 1024)
            bytes
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image" }
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 960) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val orientation = try { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 1 }
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
            }
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
    /** 最多读取limit字节，超限抛错；流的关闭由外层use负责，避免无限读取外部图片。 */
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(out.size() + count <= limit) { "Image exceeds 20 MB" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    /** 生成不含人脸的内存渐变测试图，仅用于基础美颜及旁路验证。 */
    private fun sample(): Bitmap {
        val w = 320; val h = 240
        return Bitmap.createBitmap(IntArray(w*h) { i ->
            val x = i % w; val y = i / w
            (255 shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 96
        }, w, h, Bitmap.Config.ARGB_8888)
    }
    /** 停止接收请求，在原分析线程释放检测器与素材缓存，关闭 executor；不保存或上传图片。 */
    override fun close() {
        if (!closed) {
            closed = true
            executor.execute { analyzers.values.forEach { it.close() }; analyzers.clear(); bitmap = null; stickers.clear() }
            executor.shutdown()
        }
    }
}
