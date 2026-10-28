import Foundation
import CoreFoundation
import CoreGraphics
import ZIPFoundation

/// 宿主明确声明已具备的能力；主题包不自动启动检测器或下载模型。
public struct ThemeCapabilities {
    public let faceTracking: Bool, segmentation: Bool, extendedBeauty: Bool
    public init(faceTracking:Bool=false,segmentation:Bool=false,extendedBeauty:Bool=false) {
        self.faceTracking=faceTracking;self.segmentation=segmentation;self.extendedBeauty=extendedBeauty
    }
}
/// V1主题容器，内含V1贴纸与V2效果包。加载只在工作队列进行，固定一层ZIP、整体预算预检后再解码。
public final class BeautyThemePack {
    public let id:String, name:String
    public let sticker:StickerResourcePack?
    public let effects:[EffectResourceType:EffectResourcePack]
    public let requirements:ThemeCapabilities
    private init(id:String,name:String,sticker:StickerResourcePack?,effects:[EffectResourceType:EffectResourcePack]) {
        self.id=id;self.name=name;self.sticker=sticker;self.effects=effects
        let a=effects[.beauty]?.config["adjustments"] as? [String:NSNumber] ?? [:]
        let extended=a.contains { key,value in value.doubleValue != (["contrast","saturation"].contains(key) ? 1:0) }
        requirements=ThemeCapabilities(faceTracking:sticker != nil || effects[.motion]?.config["mode"] as? String == "FACE_FOLLOW" || (a["slimFace"]?.doubleValue ?? 0)>0 || ["bigEyes","slimNose","chinLength","mouthSize","lipstick","blusher","eyelashes"].contains { (a[$0]?.doubleValue ?? 0) != 0 },segmentation:effects[.background] != nil,extendedBeauty:extended)
    }
    /// 在切换任何渲染状态之前统一检查；缺失能力明确失败，宿主保留旧主题。
    public func validateCapabilities(_ capabilities:ThemeCapabilities) throws {
        try check(!requirements.faceTracking || capabilities.faceTracking,"Theme requires face tracking")
        try check(!requirements.segmentation || capabilities.segmentation,"Theme requires foreground segmentation")
        try check(!requirements.extendedBeauty || capabilities.extendedBeauty,"Theme requires extended beauty backend")
    }
    public static func load(url:URL) throws -> BeautyThemePack {
        try check((try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=33554432,"Theme ZIP too large")
        return try load(data:Data(contentsOf:url))
    }
    public static func load(data:Data) throws -> BeautyThemePack {
        try check(data.count<=33554432,"Theme ZIP too large")
        let kinds=["sticker","background","motion","beauty"],allowed=Set(["bundle.json","preview.png"]+["sticker.zip","background.zip","motion.zip","beauty.zip"])
        let files=try entries(data,allowed:allowed,maxCount:6,totalLimit:33554432)
        guard let description=files["bundle.json"],let root=try JSONSerialization.jsonObject(with:description) as? [String:Any],
              let id=root["id"] as? String,let name=root["name"] as? String,let refs=root["resources"] as? [String:Any],
              let version=root["bundleVersion"] as? NSNumber,CFGetTypeID(version) != CFBooleanGetTypeID() else { throw StickerResourceError.invalid("Missing theme fields") }
        try check(Set(root.keys)==Set(["bundleVersion","id","name","preview","resources"]) && version.doubleValue==1 && root["preview"] as? String == "preview.png","Invalid theme version/fields")
        try check(matches(id,"^[a-z][a-z0-9_]{0,47}$") && !name.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && name.utf16.count<=80 && !name.unicodeScalars.contains(where:{CharacterSet.controlCharacters.contains($0)}),"Invalid theme identity")
        try check(Set(refs.keys)==Set(kinds),"Unknown theme category")
        var selected:[String]=[]
        for kind in kinds { if refs[kind] is NSNull { continue };try check(refs[kind] as? String == kind+".zip","Invalid theme reference");selected.append(kind) }
        try check(!selected.isEmpty && Set(files.keys)==Set(["bundle.json","preview.png"]+selected.map{$0+".zip"}),"Missing/unreferenced theme content")
        let cover=try validatePNG(files["preview.png"]!);var pixels=cover.0*cover.1
        // 在任何子包CGImage解码前累计PNG尺寸；不允许把主题递归塞进子包。
        for kind in selected {
            let child=try entries(files[kind+".zip"]!,allowed:nil,maxCount:5,totalLimit:16777216)
            for (key,value) in child where key.hasSuffix(".png") { let size=try validatePNG(value);pixels+=size.0*size.1;try check(pixels<=8388608,"Theme pixel budget exceeded") }
        }
        var sticker:StickerResourcePack?;var effects:[EffectResourceType:EffectResourcePack]=[:]
        for kind in selected {
            let bytes=files[kind+".zip"]!
            if kind=="sticker" { sticker=try StickerResourcePack.load(data:bytes) }
            else { let pack=try EffectResourcePack.load(data:bytes);try check(pack.type.rawValue.lowercased()==kind,"Theme category mismatch");effects[pack.type]=pack }
        }
        return BeautyThemePack(id:id,name:name,sticker:sticker,effects:effects)
    }
    private static func entries(_ data:Data,allowed:Set<String>?,maxCount:Int,totalLimit:Int) throws -> [String:Data] {
        try check(data.count<=totalLimit,"ZIP input budget exceeded")
        let archive=try Archive(data:data,accessMode:.read);var files:[String:Data]=[:],total=0
        for entry in archive {
            let name=entry.path
            try check(entry.type == .file && files.count<maxCount && files[name]==nil && (allowed?.contains(name) ?? (name=="manifest.json" || pngName(name))),"Invalid ZIP entry")
            let limit=name.hasSuffix(".json") ? 65536:name.hasSuffix(".zip") ? 16777216:4194304
            try check(entry.uncompressedSize<=UInt64(limit),"Entry budget exceeded")
            var bytes=Data()
            let crc=try archive.extract(entry) { chunk in try check(bytes.count+chunk.count<=limit,"ZIP expanded too far");bytes.append(chunk) }
            total+=bytes.count;try check(total<=totalLimit && bytes.count==Int(entry.uncompressedSize) && crc==entry.checksum,"ZIP size/checksum mismatch")
            files[name]=bytes
        }
        return files
    }
}
