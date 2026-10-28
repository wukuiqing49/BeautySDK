import Foundation
import CoreGraphics
import Metal

/// V4图片睫毛配置。图片/绑定曲线只在切包时准备，跨端使用相同原始帧坐标契约。
public struct EyeMakeupResource {
    public let image:CGImage
    public let root:[SIMD2<Float>]
    public let length:Float
    public let mirrorRight:Bool
    public let strength:Float
    public static func from(_ pack:EffectResourcePack?) throws -> EyeMakeupResource? {
        guard let pack=pack,let e=pack.config["eyeMakeup"] as? [String:Any] else {return nil}
        guard let name=e["image"] as? String,let image=pack.images[name],let root=e["root"] as? [[NSNumber]],
            let length=e["length"] as? NSNumber,let mirror=e["mirrorRight"] as? Bool,
            let a=pack.config["adjustments"] as? [String:NSNumber],let strength=a["eyelashes"] else {throw StickerResourceError.invalid("Invalid eye resource")}
        return EyeMakeupResource(image:image,root:root.map {SIMD2($0[0].floatValue,$0[1].floatValue)},length:length.floatValue,mirrorRight:mirror,strength:strength.floatValue)
    }
    /// 非预乘RGBA，和Android PNG上传一致；仅配置切换调用。
    static func rgba(_ image:CGImage) throws -> [UInt8] {
        let w=image.width,h=image.height
        var bytes=[UInt8](repeating:0,count:w*h*4)
        let ok=bytes.withUnsafeMutableBytes { p -> Bool in
            guard let c=CGContext(data:p.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w*4,
                space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else {return false}
            c.translateBy(x:0,y:CGFloat(h));c.scaleBy(x:1,y:-1);c.draw(image,in:CGRect(x:0,y:0,width:CGFloat(w),height:CGFloat(h)));return true
        }
        try check(ok,"Cannot decode eyelash RGBA")
        for i in stride(from:0,to:bytes.count,by:4) {let a=Int(bytes[i+3]);if a>0 {for c in 0..<3 {bytes[i+c]=UInt8(min(255,(Int(bytes[i+c])*255+a/2)/a))}}}
        return bytes
    }
}

private let eyeLids=[[33,246,161,160,159,158,157,173,133],[263,466,388,387,386,385,384,398,362]]
private let eyeTriangles=[0,1,2,2,1,3]
/// 与Web/Android同拓扑，xy为原始帧NDC且y向下；Metal编码时再反转y，不重复应用旋转镜像。
public enum EyeMakeupGeometry {
    @discardableResult public static func fill(points:[SIMD2<Float>],width:Int,height:Int,resource:EyeMakeupResource,vertices:inout [SIMD4<Float>]) -> Bool {
        precondition(vertices.count==288)
        for i in vertices.indices {vertices[i] = .zero}
        guard points.count>=478 && width>0 && height>0 else {return false}
        let w=Float(width),h=Float(height)
        func valid(_ i:Int)->Bool {let p=points[i];return p.x.isFinite && p.y.isFinite && (0...1).contains(p.x) && (0...1).contains(p.y)}
        var any=false
        for eye in 0..<2 {
            let ids=eyeLids[eye],brow=eye==0 ? 105:334,lower=eye==0 ? 145:374
            if !ids.allSatisfy(valid) || !valid(brow) || !valid(lower) {continue}
            let a=points[ids[0]],b=points[ids[8]],dx=(b.x-a.x)*w,dy=(b.y-a.y)*h,span=hypot(dx,dy)
            if span<8 {continue}
            let ax=dx/span,ay=dy/span,mid=points[ids[4]],eb=points[brow]
            let sign:Float = -ay*(eb.x-mid.x)*w+ax*(eb.y-mid.y)*h>=0 ? 1:-1
            let nx = -ay*sign,ny=ax*sign
            let gap=abs((mid.x-points[lower].x)*w*nx+(mid.y-points[lower].y)*h*ny)
            let amount=max(0,min(1,gap/span/0.3)),open=amount*amount*(3-2*amount)
            let length=span*resource.length*(0.65+0.35*open)
            for column in 0..<8 {for row in 0..<3 {for v in 0..<6 {
                let corner=eyeTriangles[v],col=column+corner%2,t=Float(row+(corner>=2 ? 1:0))/3
                let root=points[ids[col]],before=points[ids[max(0,col-1)]],after=points[ids[min(8,col+1)]]
                let tx=(after.x-before.x)*w,ty=(after.y-before.y)*h,size=hypot(tx,ty)
                let direction:Float = -ty*nx+tx*ny>=0 ? 1:-1
                var px=nx,py=ny
                if size>0.001 {px=nx*0.65-ty/size*direction*0.35;py=ny*0.65+tx/size*direction*0.35;let n=hypot(px,py);px/=n;py/=n}
                let uv=resource.root[eye==1 && !resource.mirrorRight ? 8-col:col]
                let offset=(eye*8+column)*18+row*6+v
                vertices[offset]=SIMD4((root.x+px*length*t/w)*2-1,(root.y+py*length*t/h)*2-1,uv.x,uv.y*(1-t))
            }}}
            any=true
        }
        return any
    }
}

/// 宿主串行渲染队列调用；PNG切包上传一次，无摄像头、推理、GPU等待或逐帧图像转换。
public final class MetalEyeMakeupRenderer {
    private let device:MTLDevice
    private var resource:EyeMakeupResource?
    private var image:MTLTexture?
    private var pipeline:MTLRenderPipelineState?
    private var format:MTLPixelFormat = .invalid
    private var vertices=[SIMD4<Float>](repeating:.zero,count:288)
    public var isActive:Bool {return (resource?.strength ?? 0)>0}
    public init(device:MTLDevice) {self.device=device}
    public func setPack(_ pack:EffectResourcePack?) throws {
        let next=try EyeMakeupResource.from(pack)
        var texture:MTLTexture?
        if let r=next {
            let d=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:r.image.width,height:r.image.height,mipmapped:true)
            d.usage=[.shaderRead];d.storageMode = .shared
            guard let t=device.makeTexture(descriptor:d) else {throw StickerResourceError.invalid("Eye texture allocation failed")}
            // 仅切包时在CPU生成mipmap，不提交额外commandBuffer或等待GPU。
            var pixels=try EyeMakeupResource.rgba(r.image)
            var width=r.image.width,height=r.image.height,level=0
            while true {
                pixels.withUnsafeBytes {t.replace(region:MTLRegionMake2D(0,0,width,height),mipmapLevel:level,withBytes:$0.baseAddress!,bytesPerRow:width*4)}
                if width==1 && height==1 {break}
                let nw=max(1,width/2),nh=max(1,height/2)
                var next=[UInt8](repeating:0,count:nw*nh*4)
                for y in 0..<nh {for x in 0..<nw {for c in 0..<4 {
                    var sum=0,count=0
                    for yy in (y*2)..<min(height,y*2+2) {for xx in (x*2)..<min(width,x*2+2) {sum+=Int(pixels[(yy*width+xx)*4+c]);count+=1}}
                    next[(y*nw+x)*4+c]=UInt8(sum/max(1,count))
                }}}
                pixels=next;width=nw;height=nh;level+=1
            }
            texture=t
        }
        resource=next;image=texture
    }
    /// landmarks为478点原始帧归一化坐标；缺点/无人脸只复制原帧。宿主自行提交commandBuffer。
    public func encode(input:MTLTexture,output:MTLTexture,landmarks:[SIMD2<Float>],commandBuffer:MTLCommandBuffer) throws {
        try check(input !== output && input.width==output.width && input.height==output.height && input.pixelFormat==output.pixelFormat && [.rgba8Unorm,.bgra8Unorm].contains(input.pixelFormat) && output.usage.contains(.renderTarget),"Expected separate equal-size RGBA8/BGRA8 textures")
        if pipeline == nil || format != output.pixelFormat {try makePipeline(output.pixelFormat)}
        guard let blit=commandBuffer.makeBlitCommandEncoder() else {throw StickerResourceError.invalid("Cannot create eye blit")}
        blit.copy(from:input,sourceSlice:0,sourceLevel:0,sourceOrigin:MTLOrigin(x:0,y:0,z:0),sourceSize:MTLSize(width:input.width,height:input.height,depth:1),to:output,destinationSlice:0,destinationLevel:0,destinationOrigin:MTLOrigin(x:0,y:0,z:0));blit.endEncoding()
        guard let r=resource,let image=image,r.strength>0,EyeMakeupGeometry.fill(points:landmarks,width:input.width,height:input.height,resource:r,vertices:&vertices) else {return}
        for i in vertices.indices {vertices[i].y = -vertices[i].y}
        let d=MTLRenderPassDescriptor();d.colorAttachments[0].texture=output;d.colorAttachments[0].loadAction = .load;d.colorAttachments[0].storeAction = .store
        guard let e=commandBuffer.makeRenderCommandEncoder(descriptor:d) else {throw StickerResourceError.invalid("Cannot create eye encoder")}
        e.setRenderPipelineState(pipeline!);e.setFragmentTexture(image,index:0)
        var strength=r.strength;e.setFragmentBytes(&strength,length:MemoryLayout<Float>.size,index:0)
        // 每眼144顶点、2304bytes，小于setVertexBytes的4KiB限制；Metal复制数据，无在途缓冲覆盖。
        vertices.withUnsafeBytes {bytes in for eye in 0..<2 {e.setVertexBytes(bytes.baseAddress!.advanced(by:eye*2304),length:2304,index:0);e.drawPrimitives(type:.triangle,vertexStart:0,vertexCount:144)}}
        e.endEncoding()
    }
    private func makePipeline(_ pixelFormat:MTLPixelFormat) throws {
        let source="""
        #include <metal_stdlib>
        using namespace metal;
        struct V {float4 position [[position]];float2 uv;};
        vertex V eyeVertex(uint id [[vertex_id]],constant float4* p [[buffer(0)]]) {V o;o.position=float4(p[id].xy,0,1);o.uv=p[id].zw;return o;}
        fragment float4 eyeFragment(V in [[stage_in]],texture2d<float> image [[texture(0)]],constant float& strength [[buffer(0)]]) {
            constexpr sampler s(coord::normalized,address::clamp_to_edge,filter::linear,mip_filter::linear);float4 c=image.sample(s,in.uv);float a=c.a*strength;return float4(c.rgb*a,a);
        }
        """
        let library=try device.makeLibrary(source:source,options:nil),d=MTLRenderPipelineDescriptor()
        d.vertexFunction=library.makeFunction(name:"eyeVertex");d.fragmentFunction=library.makeFunction(name:"eyeFragment")
        let a=d.colorAttachments[0]!;a.pixelFormat=pixelFormat;a.isBlendingEnabled=true
        a.sourceRGBBlendFactor = .one;a.destinationRGBBlendFactor = .oneMinusSourceAlpha
        a.sourceAlphaBlendFactor = .zero;a.destinationAlphaBlendFactor = .one
        pipeline=try device.makeRenderPipelineState(descriptor:d);format=pixelFormat
    }
}
