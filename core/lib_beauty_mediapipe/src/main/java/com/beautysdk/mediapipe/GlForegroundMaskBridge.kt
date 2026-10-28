package com.beautysdk.mediapipe

import com.beautysdk.*

/** 仅纹理引擎的可选分割输入桥。GPU缩至256边长、双PBO非等待读回，复用公共桥，非零拷贝。
 * 在原始美颜前提交；与masks都由宿主拥有。换源/关闭/重启必须reset，GL操作在创建上下文。
 * 已有独立RGBA时直接masks.submit，避免重复读回。回调不等待模型。
 */
class GlForegroundMaskBridge(private val masks:MediaPipeForegroundMasks):AutoCloseable {
    private val bridge=GlTextureAnalysisBridge(maxSide=256,intervalNs=66_666_667L,
        onAnalysisFrame={ small,source -> masks.submit(small,source) })
    fun submit(frame:TextureFrame,enabled:Boolean):Boolean {
        masks.setEnabled(enabled)
        return bridge.submit(frame,enabled)
    }
    fun reset() { bridge.reset();masks.reset() }
    override fun close() { bridge.close();masks.reset() }
}
