import Foundation
import CoreFoundation
import CryptoKit
import ZIPFoundation
import ImageIO

/// 跨平台统一资源。仅资源层：宿主管理检测器、Metal队列和帧生命周期。
public enum ResourceCategory:String,CaseIterable {case beauty="BEAUTY",reshape="RESHAPE",makeup="MAKEUP",filter="FILTER",sticker="STICKER",background="BACKGROUND",motion="MOTION",theme="THEME"}
public struct ResourceDescriptor {
    public let id:String,revision:Int,category:ResourceCategory,subcategory:String,slot:String,defaultLocale:String,name:String,description:String
    public let localizations:[String:[String:String]],features:Set<String>,scope:Set<String>
    public func displayName(locale:String)->String {
        let parts=locale.replacingOccurrences(of:"_",with:"-").split(separator:"-")
        var candidates=[locale]
        if let first=parts.first,first != "zh" || !parts.contains(where:{["TW","HK","MO","Hant"].contains(String($0))}) {candidates.append(String(first))}
        candidates.append(defaultLocale)
        for key in candidates {if let text=localizations.first(where:{$0.key.caseInsensitiveCompare(key) == .orderedSame})?.value["name"] {return text}}
        return name
    }
}
/// 所有解析在宿主后台IO队列执行，不在视频回调访问磁盘或解压。
public final class UnifiedResource {
    public let descriptor:ResourceDescriptor,preview:Data,effect:[String:Any],children:[UnifiedResource]
    public let legacyEffect:EffectResourcePack?,legacySticker:StickerResourcePack?
    public let files:[String:Data]
    private init(_ d:ResourceDescriptor,_ preview:Data,_ e:[String:Any],_ files:[String:Data],_ children:[UnifiedResource],_ effect:EffectResourcePack?,_ sticker:StickerResourcePack?){descriptor=d;self.preview=preview;self.effect=e;self.files=files;self.children=children;legacyEffect=effect;legacySticker=sticker}
    static func fields(_ o:[String:Any],_ names:[String]) throws {try check(Set(o.keys)==Set(names),"Unknown/missing fields")}
    static func integer(_ value:Any?,_ low:Int,_ high:Int) throws -> Int {guard let n=value as? NSNumber,CFGetTypeID(n) != CFBooleanGetTypeID() else {throw StickerResourceError.invalid("Expected integer")};let d=n.doubleValue;try check(d.isFinite && d.rounded()==d && d>=Double(low) && d<=Double(high),"Integer out of range");return n.intValue}
    static func object(_ data:Data) throws -> [String:Any] {guard let o=try JSONSerialization.jsonObject(with:data) as? [String:Any] else {throw StickerResourceError.invalid("Expected object")};return o}
    public static func parseDescriptor(_ m:[String:Any]) throws -> ResourceDescriptor {
        try fields(m,["packageVersion","id","revision","category","subcategory","slot","defaultLocale","name","description","localizations","preview","effect","requirements","scope"])
        _=try integer(m["packageVersion"],1,1)
        guard let id=m["id"] as? String,let categoryText=m["category"] as? String,let category=ResourceCategory(rawValue:categoryText),let sub=m["subcategory"] as? String,let slot=m["slot"] as? String,let locale=m["defaultLocale"] as? String,let name=m["name"] as? String,let description=m["description"] as? String,let ls=m["localizations"] as? [String:[String:String]],let req=m["requirements"] as? [String:Any],let features=req["features"] as? [String],let scope=m["scope"] as? [String] else {throw StickerResourceError.invalid("Invalid resource fields")}
        try fields(req,["features"])
        let expectedSlot=[ResourceCategory.makeup,.sticker].contains(category) ? categoryText.lowercased()+"."+sub.lowercased():categoryText.lowercased()
        try check(matches(id,"^[a-z][a-z0-9_]{0,47}$") && matches(sub,"^[A-Z_]{1,32}$") && slot==expectedSlot && matches(locale,"^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$"),"Invalid identity/slot/locale")
        try check(!name.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && name.utf16.count<=80 && description.utf16.count<=500 && !(name+description).unicodeScalars.contains(where:{CharacterSet.controlCharacters.contains($0)}),"Invalid text")
        try check(m["preview"] as? String == "preview.png" && m["effect"] as? String == "effect.json" && ls.count<=30 && features.count<=20 && Set(features).count==features.count && Set(scope).count==scope.count,"Invalid references")
        for (key,value) in ls {try check(matches(key,"^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$") && Set(value.keys)==Set(["name","description"]) && !(value["name"] ?? "").isEmpty && (value["name"]?.utf16.count ?? 0)<=80 && (value["description"]?.utf16.count ?? 0)<=500,"Invalid translation")}
        for key in scope {try check(ResourceDefaults.neutral[key] != nil && (sub=="LEGACY" || ResourceDefaults.category(key)==category),"Invalid scope")}
        if category == .makeup {try check(["LIPSTICK","BLUSHER","EYELASH"].contains(sub) && scope==["makeup."+(sub=="EYELASH" ? "eyelashes":sub.lowercased())],"Unsupported makeup scope")}
        return ResourceDescriptor(id:id,revision:try integer(m["revision"],1,Int(Int32.max)),category:category,subcategory:sub,slot:slot,defaultLocale:locale,name:name,description:description,localizations:ls,features:Set(features),scope:Set(scope))
    }
    public static func load(data:Data) throws -> UnifiedResource {try load(data:data,nested:false)}
    private static func load(data:Data,nested:Bool) throws -> UnifiedResource {
        let fs=try read(data),m=try object(try required(fs,"manifest.json")),d=try parseDescriptor(m),e=try object(try required(fs,"effect.json")),preview=try required(fs,"preview.png")
        _=try pngPixels(preview);try fields(e,["effectVersion","effectType","payload"]);_=try integer(e["effectVersion"],1,1)
        guard let type=e["effectType"] as? String,let payload=e["payload"] as? [String:Any] else {throw StickerResourceError.invalid("Invalid effect")}
        if type=="THEME" {
            try check(d.category == .theme && !nested && d.scope.isEmpty,"Nested theme");try fields(payload,["resources"])
            guard let refs=payload["resources"] as? [[String:Any]] else {throw StickerResourceError.invalid("Invalid theme references")}
            try check((1...20).contains(refs.count),"Invalid resource count");var names=Set(["manifest.json","preview.png","effect.json"]),children=[UnifiedResource](),pixels=try pngPixels(preview)
            for ref in refs {try fields(ref,["file","sha256"]);guard let path=ref["file"] as? String,let hash=ref["sha256"] as? String else {throw StickerResourceError.invalid("Invalid child")};try check(matches(path,"^resource_[0-9]+\\.zip$") && names.insert(path).inserted,"Invalid child path");let bytes=try required(fs,path);try check(digest(bytes)==hash,"Child digest mismatch");let raw=try read(bytes);for (name,image) in raw where name.hasSuffix(".png") {pixels+=try pngPixels(image)};try check(pixels<=8388608,"Theme pixel budget");children.append(try load(data:bytes,nested:true))}
            try check(Set(fs.keys)==names && Set(children.map{$0.descriptor.slot}).count==children.count && d.features==Set(children.flatMap{Array($0.descriptor.features)}).union(["theme.v1"]),"Theme mismatch")
            return UnifiedResource(d,preview,e,fs,children,nil,nil)
        }
        if type=="PARAMETERS" {try check([ResourceCategory.beauty,.reshape,.makeup,.filter].contains(d.category),"Unsupported parameter category");try fields(payload,["parameters"]);guard let p=payload["parameters"] as? [String:Any] else {throw StickerResourceError.invalid("Missing parameters")};_=try ResourceDefaults.parameters(p);try check(Set(p.keys)==d.scope && Set(fs.keys)==Set(["manifest.json","effect.json","preview.png"]),"Parameter scope mismatch");try check(d.features==features(d.category,d.scope,false),"Feature mismatch");return UnifiedResource(d,preview,e,fs,[],nil,nil)}
        try check(type=="LEGACY","Unsupported execution payload")
        var legacy=fs;legacy.removeValue(forKey:"effect.json");legacy["manifest.json"]=try JSONSerialization.data(withJSONObject:payload)
        let zip=try write(legacy)
        let sticker:StickerResourcePack?;let effect:EffectResourcePack?
        if d.category == .sticker {sticker=try StickerResourcePack.load(data:zip);effect=nil}
        else {effect=try EffectResourcePack.load(data:zip);sticker=nil;let expected=[ResourceCategory.background,.motion].contains(d.category) ? d.category.rawValue:"BEAUTY";try check(payload["type"] as? String == expected,"Category mismatch")}
        let eye=(payload["schemaVersion"] as? NSNumber)?.intValue==4
        try check(d.features==features(d.category,d.scope,eye),"Feature mismatch")
        if d.category == .makeup && d.subcategory=="EYELASH" {try check(eye,"Missing eye image")}
        return UnifiedResource(d,preview,e,fs,[],effect,sticker)
    }
    static func features(_ category:ResourceCategory,_ scope:Set<String>,_ eye:Bool)->Set<String> {
        var f=Set<String>()
        if category == .sticker {f.formUnion(["sticker.png.v1","face.landmarks478"])}
        if category == .background {f.formUnion(["background.v1","segmentation"])}
        if category == .motion {f.formUnion(["motion.v1","face.landmarks478"])}
        if category == .filter {f.insert("filter.basic.v1")}
        for key in scope {switch ResourceDefaults.category(key){case .beauty:f.insert("beauty.parameters.v1");case .filter:f.insert("filter.basic.v1");case .reshape:f.formUnion(["reshape.parameters.v1","face.landmarks478"]);case .makeup:f.formUnion(["makeup.parameters.v1","face.landmarks478"]);default:break}}
        if eye {f.insert("makeup.eyelash.texture.v1")};return f
    }
    static func required(_ files:[String:Data],_ path:String) throws -> Data {guard let d=files[path] else {throw StickerResourceError.invalid("Missing "+path)};return d}
    static func digest(_ data:Data)->String {SHA256.hash(data:data).map{String(format:"%02x",$0)}.joined()}
    static func read(_ data:Data) throws -> [String:Data] {
        try check(data.count<=33554432,"ZIP too large");let zip=try Archive(data:data,accessMode:.read);var fs=[String:Data](),folded=Set<String>(),total=0
        for entry in zip {try check(entry.type == .file && fs.count<40 && matches(entry.path,"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}$") && folded.insert(entry.path.lowercased()).inserted,"Invalid path");let limit=entry.path.hasSuffix(".json") ? 65536:entry.path.hasSuffix(".png") ? 4194304:16777216;try check(entry.uncompressedSize<=UInt64(limit),"Entry too large");var bytes=Data();let crc=try zip.extract(entry){chunk in try check(bytes.count+chunk.count<=limit,"Expanded too far");bytes.append(chunk)};total+=bytes.count;try check(total<=33554432 && crc==entry.checksum,"CRC/budget mismatch");fs[entry.path]=bytes};return fs
    }
    static func write(_ fs:[String:Data]) throws -> Data {let zip=try Archive(data:Data(),accessMode:.create);for(name,bytes) in fs {try zip.addEntry(with:name,type:.file,uncompressedSize:Int64(bytes.count),compressionMethod:.none){position,size in bytes.subdata(in:Int(position)..<min(bytes.count,Int(position)+size))}};guard let data=zip.data else {throw StickerResourceError.invalid("ZIP write failed")};return data}
    static func pngPixels(_ data:Data) throws -> Int {try check(data.count<=4194304,"PNG too large");guard let source=CGImageSourceCreateWithData(data as CFData,nil),CGImageSourceGetCount(source)==1,let p=CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [String:Any],let w=p[kCGImagePropertyPixelWidth as String] as? Int,let h=p[kCGImagePropertyPixelHeight as String] as? Int else {throw StickerResourceError.invalid("Invalid PNG")};try check(data.starts(with:[137,80,78,71,13,10,26,10]) && (1...2048).contains(w) && (1...2048).contains(h),"Invalid PNG size");return w*h}
}

