package com.beautysdk.gpupixel

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import com.beautysdk.license.LicenseException
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES30
import com.beautysdk.*
import java.nio.ByteBuffer

/**
 * GPUPixel 美颜 Shader 的 C++/JNI 纹理适配版，不包含上游独立 EGL 引擎和人脸检测器。
 * 在宿主当前 ES3 上下文及创建线程调用；接受 OES/2D，采样 transform 后返回 identity 2D。
 * 输出归本实例所有，下次 render/close 后失效；旋转、镜像元数据保持原值。
 * LUT 仅初始化时解码；视频主链路不读回 GPU、不创建逐帧 Bitmap。
 */
class GpuPixelBeautyRenderer(context: Context) : BeautyRenderer {
    private val owner = Thread.currentThread()
    private val egl = EGL14.eglGetCurrentContext()
    private var handle = 0L
    private val overlays: GlBeautyRenderer
    private val controls=FloatArray(5)
    private val geometry=FloatArray(56)
    private val reshapeControls=FloatArray(5)
    private val makeupVertices=FloatArray(1440)
    private val neutral=BeautyAdjustments()
    private var eyeImage: EyeMakeup? = null
    private var eyeTexture=0

    init {
        check(egl != EGL14.EGL_NO_CONTEXT) { "A current EGL context is required" }
        check(GLES30.glGetString(GLES30.GL_VERSION)?.contains("OpenGL ES 3") == true) {
            "OpenGL ES 3.0 is required"
        }
        val dimensions = IntArray(12)
        val buffers = arrayOf("lookup_gray", "lookup_origin", "lookup_skin", "lookup_light", "mouth", "blusher")
            .mapIndexed { index, name ->
                val bitmap = context.assets.open("gpupixel/res/$name.png").use {
                    requireNotNull(BitmapFactory.decodeStream(it)) { "Invalid GPUPixel LUT: $name" }
                }
                try {
                    dimensions[index * 2] = bitmap.width
                    dimensions[index * 2 + 1] = bitmap.height
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    ByteBuffer.allocateDirect(pixels.size * 4).apply {
                        pixels.forEach { argb ->
                            put((argb ushr 16).toByte()); put((argb ushr 8).toByte())
                            put(argb.toByte()); put((argb ushr 24).toByte())
                        }
                        flip()
                    }
                } finally { bitmap.recycle() }
            }.toTypedArray()
        try { handle = NativeBeauty.create(buffers, dimensions) }
        catch (error: LinkageError) { throw IllegalStateException("GPUPixel native library unavailable", error) }
        check(handle != 0L) { "GPUPixel initialization failed" }
        try { overlays = GlBeautyRenderer() }
        catch (error: Throwable) { NativeBeauty.release(handle, false); handle = 0; throw error }
    }

    /** 先执行 GPUPixel 磨皮/美白，再执行原有色调和贴纸；不会重复应用基础磨皮、美白。 */
    override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame {
        if (!BeautyLicensing.allowsSettings(settings)) return frame
        return try { renderLicensed(frame, settings, face) } catch (_: LicenseException) { frame }
    }

