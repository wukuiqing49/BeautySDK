package com.beautysdk.sample

import android.content.Context
import com.beautysdk.BeautyEnginePipeline
import com.beautysdk.FaceAnalyzerFactory
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
