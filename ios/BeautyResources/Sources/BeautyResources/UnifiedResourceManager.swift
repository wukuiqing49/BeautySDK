import Foundation
import CoreFoundation

/// 下载由宿主HTTP客户端提供；实现必须流式限量、支持取消并只接受HTTPS。
public protocol UnifiedResourceTransport {func read(url:URL,maxBytes:Int,cancelled:()->Bool) throws -> Data}
public final class UnifiedResourceLease {
    public let resource:UnifiedResource,hash:String
    private var release:(()->Void)?
    init(_ hash:String,_ resource:UnifiedResource,_ release:@escaping()->Void){self.hash=hash;self.resource=resource;self.release=release}
    public func close(){release?();release=nil}
    deinit{close()}
}
/// 调用者在后台串行队列使用。安装内容按摘要固定，不覆盖正在使用的版本。
public final class UnifiedResourceManager {
    private let root:URL,transport:UnifiedResourceTransport?
    private var cache=[String:UnifiedResource](),leases=[String:Int]()
    private let lock=NSRecursiveLock()
    public init(root:URL,transport:UnifiedResourceTransport?=nil) throws {self.root=root;self.transport=transport;for name in ["installed","staging","catalogs"] {try FileManager.default.createDirectory(at:root.appendingPathComponent(name),withIntermediateDirectories:true)}}
    public func install(_ data:Data,expectedHash:String?=nil,cancelled:()->Bool={false}) throws -> String {
        lock.lock();defer{lock.unlock()};try check(!cancelled(),"Cancelled")
        let hash=UnifiedResource.digest(data);try check(expectedHash==nil || expectedHash==hash,"Digest mismatch");let resource=try UnifiedResource.load(data:data)
        let target=root.appendingPathComponent("installed/"+hash,isDirectory:true)
        if FileManager.default.fileExists(atPath:target.path){try check(try Data(contentsOf:target.appendingPathComponent("package.zip"))==data,"Corrupt installed resource");return hash}
        for existing in try installed() where existing.descriptor.id==resource.descriptor.id && existing.descriptor.revision==resource.descriptor.revision {try check(existing.hash==hash,"Revision conflict")}
        let temp=root.appendingPathComponent("staging/"+UUID().uuidString,isDirectory:true);try FileManager.default.createDirectory(at:temp,withIntermediateDirectories:true)
        defer{try? FileManager.default.removeItem(at:temp)}
        for(name,bytes)in resource.files {try check(!cancelled(),"Cancelled");try bytes.write(to:temp.appendingPathComponent(name),options:.atomic)}
        try data.write(to:temp.appendingPathComponent("package.zip"),options:.atomic);try check(!cancelled(),"Cancelled");try FileManager.default.moveItem(at:temp,to:target);return hash
    }
    public func installed() throws -> [(hash:String,descriptor:ResourceDescriptor)] {
        lock.lock();defer{lock.unlock()}
        return try FileManager.default.contentsOfDirectory(at:root.appendingPathComponent("installed"),includingPropertiesForKeys:nil).compactMap {dir in
            guard matches(dir.lastPathComponent,"^[0-9a-f]{64}$"),let data=try? Data(contentsOf:dir.appendingPathComponent("manifest.json")),let object=try? UnifiedResource.object(data),let d=try? UnifiedResource.parseDescriptor(object) else {return nil};return (dir.lastPathComponent,d)
        }
    }
    public func acquire(_ hash:String) throws -> UnifiedResourceLease {
        lock.lock();defer{lock.unlock()};try check(matches(hash,"^[0-9a-f]{64}$"),"Invalid hash")
        let resource:UnifiedResource
        if let current=cache[hash]{resource=current}else{let bytes=try Data(contentsOf:root.appendingPathComponent("installed/"+hash+"/package.zip"));try check(UnifiedResource.digest(bytes)==hash,"Corrupt install");resource=try UnifiedResource.load(data:bytes);cache[hash]=resource}
        leases[hash,default:0]+=1
        return UnifiedResourceLease(hash,resource){[weak self]in guard let self=self else{return};self.lock.lock();defer{self.lock.unlock()};let remaining=max(0,(self.leases[hash] ?? 1)-1);if remaining==0 {self.leases.removeValue(forKey:hash);self.cache.removeValue(forKey:hash)}else{self.leases[hash]=remaining}}
    }
    /// 编辑套装前固定其全部子项，避免只换睫毛时丢掉其余搭配。
    public func materializeTheme(_ hash:String) throws -> [(hash:String,descriptor:ResourceDescriptor)] {
        let lease=try acquire(hash);defer{lease.close()};try check(lease.resource.descriptor.category == .theme,"Expected theme")
        guard let payload=lease.resource.effect["payload"] as? [String:Any],let refs=payload["resources"] as? [[String:Any]] else {throw StickerResourceError.invalid("Invalid theme references")}
        return try refs.map {ref in
            guard let name=ref["file"] as? String,let bytes=lease.resource.files[name] else {throw StickerResourceError.invalid("Missing child archive")}
            let hash=try install(bytes),child=try UnifiedResource.load(data:bytes)
            return (hash,child.descriptor)
        }
    }
    /// 封面独立缓存；只在资源面板后台任务调用。
    public func preview(entry:[String:Any]) throws -> Data {
        guard let address=entry["previewUrl"] as? String,let url=URL(string:address),let hash=entry["sha256"] as? String else {throw StickerResourceError.invalid("Invalid preview entry")}
        try validateURL(url);try check(matches(hash,"^[0-9a-f]{64}$"),"Invalid preview hash")
        let path=root.appendingPathComponent("catalogs/"+hash+".png")
        do {guard let transport=transport else{throw StickerResourceError.invalid("Host transport required")};let data=try transport.read(url:url,maxBytes:4194304,cancelled:{false});_=try UnifiedResource.pngPixels(data);try data.write(to:path,options:.atomic);return data}
        catch{if FileManager.default.fileExists(atPath:path.path){let data=try Data(contentsOf:path);_=try UnifiedResource.pngPixels(data);return data};throw error}
    }
    public func uninstall(_ hash:String) throws {lock.lock();defer{lock.unlock()};try check(matches(hash,"^[0-9a-f]{64}$") && leases[hash,default:0]==0,"Resource in use");let path=root.appendingPathComponent("installed/"+hash);if FileManager.default.fileExists(atPath:path.path){try FileManager.default.removeItem(at:path)};cache.removeValue(forKey:hash)}
    public func catalog(url:URL) throws -> [String:Any] {
        try validateURL(url);let file=root.appendingPathComponent("catalogs/"+UnifiedResource.digest(Data(url.absoluteString.utf8))+".json")
        do {guard let transport=transport else{throw StickerResourceError.invalid("Host transport required")};let data=try transport.read(url:url,maxBytes:1048576,cancelled:{false});let object=try Self.parseCatalog(data);try data.write(to:file,options:.atomic);return object}
        catch{if FileManager.default.fileExists(atPath:file.path){return try Self.parseCatalog(Data(contentsOf:file))};throw error}
    }
    public func download(entry:[String:Any],cancelled:()->Bool={false}) throws -> String {
        try UnifiedResource.fields(entry,["manifest","url","previewUrl","bytes","sha256"])
        guard let address=entry["url"] as? String,let url=URL(string:address),let hash=entry["sha256"] as? String,let manifest=entry["manifest"] as? [String:Any],let transport=transport else {throw StickerResourceError.invalid("Invalid download/host transport")}
        try validateURL(url);let size=try UnifiedResource.integer(entry["bytes"],1,33554432),data=try transport.read(url:url,maxBytes:size,cancelled:cancelled)
        try check(data.count==size && UnifiedResource.digest(data)==hash,"Download digest/size mismatch")
        let resource=try UnifiedResource.load(data:data),declared=try UnifiedResource.parseDescriptor(manifest)
        try check(resource.descriptor.id==declared.id && resource.descriptor.revision==declared.revision && resource.descriptor.category==declared.category && resource.descriptor.slot==declared.slot && NSDictionary(dictionary:resource.files["manifest.json"].flatMap{try? UnifiedResource.object($0)} ?? [:]).isEqual(to:manifest),"Catalog mismatch")
        return try install(data,expectedHash:hash,cancelled:cancelled)
    }
    public func saveState(_ state:ResourceUserState) throws {
        _=try ResourceDefaults.parameters(state.parameters.mapValues{$0 as Any})
        let selections=state.selections.mapValues{v -> Any in v.map{$0 as Any} ?? NSNull()};_=try ResourceDefaults.parseSelections(selections)
        let o:[String:Any]=["stateVersion":1,"parameters":state.parameters,"selections":selections,"enabled":state.enabled]
        try JSONSerialization.data(withJSONObject:o).write(to:root.appendingPathComponent("user-state.json"),options:.atomic)
    }
    public func loadState() throws -> ResourceUserState? {
        let path=root.appendingPathComponent("user-state.json");if !FileManager.default.fileExists(atPath:path.path){return nil};let data=try Data(contentsOf:path);try check(data.count<=65536,"State too large");let o=try UnifiedResource.object(data);try UnifiedResource.fields(o,["stateVersion","parameters","selections","enabled"]);_=try UnifiedResource.integer(o["stateVersion"],1,1)
        guard let p=o["parameters"] as? [String:Any],let s=o["selections"] as? [String:Any],let enabled=o["enabled"] as? NSNumber,CFGetTypeID(enabled)==CFBooleanGetTypeID() else {throw StickerResourceError.invalid("Invalid state")}
        return ResourceUserState(parameters:try ResourceDefaults.parameters(p),selections:try ResourceDefaults.parseSelections(s),enabled:enabled.boolValue)
    }
    public static func parseCatalog(_ data:Data) throws -> [String:Any] {
        try check(data.count<=1048576,"Catalog too large");let o=try UnifiedResource.object(data);try UnifiedResource.fields(o,["resourceCatalogVersion","resources"]);_=try UnifiedResource.integer(o["resourceCatalogVersion"],1,1)
        guard let entries=o["resources"] as? [[String:Any]] else {throw StickerResourceError.invalid("Invalid catalog")};try check(entries.count<=500,"Catalog count");var identities=Set<String>()
        for e in entries {try UnifiedResource.fields(e,["manifest","url","previewUrl","bytes","sha256"]);guard let m=e["manifest"] as? [String:Any],let hash=e["sha256"] as? String,let u=e["url"] as? String,let p=e["previewUrl"] as? String,let url=URL(string:u),let preview=URL(string:p) else {throw StickerResourceError.invalid("Invalid entry")};let d=try UnifiedResource.parseDescriptor(m);try validateURL(url);try validateURL(preview);_=try UnifiedResource.integer(e["bytes"],1,33554432);try check(matches(hash,"^[0-9a-f]{64}$") && identities.insert(d.id+"/"+String(d.revision)).inserted,"Invalid hash/duplicate entry")};return o
    }
    private static func validateURL(_ url:URL) throws {try check(url.scheme=="https" && url.host != nil && url.user==nil && url.password==nil && url.fragment==nil,"HTTPS required")}
    private func validateURL(_ url:URL) throws {try Self.validateURL(url)}
}
