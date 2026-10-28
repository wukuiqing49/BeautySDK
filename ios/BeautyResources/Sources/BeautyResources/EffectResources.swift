import Foundation
import CoreFoundation
import CoreGraphics
import ImageIO
import ZIPFoundation

public enum EffectResourceType: String, Hashable { case background = "BACKGROUND", motion = "MOTION", beauty = "BEAUTY" }
public struct MotionFrame {
    public let timeMs: Double, x: Double, y: Double, zoom: Double
    public let smooth: Bool
}
/// 无状态关键帧采样，时间为视频会话相对毫秒；中心坐标使用正向画面。
public struct MotionTimeline {
    public let frames: [MotionFrame]
    public let loop: Bool
    public func sample(_ elapsedMs: Double) -> MotionFrame {
        let duration=frames.last!.timeMs
        let t=loop ? max(0,elapsedMs).truncatingRemainder(dividingBy:duration) : min(duration,max(0,elapsedMs))
        let i=min(frames.count-2,frames.lastIndex(where: { $0.timeMs <= t }) ?? 0)
        let a=frames[i], b=frames[i+1]
        var u=(t-a.timeMs)/(b.timeMs-a.timeMs)
        if a.smooth { u=u*u*(3-2*u) }
        return MotionFrame(timeMs:t,x:a.x+(b.x-a.x)*u,y:a.y+(b.y-a.y)*u,zoom:a.zoom+(b.zoom-a.zoom)*u,smooth:a.smooth)
    }
}
/// V2跨端资源。配置和PNG只在加载线程解析，资源不持有相机、引擎或推理器。
public final class EffectResourcePack {
    public let type: EffectResourceType
    public let id: String, name: String
    public let config: [String: Any]
    public let images: [String: CGImage]
    public let timeline: MotionTimeline?
    private init(type: EffectResourceType,id:String,name:String,config:[String:Any],images:[String:CGImage],timeline:MotionTimeline?) {
        self.type=type;self.id=id;self.name=name;self.config=config;self.images=images;self.timeline=timeline
    }
    public static func load(url:URL) throws -> EffectResourcePack {
        try check((try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=16777216,"ZIP too large")
        return try load(data:Data(contentsOf:url))
    }
    public static func load(data:Data) throws -> EffectResourcePack {
        try check(data.count<=16777216,"ZIP too large")
        let archive=try Archive(data:data,accessMode:.read)
        var entries:[String:Data]=[:], total=0
        for entry in archive {
            try check(entry.type == .file && entries.count<5 && entries[entry.path]==nil && (entry.path=="manifest.json" || pngName(entry.path)),"Invalid ZIP entry")
            let limit=entry.path=="manifest.json" ? 65536 : 4194304
            try check(entry.uncompressedSize<=UInt64(limit),"Entry too large")
            var bytes=Data()
            let crc=try archive.extract(entry) { chunk in
                try check(bytes.count+chunk.count<=limit,"Entry expanded too far");bytes.append(chunk)
            }
            total+=bytes.count
            try check(total<=16777216 && crc==entry.checksum && bytes.count==Int(entry.uncompressedSize),"ZIP checksum/size mismatch")
            entries[entry.path]=bytes
        }
        guard let description=entries.removeValue(forKey:"manifest.json"), let root=try JSONSerialization.jsonObject(with:description) as? [String:Any],
              let type=EffectResourceType(rawValue:root["type"] as? String ?? ""),let id=root["id"] as? String,let name=root["name"] as? String,
              let preview=root["preview"] as? String,let c=root["config"] as? [String:Any] else { throw StickerResourceError.invalid("Missing manifest fields") }
        func fields(_ object:[String:Any],_ names:[String]) throws { try check(Set(object.keys)==Set(names),"Unknown/missing fields") }
        func n(_ object:[String:Any],_ key:String,_ low:Double,_ high:Double) throws -> Double {
            guard let v=object[key] as? NSNumber,CFGetTypeID(v) != CFBooleanGetTypeID() else { throw StickerResourceError.invalid("Expected number") }
            let d=v.doubleValue;try check(d.isFinite && d>=low && d<=high,"Number out of range");return d
        }
        try fields(root,["schemaVersion","type","id","name","preview","config"])
        let version=try n(root,"schemaVersion",2,4)
        try check(version==2 || (version==3 || version==4) && type == .beauty,"Unsupported version")
        try check(matches(id,"^[a-z][a-z0-9_]{0,47}$") && !name.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && name.utf16.count<=80 && !name.unicodeScalars.contains(where:{CharacterSet.controlCharacters.contains($0)}) && pngName(preview),"Invalid identity")
        var refs=Set([preview])
        var timeline:MotionTimeline? = nil
        switch type {
        case .background:
            try fields(c,["mode","image","color","blurRadius","focus"])
            let mode=c["mode"] as? String ?? ""
            try check(["BLUR","COLOR","IMAGE"].contains(mode),"Invalid background mode")
            if mode=="IMAGE" { guard let image=c["image"] as? String else { throw StickerResourceError.invalid("Missing image") };try check(pngName(image),"Invalid image");refs.insert(image) }
            else { try check(c["image"] is NSNull,"Unexpected image") }
            try check(matches(c["color"] as? String ?? "","^#[0-9a-fA-F]{6}$"),"Invalid color")
            _=try n(c,"blurRadius",0,32)
            guard let focus=c["focus"] as? [Any], focus.count==2 else { throw StickerResourceError.invalid("Invalid focus") }
            for v in focus { _=try n(["v":v],"v",0,1) }
        case .motion:
            try fields(c,["mode","zoom","periodMs","timeline"])
            let mode=c["mode"] as? String ?? ""
            try check(["PUSH_IN","PAN","FACE_FOLLOW","KEYFRAMES"].contains(mode),"Invalid motion mode")
            _=try n(c,"zoom",1,2);let period=try n(c,"periodMs",1000,60000);try check(period.rounded()==period,"Noninteger period")
            if mode=="KEYFRAMES" {
                guard let t=c["timeline"] as? [String:Any],let flag=t["loop"] as? NSNumber,CFGetTypeID(flag)==CFBooleanGetTypeID(),let frames=t["frames"] as? [[String:Any]] else { throw StickerResourceError.invalid("Invalid timeline") }
                try fields(t,["loop","frames"]);try check((2...32).contains(frames.count),"Frame count")
                var parsed:[MotionFrame]=[],previous = -1.0
                for f in frames {
                    try fields(f,["timeMs","x","y","zoom","easing"])
                    let time=try n(f,"timeMs",0,60000),x=try n(f,"x",0,1),y=try n(f,"y",0,1),z=try n(f,"zoom",1,2),e=f["easing"] as? String ?? ""
                    try check(time.rounded()==time && time>previous && ["LINEAR","SMOOTH"].contains(e),"Invalid keyframe")
                    parsed.append(MotionFrame(timeMs:time,x:x,y:y,zoom:z,smooth:e=="SMOOTH"));previous=time
                }
                let a=parsed.first!,b=parsed.last!
                try check(a.timeMs==0 && (!flag.boolValue || (a.x==b.x && a.y==b.y && a.zoom==b.zoom)),"Timeline must start at zero and close loop")
                timeline=MotionTimeline(frames:parsed,loop:flag.boolValue)
            } else { try check(c["timeline"] is NSNull,"Unexpected timeline") }
        case .beauty:
            try fields(c,["smoothing","whitening","filter","adjustments"] + (version==4 ? ["eyeMakeup"] : []))
            _=try n(c,"smoothing",0,1);_=try n(c,"whitening",0,1)
            try check(["NONE","WARM","COOL"].contains(c["filter"] as? String ?? ""),"Invalid filter")
            guard let a=c["adjustments"] as? [String:Any] else { throw StickerResourceError.invalid("Missing adjustments") }
            try fields(a,["rosy","brightness","contrast","saturation","sharpening","slimFace","bigEyes"] + (version>=3 ? ["lipstick","blusher","slimNose","chinLength","mouthSize"] : []) + (version==4 ? ["eyelashes"] : []))
            for key in a.keys { _=try n(a,key,["brightness","chinLength","mouthSize"].contains(key) ? -1:0,["contrast","saturation"].contains(key) ? 2:1) }
            if version==4 {
                guard let e=c["eyeMakeup"] as? [String:Any],let image=e["image"] as? String,
                    let root=e["root"] as? [[Any]],let mirror=e["mirrorRight"] as? NSNumber else {throw StickerResourceError.invalid("Missing eye makeup")}
                try fields(e,["kind","image","root","length","mirrorRight"])
                try check(e["kind"] as? String == "EYELASH" && pngName(image) && CFGetTypeID(mirror)==CFBooleanGetTypeID() && root.count==9,"Invalid eye makeup")
                _=try n(e,"length",0.02,0.5)
                var previous = -1.0
                for point in root {try check(point.count==2,"Invalid root point");let u=try n(["v":point[0]],"v",0,1);_=try n(["v":point[1]],"v",0.3,1);try check(previous<0 || u-previous>=0.01-0.000001,"Root points must increase");previous=u}
                let first=try n(["v":root[0][0]],"v",0,1),last=try n(["v":root[8][0]],"v",0,1)
                try check(last-first>=0.5-0.000001,"Root too narrow");refs.insert(image)
            }

        }
        try check(Set(entries.keys)==refs,"Missing/unreferenced PNG")
        var pixels=0
        for bytes in entries.values { let size=try validatePNG(bytes);pixels+=size.0*size.1;try check(pixels<=4194304,"Pixel budget") }
        var images:[String:CGImage]=[:]
        for (key,bytes) in entries {
            guard let source=CGImageSourceCreateWithData(bytes as CFData,nil),let image=CGImageSourceCreateImageAtIndex(source,0,nil) else { throw StickerResourceError.invalid("PNG decode failed") }
            images[key]=image
        }
        if version==4,let eye=c["eyeMakeup"] as? [String:Any],let name=eye["image"] as? String,let image=images[name] {
            let pixels=try EyeMakeupResource.rgba(image)
            var visible=false,transparent=false
            for i in stride(from:3,to:pixels.count,by:4) {visible = visible || pixels[i]>0;transparent = transparent || pixels[i]<255}
            try check(visible && transparent,"Eyelash PNG must have transparent and visible pixels")
        }
        return EffectResourcePack(type:type,id:id,name:name,config:c,images:images,timeline:timeline)
    }
}
