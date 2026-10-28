import Foundation
import Metal

/// 主题组合消费端。宿主在同一串行渲染队列调用；输入纹理已转为正向RGBA8，mask/锚点方向一致。
/// 完整准备新场景与贴纸纹理后才交换引用，上传失败保留旧主题；不接管摄像头或commandBuffer提交。
public final class MetalThemeRenderer {
    private let device:MTLDevice
    private var scene:MetalSceneRenderer
    private var sticker:MetalStickerRenderer
    private var eye:MetalEyeMakeupRenderer
    private var scratchA:MTLTexture?,scratchB:MTLTexture?
    public init(device:MTLDevice) throws {
        self.device=device;scene=try MetalSceneRenderer(device:device);sticker=MetalStickerRenderer(device:device);eye=MetalEyeMakeupRenderer(device:device)
    }
    /// 缺少类别在新渲染器中保持关闭，避免上一主题残留。当前Metal后端不支持瘦脸/大眼，加载时明确拒绝。
    public func setTheme(_ theme:BeautyThemePack?,capabilities:ThemeCapabilities) throws {
        try theme?.validateCapabilities(capabilities)
        let nextScene=try MetalSceneRenderer(device:device),nextSticker=MetalStickerRenderer(device:device),nextEye=MetalEyeMakeupRenderer(device:device)
        if let theme=theme { for pack in theme.effects.values { try nextScene.setPack(pack,allowEyeMakeup:true) };try nextSticker.setPack(theme.sticker);try nextEye.setPack(theme.effects[.beauty]) }
        scene=nextScene;sticker=nextSticker;eye=nextEye
    }
    /// 场景/美颜→贴纸→运镜。临时纹理按分辨率复用；输出与输入不得相同，宿主持有直到GPU完成。
    public func encode(input:MTLTexture,output:MTLTexture,mask:MTLTexture?,elapsedMs:Double,poses:[StickerAnchor:StickerPose],faceCenter:SIMD2<Float>? = nil,landmarks:[SIMD2<Float>] = [],commandBuffer:MTLCommandBuffer) throws {
        try check(input !== output && input.width==output.width && input.height==output.height && input.pixelFormat == .rgba8Unorm && output.pixelFormat == .rgba8Unorm && output.usage.contains(.shaderWrite),"Expected separate equal-size RGBA8 textures")
        if scratchA?.width != input.width || scratchA?.height != input.height {
            let d=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:input.width,height:input.height,mipmapped:false)
            d.usage=[.shaderRead,.shaderWrite,.renderTarget];d.storageMode = .private
            guard let a=device.makeTexture(descriptor:d),let b=device.makeTexture(descriptor:d) else { throw StickerResourceError.invalid("Theme texture allocation failed") }
            scratchA=a;scratchB=b
        }
        try scene.encode(input:input,output:scratchA!,mask:mask,elapsedMs:elapsedMs,faceCenter:faceCenter,commandBuffer:commandBuffer,stage:.sceneOnly)
        let finalImage:MTLTexture
        if eye.isActive {
            try eye.encode(input:scratchA!,output:scratchB!,landmarks:landmarks,commandBuffer:commandBuffer)
            try sticker.encode(input:scratchB!,output:scratchA!,commandBuffer:commandBuffer,poses:poses,mirrored:false)
            finalImage=scratchA!
        } else {
            try sticker.encode(input:scratchA!,output:scratchB!,commandBuffer:commandBuffer,poses:poses,mirrored:false)
            finalImage=scratchB!
        }
        try scene.encode(input:finalImage,output:output,mask:nil,elapsedMs:elapsedMs,faceCenter:faceCenter,commandBuffer:commandBuffer,stage:.motionOnly)
    }
}
