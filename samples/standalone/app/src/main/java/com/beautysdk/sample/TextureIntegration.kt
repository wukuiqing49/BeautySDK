package com.beautysdk.sample

import android.content.Context
import com.beautysdk.BeautyEnginePipeline
import com.beautysdk.FaceAnalyzerFactory
import com.beautysdk.GlSticker3DRenderer
import com.beautysdk.BeautySettings
import com.beautysdk.BuiltInStickers3D
import com.beautysdk.gpupixel.GpuPixelBeautyRenderer
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer

/** 宿主在当前ES3渲染线程调用；此示例不创建摄像头或直播引擎。 */
fun createTexturePipeline(context: Context, onError: (Exception) -> Unit): BeautyEnginePipeline {
    val app = context.applicationContext
    val renderer = GpuPixelBeautyRenderer(app)
    return try {
        BeautyEnginePipeline(
            factory = FaceAnalyzerFactory { MediaPipeFaceAnalyzer(app) },
            renderer = renderer,
            textureAnalysis = true,
            onError = onError,
        )
    } catch (error: Exception) { renderer.close(); throw error }
}

/** 同样在宿主当前ES3线程创建/关闭；与普通入口二选一，不创建第二套采集链路。
 * 首版不支持2D美型，开启前显式移除slimFace/bigEyes等形变参数；不能叠加旧PNG眼镜。
 */
fun create3DTexturePipeline(context: Context,onError: (Exception) -> Unit): BeautyEnginePipeline {
    val app=context.applicationContext
    val renderer=GlSticker3DRenderer(GpuPixelBeautyRenderer(app))
    return try {
        BeautyEnginePipeline(factory=MediaPipeFaceAnalyzer.factory(app,output3D=true),
            renderer=renderer,textureAnalysis=true,onError=onError).also {
                it.settings=BeautySettings(smoothing=.3f,sticker3D=BuiltInStickers3D.glasses())
            }
    }catch(error:Exception){renderer.close();throw error}
}