    private fun renderLicensed(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame {
        checkOwner(); check(handle != 0L) { "Renderer is closed" }
        require(frame is TextureFrame) { "GPUPixel only accepts TextureFrame" }
        require(settings.scene.isIdentity) { "Use BeautySceneRenderer for scene effects" }
        if (settings.isIdentity) return frame
        val a=settings.adjustments
        controls[0]=a.rosy;controls[1]=a.brightness;controls[2]=a.contrast
        controls[3]=a.saturation;controls[4]=a.sharpening
        var image = if (settings.smoothing == 0f && settings.whitening == 0f && !a.hasColor) frame else {
            val texture = NativeBeauty.render(handle, frame.textureId, frame.target == TextureTarget.EXTERNAL_OES,
                frame.info.width, frame.info.height, frame.copyTransform(), settings.smoothing, settings.whitening,controls)
            TextureFrame(frame.info, texture, TextureTarget.TEXTURE_2D, IDENTITY)
        }
        // 贴妆在贴纸与美型之前，整张画面随后统一形变；复用固定顶点数组及native FBO。
        if(a.hasMakeup) {
            // 只构建已启用的局部妆面；单开睫毛不计算嘴唇和脸颊网格。
            val regions=(if(a.lipstick>0f) 1 else 0) or (if(a.blusher>0f) 2 else 0)
            val valid=makeupGeometry(face,frame.info,makeupVertices,regions)
            val material=settings.eyeMakeup
            val lashValid=if(a.eyelashes<=0f) false else if(material!=null)
                texturedEyelashGeometry(face,frame.info,material,makeupVertices) else eyelashGeometry(face,frame.info,makeupVertices)
            val lashes=if(lashValid) a.eyelashes else 0f
            val texture=if(lashes>0f && material!=null) uploadEyeImage(material) else 0
            val lips=if(valid and 1 != 0) a.lipstick else 0f
            val cheeks=if(valid and 2 != 0) a.blusher else 0f
            if(lips>0f || cheeks>0f || lashes>0f) {
                val texture=NativeBeauty.makeup(handle,image.textureId,image.target==TextureTarget.EXTERNAL_OES,
                    image.info.width,image.info.height,image.copyTransform(),lips,cheeks,lashes,texture,makeupVertices)
                image=TextureFrame(frame.info,texture,TextureTarget.TEXTURE_2D,IDENTITY)
            }
        }
        // 先叠加原坐标贴纸，再让画面和贴纸一起形变，避免眼镜仍停在形变前的位置。
        val composite=overlays.render(image,settings.copy(smoothing=0f,whitening=0f,adjustments=neutral),face) as TextureFrame
        if(!a.hasReshape) return composite
        val legacyValid=(a.slimFace>0f || a.bigEyes>0f) && reshapeGeometry(face,frame.info,geometry)
        val regions=if(a.slimNose>0f || a.chinLength!=0f || a.mouthSize!=0f)
            extendedReshapeGeometry(face,frame.info,geometry) else 0
        reshapeControls[0]=if(legacyValid) a.slimFace else 0f
        reshapeControls[1]=if(legacyValid) a.bigEyes else 0f
        reshapeControls[2]=if(regions and 1 != 0) a.slimNose else 0f
        reshapeControls[3]=if(regions and 2 != 0) a.chinLength else 0f
        reshapeControls[4]=if(regions and 4 != 0) a.mouthSize else 0f
        if(reshapeControls.all { it==0f }) return composite
        val texture=NativeBeauty.reshape(handle,composite.textureId,composite.target==TextureTarget.EXTERNAL_OES,
            composite.info.width,composite.info.height,composite.copyTransform(),reshapeControls,geometry)
        return TextureFrame(frame.info,texture,TextureTarget.TEXTURE_2D,IDENTITY)
    }

    /** 素材切换时上传一次直通RGBA；恢复宿主的纹理、PBO与像素解包状态。 */
    private fun uploadEyeImage(pack: EyeMakeup): Int {
        if(eyeImage===pack) return eyeTexture
        val image=pack.image;val pixels=image.copyArgb()
        val bytes=ByteBuffer.allocateDirect(pixels.size*4)
        for(c in pixels) {bytes.put((c ushr 16).toByte());bytes.put((c ushr 8).toByte());bytes.put(c.toByte());bytes.put((c ushr 24).toByte())};bytes.flip()
        val names=intArrayOf(GLES30.GL_TEXTURE_BINDING_2D,GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING,
            GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS)
        val saved=IntArray(names.size);val temp=IntArray(1)
        for(i in names.indices) {GLES30.glGetIntegerv(names[i],temp,0);saved[i]=temp[0]}
        val next=IntArray(1);GLES30.glGenTextures(1,next,0)
        try {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0)
            for(i in 2 until names.size) GLES30.glPixelStorei(names[i],if(i==2) 1 else 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,next[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,image.width,image.height,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,bytes)
            // 小脸对应强缩小采样，切包时生成一次mipmap，避免细毛在像素之间消失。
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        } catch(e:Throwable) {GLES30.glDeleteTextures(1,next,0);throw e}
        finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,saved[0]);GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,saved[1])
            for(i in 2 until names.size) GLES30.glPixelStorei(names[i],saved[i])
        }
        if(eyeTexture!=0) GLES30.glDeleteTextures(1,intArrayOf(eyeTexture),0)
        eyeTexture=next[0];eyeImage=pack;return eyeTexture
    }

    /** 正常关闭必须使用资源创建时的 EGL 上下文；GL 句柄与 native 内存同步释放。 */
    override fun close() {
        checkOwner()
        if (handle == 0L) return
        try { overlays.close() } finally {
            if(eyeTexture!=0) GLES30.glDeleteTextures(1,intArrayOf(eyeTexture),0)
            eyeTexture=0;eyeImage=null;NativeBeauty.release(handle, false); handle = 0
        }
    }

    /** 上下文已丢失时只释放 native 内存，GL 对象由 EGL 回收，绝不在新 context 删除旧句柄。 */
    fun abandonAfterContextLoss() {
        // GLSurfaceView 也可能重建 GL 线程；确认旧上下文已退出后允许新线程清理 native 元数据。
        check(EGL14.eglGetCurrentContext() != egl) { "Use close while the owning context is current" }
        if (handle != 0L) { NativeBeauty.release(handle, true); handle = 0;eyeTexture=0;eyeImage=null }
    }

    /** 线程和上下文同时匹配才允许访问 GPU 资源。 */
    private fun checkOwner() {
        check(Thread.currentThread() === owner && EGL14.eglGetCurrentContext() == egl) {
            "Use the creating EGL context/thread"
        }
    }

    companion object {
        private val IDENTITY = floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    }
}

