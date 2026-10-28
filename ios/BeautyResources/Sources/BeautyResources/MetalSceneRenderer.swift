import Foundation
import Metal
import MetalKit

/// 场景资源消费端：宿主先将帧旋转/镜像为正向2D纹理，再执行背景/美颜，最后运镜。
/// 不采集视频；蒙版由宿主人像分割提供。commandBuffer、输入/输出纹理由宿主持有并等待GPU完成。
public enum SceneRenderStage { case all, sceneOnly, motionOnly }
public final class MetalSceneRenderer {
    private let device: MTLDevice
    private let pipeline: MTLComputePipelineState
    private var packs: [EffectResourceType: EffectResourcePack] = [:]
    private var background: MTLTexture?
    public init(device: MTLDevice) throws {
        self.device=device
        let library=try device.makeLibrary(source:Self.source,options:nil)
        pipeline=try device.makeComputePipelineState(function:library.makeFunction(name:"scene")!)
    }
    /// 加载线程解码后的PNG在渲染队列一次性上传。扩展美型由宿主后端消费，本渲染器明确拒绝。
    public func setPack(_ pack: EffectResourcePack, allowEyeMakeup:Bool = false) throws {
        var texture: MTLTexture?
        if pack.type == .beauty, let a=pack.config["adjustments"] as? [String:NSNumber] {
            try check(allowEyeMakeup || (a["eyelashes"]?.doubleValue ?? 0)==0,"Use MetalEyeMakeupRenderer or MetalThemeRenderer for eye makeup")
            try check(["slimFace","bigEyes","slimNose","chinLength","mouthSize","lipstick","blusher"].allSatisfy { (a[$0]?.doubleValue ?? 0) == 0 },"Face reshaping requires a host beauty backend")
        }
        if pack.type == .background,let name=pack.config["image"] as? String,let image=pack.images[name] {
            texture=try MTKTextureLoader(device:device).newTexture(cgImage:image,options:[.SRGB:false,.origin:MTKTextureLoader.Origin.topLeft.rawValue])
        }
        packs[pack.type]=pack
        if pack.type == .background { background=texture }
    }
    public func clear(_ type:EffectResourceType) { packs.removeValue(forKey:type);if type == .background { background=nil } }
    /// 输入已正向；mask为同方向R8置信度，0背景/1前景且必须对应当前有效帧。缺mask时保留真实背景。
    /// elapsedMs为视频会话时间，faceCenter为可选正向人脸中心；非循环时间轴结束后保持末帧。
    public func encode(input:MTLTexture,output:MTLTexture,mask:MTLTexture?,elapsedMs:Double,faceCenter:SIMD2<Float>? = nil,commandBuffer:MTLCommandBuffer,stage:SceneRenderStage = .all) throws {
        try check(input !== output && input.width==output.width && input.height==output.height && input.pixelFormat == .rgba8Unorm && output.pixelFormat == .rgba8Unorm && output.usage.contains(.shaderWrite),"Expected separate equal-size RGBA8 textures and shaderWrite output")
        try check(elapsedMs.isFinite && elapsedMs>=0,"Invalid video time")
        // float4数组避免Swift/Metal结构体对齐差异。
        var p=[SIMD4<Float>](repeating:.zero,count:8)
        p[0]=SIMD4(.5,.5,1,0)
        if stage != .sceneOnly,let pack=packs[.motion] {
            let c=pack.config,mode=c["mode"] as? String ?? "",zoom=(c["zoom"] as? NSNumber)?.floatValue ?? 1,period=(c["periodMs"] as? NSNumber)?.doubleValue ?? 4000,phase=elapsedMs.truncatingRemainder(dividingBy:period)/period*2*Double.pi
            if let t=pack.timeline { let f=t.sample(elapsedMs);p[0]=SIMD4(Float(f.x),Float(f.y),Float(f.zoom),0) }
            else if mode=="PUSH_IN" { p[0].z=1+(zoom-1)*Float((1-cos(phase))/2) }
            else { p[0].z=zoom;if mode=="PAN" { p[0].x += (0.5-0.5/zoom)*Float(sin(phase)) };if mode=="FACE_FOLLOW",let face=faceCenter { try check(face.x.isFinite && face.y.isFinite,"Invalid face center");p[0].x=face.x;p[0].y=face.y } }
        }
        if stage != .motionOnly,let pack=packs[.background],mask != nil {
            let c=pack.config,mode=c["mode"] as? String ?? ""
            p[0].w=mode=="BLUR" ? 1:mode=="COLOR" ? 2:3
            let hex=String((c["color"] as? String ?? "#26334d").dropFirst()),color=UInt32(hex,radix:16) ?? 0
            p[1]=SIMD4(Float((color>>16)&255)/255,Float((color>>8)&255)/255,Float(color&255)/255,(c["blurRadius"] as? NSNumber)?.floatValue ?? 12)
            let focus=c["focus"] as? [NSNumber] ?? [0.5,0.5]
            p[2]=SIMD4(focus[0].floatValue,focus[1].floatValue,Float(input.width)/Float(input.height),background.map { Float($0.width)/Float($0.height) } ?? 1)
        }
        if stage != .motionOnly,let c=packs[.beauty]?.config {
            p[3]=SIMD4((c["smoothing"] as? NSNumber)?.floatValue ?? 0,(c["whitening"] as? NSNumber)?.floatValue ?? 0,c["filter"] as? String == "WARM" ? 1:c["filter"] as? String == "COOL" ? -1:0,0)
            if let a=c["adjustments"] as? [String:NSNumber] { p[4]=SIMD4(a["brightness"]!.floatValue,a["contrast"]!.floatValue,a["saturation"]!.floatValue,a["rosy"]!.floatValue);p[5].x=a["sharpening"]!.floatValue }
        } else { p[4]=SIMD4(0,1,1,0) }
        guard let encoder=commandBuffer.makeComputeCommandEncoder() else { throw StickerResourceError.invalid("Cannot encode scene") }
        encoder.setComputePipelineState(pipeline);encoder.setTexture(input,index:0);encoder.setTexture(output,index:1);encoder.setTexture(mask ?? input,index:2);encoder.setTexture(background ?? input,index:3)
        p.withUnsafeBytes { bytes in encoder.setBytes(bytes.baseAddress!,length:bytes.count,index:0) }
        let width=pipeline.threadExecutionWidth,height=max(1,pipeline.maxTotalThreadsPerThreadgroup/width)
        encoder.dispatchThreads(MTLSize(width:input.width,height:input.height,depth:1),threadsPerThreadgroup:MTLSize(width:width,height:height,depth:1));encoder.endEncoding()
    }
    private static let source = """
    #include <metal_stdlib>
    using namespace metal;
    kernel void scene(texture2d<float,access::sample> image [[texture(0)]],texture2d<float,access::write> output [[texture(1)]],texture2d<float,access::sample> mask [[texture(2)]],texture2d<float,access::sample> background [[texture(3)]],constant float4* p [[buffer(0)]],uint2 id [[thread_position_in_grid]]) {
        if(id.x>=output.get_width() || id.y>=output.get_height())return;
        constexpr sampler s(coord::normalized,address::clamp_to_edge,filter::linear);
        float2 size=float2(output.get_width(),output.get_height()),uv=(float2(id)+.5)/size;
        float side=1/p[0].z;float2 center=clamp(p[0].xy,float2(side*.5),float2(1-side*.5));uv=(uv-.5)*side+center;
        float4 original=image.sample(s,uv);float3 color=original.rgb,mean=color;
        if(p[3].x>0 || p[5].x>0){mean=0;for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)mean+=image.sample(s,uv+float2(x,y)/size).rgb/9;}
        color=mix(color,mean,p[3].x*.6);color+=p[3].y*.15;color+=float3(.03,0,-.02)*p[3].z;
        color=(color-.5)*p[4].y+.5+p[4].x*.2;float l=dot(color,float3(.299,.587,.114));color=mix(float3(l),color,p[4].z);color+=float3(.03,-.01,-.01)*p[4].w;color+=(original.rgb-mean)*p[5].x;
        int mode=int(p[0].w);
        if(mode>0){float3 bg=p[1].rgb;if(mode==1){bg=0;for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++)bg+=image.sample(s,uv+float2(x,y)*p[1].w*.5/size).rgb/25;}
        if(mode==3){float2 extent=float2(min(1.0,p[2].z/p[2].w),min(1.0,p[2].w/p[2].z));bg=background.sample(s,(uv-.5)*extent+clamp(p[2].xy,extent*.5,1-extent*.5)).rgb;}color=mix(bg,color,clamp(mask.sample(s,uv).r,0.0,1.0));}
        output.write(float4(clamp(color,0.0,1.0),original.a),id);
    }
    """
}
