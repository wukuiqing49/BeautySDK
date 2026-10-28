import Foundation
import Metal
import CoreGraphics

/// 只负责静态贴纸合成，不持有相机或推流引擎。所有方法由宿主在同一串行渲染队列调用。
public final class MetalStickerRenderer {
    private let device: MTLDevice
    private var pipeline: MTLRenderPipelineState?
    private var format: MTLPixelFormat = .invalid
    private var textures: [String: MTLTexture] = [:]
    private var pack: StickerResourcePack?
    public init(device: MTLDevice) { self.device=device }

    /// 在处理帧之前调用。加载失败保留旧包；每张PNG只上传一次，不在帧回调解码。
    public func setPack(_ value: StickerResourcePack?) throws {
        guard pack !== value else { return }
        var next: [String: MTLTexture]=[:]
        if let value=value {
            for name in Set(value.manifest.layers.map(\.image)) {
                let image=value.images[name]!, w=image.width, h=image.height
                var pixels=[UInt8](repeating:0,count:w*h*4)
                let ok=pixels.withUnsafeMutableBytes { bytes -> Bool in
                    guard let context=CGContext(data:bytes.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w*4,space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
                    context.translateBy(x:0,y:CGFloat(h));context.scaleBy(x:1,y:-1)
                    context.draw(image,in:CGRect(x:0,y:0,width:CGFloat(w),height:CGFloat(h)));return true
                }
                try check(ok,"Cannot decode RGBA")
                // 上传非预乘RGBA，匹配Android纹理的线性采样语义；fragment再预乘合成。
                for i in stride(from:0,to:pixels.count,by:4) { let a=Int(pixels[i+3]); if a > 0 { for c in 0..<3 { pixels[i+c]=UInt8(min(255,(Int(pixels[i+c])*255+a/2)/a)) } } }
                let descriptor=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:w,height:h,mipmapped:false)
                descriptor.usage=[.shaderRead];descriptor.storageMode = .shared
                guard let texture=device.makeTexture(descriptor:descriptor) else { throw StickerResourceError.invalid("Texture allocation failed") }
                pixels.withUnsafeBytes { texture.replace(region:MTLRegionMake2D(0,0,w,h),mipmapLevel:0,withBytes:$0.baseAddress!,bytesPerRow:w*4) }
                next[name]=texture
            }
        }
        textures=next;pack=value
    }
    /// 输入/输出必须不同且同尺寸同格式，输出需有renderTarget用途。传入的commandBuffer由宿主提交。
    /// 支持非sRGB RGBA8/BGRA8；原始帧alpha按预乘source-over约定。无锚点时只复制原帧。
    /// 锚点已在原始纹理坐标中，宿主保留旋转/镜像元数据，不在这里重复旋转。
    public func encode(input: MTLTexture, output: MTLTexture, commandBuffer: MTLCommandBuffer,
                       poses: [StickerAnchor: StickerPose], mirrored: Bool) throws {
        try check(input !== output && input.width == output.width && input.height == output.height && input.pixelFormat == output.pixelFormat && [.rgba8Unorm,.bgra8Unorm].contains(input.pixelFormat),"Expected distinct equal-size RGBA8/BGRA8 textures")
        try check(output.usage.contains(.renderTarget),"Output must support renderTarget")
        // 先准备pipeline再写入commandBuffer，避免编译失败时留下半帧。
        if pipeline == nil || format != output.pixelFormat { try makePipeline(output.pixelFormat) }
        guard let blit=commandBuffer.makeBlitCommandEncoder() else { throw StickerResourceError.invalid("Cannot create blit encoder") }
        blit.copy(from:input,sourceSlice:0,sourceLevel:0,sourceOrigin:MTLOrigin(x:0,y:0,z:0),sourceSize:MTLSize(width:input.width,height:input.height,depth:1),to:output,destinationSlice:0,destinationLevel:0,destinationOrigin:MTLOrigin(x:0,y:0,z:0));blit.endEncoding()
        guard let pack=pack else { return }
        let descriptor=MTLRenderPassDescriptor();descriptor.colorAttachments[0].texture=output;descriptor.colorAttachments[0].loadAction = .load;descriptor.colorAttachments[0].storeAction = .store
        guard let encoder=commandBuffer.makeRenderCommandEncoder(descriptor:descriptor) else { throw StickerResourceError.invalid("Cannot create render encoder") }
        encoder.setRenderPipelineState(pipeline!)
        for layer in pack.manifest.layers {
            guard let pose=poses[layer.anchor], pose.span >= 2, pose.span.isFinite, pose.angle.isFinite, pose.center.x.isFinite, pose.center.y.isFinite, let texture=textures[layer.image] else { continue }
            let points=StickerGeometry.corners(layer:layer,imageSize:CGSize(width:CGFloat(texture.width),height:CGFloat(texture.height)),pose:pose,mirrored:mirrored)
            let uv:[SIMD2<Float>]=[.init(0,0),.init(1,0),.init(0,1),.init(1,1)]
            var vertices=[SIMD4<Float>]()
            for i in 0..<4 { vertices.append(SIMD4(Float(points[i].x)/Float(output.width)*2-1,1-Float(points[i].y)/Float(output.height)*2,uv[i].x,uv[i].y)) }
            vertices.withUnsafeBytes { encoder.setVertexBytes($0.baseAddress!,length:$0.count,index:0) }
            encoder.setFragmentTexture(texture,index:0);encoder.drawPrimitives(type:.triangleStrip,vertexStart:0,vertexCount:4)
        }
        encoder.endEncoding()
    }
    private func makePipeline(_ pixelFormat: MTLPixelFormat) throws {
        let source="""
        #include <metal_stdlib>
        using namespace metal;
        struct V { float4 position [[position]]; float2 uv; };
        vertex V sticker_vertex(uint id [[vertex_id]], constant float4 *v [[buffer(0)]]) {
            V o; o.position=float4(v[id].xy,0,1); o.uv=v[id].zw; return o;
        }
        fragment float4 sticker_fragment(V in [[stage_in]], texture2d<float> image [[texture(0)]]) {
            constexpr sampler s(coord::normalized,address::clamp_to_edge,filter::linear);
            float4 c=image.sample(s,in.uv); return float4(c.rgb*c.a,c.a);
        }
        """
        let library=try device.makeLibrary(source:source,options:nil),d=MTLRenderPipelineDescriptor()
        d.vertexFunction=library.makeFunction(name:"sticker_vertex");d.fragmentFunction=library.makeFunction(name:"sticker_fragment")
        let attachment=d.colorAttachments[0]!;attachment.pixelFormat=pixelFormat;attachment.isBlendingEnabled=true
        attachment.sourceRGBBlendFactor = .one;attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        attachment.sourceAlphaBlendFactor = .one;attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        pipeline=try device.makeRenderPipelineState(descriptor:d);format=pixelFormat
    }
}