/** JNI 内部桥接；native 端检查尺寸、矩阵、线程和 EGL，并把异常转成 Java 异常。 */
internal object NativeBeauty {
    init { System.loadLibrary("beauty_gpupixel") }
    external fun create(luts: Array<ByteBuffer>, dimensions: IntArray): Long
    private external fun renderNative(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, smoothing: Float, whitening: Float, controls: FloatArray): Int
    private external fun reshapeNative(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, strengths: FloatArray, geometry: FloatArray): Int
    private external fun makeupNative(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, lipstick: Float, blusher: Float, eyelashes: Float, eyeTexture: Int, vertices: FloatArray): Int
    fun render(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, smoothing: Float, whitening: Float, controls: FloatArray): Int {
        require(controls.size == 5)
        if ((smoothing != 0f || whitening != 0f || controls[0] != 0f) && !BeautyLicensing.allows(LicensedFeature.BEAUTY)) throw LicenseException("LICENSE_FEATURE_UNAVAILABLE", setOf(LicensedFeature.BEAUTY))
        if ((controls[1] != 0f || controls[2] != 1f || controls[3] != 1f || controls[4] != 0f) && !BeautyLicensing.allows(LicensedFeature.FILTER)) throw LicenseException("LICENSE_FEATURE_UNAVAILABLE", setOf(LicensedFeature.FILTER))
        return renderNative(handle, texture, oes, width, height, transform, smoothing, whitening, controls)
    }
    fun reshape(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, strengths: FloatArray, geometry: FloatArray): Int {
        if (!BeautyLicensing.allows(LicensedFeature.RESHAPE) || !BeautyLicensing.allows(LicensedFeature.FACE)) throw LicenseException("LICENSE_FEATURE_UNAVAILABLE", setOf(LicensedFeature.RESHAPE, LicensedFeature.FACE))
        return reshapeNative(handle, texture, oes, width, height, transform, strengths, geometry)
    }
    fun makeup(handle: Long, texture: Int, oes: Boolean, width: Int, height: Int,
        transform: FloatArray, lipstick: Float, blusher: Float, eyelashes: Float, eyeTexture: Int, vertices: FloatArray): Int {
        if (!BeautyLicensing.allows(LicensedFeature.MAKEUP) || !BeautyLicensing.allows(LicensedFeature.FACE)) throw LicenseException("LICENSE_FEATURE_UNAVAILABLE", setOf(LicensedFeature.MAKEUP, LicensedFeature.FACE))
        return makeupNative(handle, texture, oes, width, height, transform, lipstick, blusher, eyelashes, eyeTexture, vertices)
    }
    external fun release(handle: Long, abandon: Boolean)
}

/** MediaPipe478语义映射，不能套用GPUPixel原106点下标；按像素宽高比计算距离。
 * 固定9个下颌拉伸对和2个眼睛圆域，只在渲染线程写入调用者复用的42个浮点数。
 * 缺少稠密关键点/锚点越界即跳过美型，不用人脸框猜五官。
 */
private val reshapeIndices=intArrayOf(234,454,132,361,172,397,136,365,152)
private val reshapeTargets=intArrayOf(168,168,1,1,4,4,0,0,0)
private val reshapeNeeded=(reshapeIndices.toList()+reshapeTargets.toList()+listOf(33,133,362,263)).distinct().toIntArray()
internal fun reshapeGeometry(face: FaceObservation?,info: FrameInfo,out: FloatArray): Boolean {
    if(face==null || face.landmarks.size<478) return false
    val indices=reshapeIndices
    val targets=reshapeTargets
    if(reshapeNeeded.any { face.landmarks[it].let { p -> p.x !in 0f..1f || p.y !in 0f..1f } }) return false
    for(i in indices.indices) {
        val from=face.landmarks[indices[i]];val to=face.landmarks[targets[i]]
        out[i*4]=from.x;out[i*4+1]=from.y;out[i*4+2]=to.x;out[i*4+3]=to.y
    }
    val aspect=info.width.toFloat()/info.height
    for(i in 0..1) {
        val a=face.landmarks[if(i==0) 33 else 362];val b=face.landmarks[if(i==0) 133 else 263]
        val radius=kotlin.math.hypot(a.x-b.x,(a.y-b.y)/aspect)*.9f
        if(radius<2f/info.width) return false
        out[36+i*3]=(a.x+b.x)*.5f;out[37+i*3]=(a.y+b.y)*.5f;out[38+i*3]=radius
    }
    return true
}