public struct ResourceUserState {
    public var parameters:[String:Double],selections:[String:String?],enabled:Bool
    public init(parameters:[String:Double]=[:],selections:[String:String?]=[:],enabled:Bool=true){self.parameters=parameters;self.selections=selections;self.enabled=enabled}
}
public struct ResourceDefaults {
    public let locale:String,followHost:Bool,state:ResourceUserState,categoryOrder:[ResourceCategory]
    static let neutral:[String:Double]=["beauty.smoothing":0,"beauty.whitening":0,"beauty.rosy":0,"color.brightness":0,"color.contrast":1,"color.saturation":1,"color.sharpening":0,"reshape.slimFace":0,"reshape.bigEyes":0,"reshape.slimNose":0,"reshape.chinLength":0,"reshape.mouthSize":0,"makeup.lipstick":0,"makeup.blusher":0,"makeup.eyelashes":0]
    static func category(_ key:String)->ResourceCategory {switch key.components(separatedBy:".").first {case "beauty":return .beauty;case "color":return .filter;case "reshape":return .reshape;default:return .makeup}}
    static func parameters(_ p:[String:Any]) throws -> [String:Double] {var result=[String:Double]();for(k,v)in p {guard let n=v as? NSNumber,CFGetTypeID(n) != CFBooleanGetTypeID() else {throw StickerResourceError.invalid("Expected parameter number")};let value=n.doubleValue,low=["color.brightness","reshape.chinLength","reshape.mouthSize"].contains(k) ? -1.0:0.0,high=["color.contrast","color.saturation"].contains(k) ? 2.0:1.0;try check(neutral[k] != nil && value.isFinite && value>=low && value<=high,"Parameter out of range");result[k]=value};return result}
    public static func parse(_ data:Data) throws -> ResourceDefaults {
        try check(data.count<=65536,"Defaults too large");let o=try UnifiedResource.object(data);try UnifiedResource.fields(o,["defaultsVersion","defaultLocale","localeMode","enabled","parameters","selections","categoryOrder"]);_=try UnifiedResource.integer(o["defaultsVersion"],1,1)
        guard let locale=o["defaultLocale"] as? String,let mode=o["localeMode"] as? String,let enabled=o["enabled"] as? NSNumber,CFGetTypeID(enabled)==CFBooleanGetTypeID(),let p=o["parameters"] as? [String:Any],let selections=o["selections"] as? [String:Any],let order=o["categoryOrder"] as? [String] else {throw StickerResourceError.invalid("Invalid defaults")}
        try check(["FOLLOW_HOST","FIXED"].contains(mode) && matches(locale,"^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$") && Set(order)==Set(ResourceCategory.allCases.map{$0.rawValue}) && order.count==ResourceCategory.allCases.count,"Defaults mode/categories")
        return ResourceDefaults(locale:locale,followHost:mode=="FOLLOW_HOST",state:ResourceUserState(parameters:try parameters(p),selections:try parseSelections(selections),enabled:enabled.boolValue),categoryOrder:order.compactMap{ResourceCategory(rawValue:$0)})
    }
    static func parseSelections(_ selections:[String:Any]) throws -> [String:String?] {var result=[String:String?]();for(k,v)in selections {try check(matches(k,"^[a-z][a-z0-9_.]{0,63}$"),"Invalid slot");if v is NSNull {result[k] = .some(nil)}else if let hash=v as? String,matches(hash,"^[0-9a-f]{64}$"){result[k] = .some(hash)}else{throw StickerResourceError.invalid("Invalid pinned hash")}};return result}
    public func merge(_ saved:ResourceUserState?,host:ResourceUserState?=nil,installed:[String:ResourceDescriptor]=[:])->ResourceUserState {var r=state;for next in [saved,host].compactMap({$0}){let chosen=next.selections.values.compactMap{$0}.compactMap{installed[$0]};let scope=chosen.contains{$0.category == .theme} ? Set(Self.neutral.keys):Set(chosen.flatMap{Array($0.scope)});for key in scope {r.parameters.removeValue(forKey:key)};r.parameters.merge(next.parameters){_,new in new};if let theme=next.selections["theme"],theme != nil {r.selections.removeAll()}else if next.selections.keys.contains(where:{$0 != "theme"}){r.selections.removeValue(forKey:"theme")};r.selections.merge(next.selections){_,new in new};r.enabled=next.enabled};return r}
}
