import Foundation
import Metal

/// 统一资源到现有Metal执行器的桥。后端未实现的美型/口红/腮红会在切换前明确失败。
/// 调用者在串行Metal队列setResources/encode，旧GPU工作完成后再释放资源句柄。
public final class MetalResourceRenderer {
    private let device:MTLDevice
    private var scene:MetalSceneRenderer
    private var eye:MetalEyeMakeupRenderer
    private var stickers=[MetalStickerRenderer]()
    private var a:MTLTexture?,b:MTLTexture?
    public init(device:MTLDevice) throws {self.device=device;scene=try MetalSceneRenderer(device:device);eye=MetalEyeMakeupRenderer(device:device)}
    public func setResources(_ resources:[UnifiedResource],state:ResourceUserState=ResourceUserState()) throws {
        var selected=[String:UnifiedResource]()
        for resource in resources {if resource.descriptor.category == .theme {selected.removeAll();for child in resource.children {selected[child.descriptor.slot]=child}}else{selected[resource.descriptor.slot]=resource}}
        var parameters=ResourceDefaults.neutral
        var filter="NONE",eyeResource:UnifiedResource?
        let nextScene=try MetalSceneRenderer(device:device),nextEye=MetalEyeMakeupRenderer(device:device)
        var nextStickers=[MetalStickerRenderer]()
        for resource in selected.values.sorted(by:{$0.descriptor.slot<$1.descriptor.slot}) {
            let d=resource.descriptor
            if let sticker=resource.legacySticker {let renderer=MetalStickerRenderer(device:device);try renderer.setPack(sticker);nextStickers.append(renderer)}
            if let effect=resource.legacyEffect,[EffectResourceType.background,.motion].contains(effect.type){try nextScene.setPack(effect)}
            if let payload=resource.effect["payload"] as? [String:Any] {
                if resource.effect["effectType"] as? String == "PARAMETERS",let values=payload["parameters"] as? [String:Any] {parameters.merge(try ResourceDefaults.parameters(values)){_,new in new}}
                else if let config=payload["config"] as? [String:Any] {
                    let adjustments=config["adjustments"] as? [String:Any] ?? [:]
                    for key in d.scope {let short=String(key.split(separator:".").last!);if let n=(short=="smoothing" || short=="whitening" ? config[short]:adjustments[short]) as? NSNumber {parameters[key]=n.doubleValue}}
                    if d.category == .filter || d.subcategory=="LEGACY" {filter=config["filter"] as? String ?? filter}
                    if config["eyeMakeup"] != nil && (d.subcategory=="EYELASH" || d.subcategory=="LEGACY") {eyeResource=resource}
                }
            }
        }
        parameters.merge(state.parameters){_,new in new};_=try ResourceDefaults.parameters(parameters.mapValues{$0 as Any})
        if !state.enabled {for(key,value)in ResourceDefaults.neutral {parameters[key]=value};filter="NONE";eyeResource=nil}
        var config:[String:Any]=["smoothing":parameters["beauty.smoothing"] ?? 0,"whitening":parameters["beauty.whitening"] ?? 0,"filter":filter]
        var adjustments=[String:Double]()
        for(key,value)in parameters where !["beauty.smoothing","beauty.whitening"].contains(key){adjustments[String(key.split(separator:".").last!)]=value}
        let preview=selected.values.first?.preview ?? Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")!
        var files=["preview.png":preview],version=3
        if let resource=eyeResource,let payload=resource.effect["payload"] as? [String:Any],let oldConfig=payload["config"] as? [String:Any],let eyeConfig=oldConfig["eyeMakeup"] as? [String:Any],let image=eyeConfig["image"] as? String,let bytes=resource.files[image] {version=4;config["eyeMakeup"]=eyeConfig;files[image]=bytes}
        else {try check((adjustments["eyelashes"] ?? 0)==0,"Metal image eyelashes require texture resource");adjustments.removeValue(forKey:"eyelashes")}
        config["adjustments"]=adjustments
        let manifest:[String:Any]=["schemaVersion":version,"type":"BEAUTY","id":"composed_beauty","name":"Composed beauty","preview":"preview.png","config":config]
        files["manifest.json"]=try JSONSerialization.data(withJSONObject:manifest)
        let beauty=try EffectResourcePack.load(data:UnifiedResource.write(files))
        try nextScene.setPack(beauty,allowEyeMakeup:true);try nextEye.setPack(beauty)
        scene=nextScene;eye=nextEye;stickers=nextStickers
    }
    public func encode(input:MTLTexture,output:MTLTexture,mask:MTLTexture?,elapsedMs:Double,poses:[StickerAnchor:StickerPose],landmarks:[SIMD2<Float>]=[],faceCenter:SIMD2<Float>?=nil,commandBuffer:MTLCommandBuffer) throws {
        try check(input !== output && input.width==output.width && input.height==output.height && input.pixelFormat == .rgba8Unorm && output.pixelFormat == .rgba8Unorm && output.usage.contains(.shaderWrite),"Expected separate equal-size RGBA8 textures")
        if a?.width != input.width || a?.height != input.height {let d=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:input.width,height:input.height,mipmapped:false);d.usage=[.shaderRead,.shaderWrite,.renderTarget];d.storageMode = .private;guard let x=device.makeTexture(descriptor:d),let y=device.makeTexture(descriptor:d)else{throw StickerResourceError.invalid("Scratch allocation failed")};a=x;b=y}
        try scene.encode(input:input,output:a!,mask:mask,elapsedMs:elapsedMs,faceCenter:faceCenter,commandBuffer:commandBuffer,stage:.sceneOnly)
        var current=a!,next=b!
        if eye.isActive {try eye.encode(input:current,output:next,landmarks:landmarks,commandBuffer:commandBuffer);swap(&current,&next)}
        for sticker in stickers {try sticker.encode(input:current,output:next,commandBuffer:commandBuffer,poses:poses,mirrored:false);swap(&current,&next)}
        try scene.encode(input:current,output:output,mask:nil,elapsedMs:elapsedMs,faceCenter:faceCenter,commandBuffer:commandBuffer,stage:.motionOnly)
    }
}
