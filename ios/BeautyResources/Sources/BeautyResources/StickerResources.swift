import Foundation
import CoreFoundation
import CoreGraphics
import ImageIO
import ZIPFoundation

public enum StickerResourceError: Error { case invalid(String) }
func check(_ condition: Bool, _ reason: String) throws {
    if !condition { throw StickerResourceError.invalid(reason) }
}
func matches(_ text: String, _ pattern: String) -> Bool { text.range(of: pattern, options: .regularExpression) != nil }
func pngName(_ s: String) -> Bool { matches(s, "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\\.png$") && !s.contains("..") }

/// 资源锚点不引用检测器编号；宿主负责把人脸语义点转换到原始帧像素坐标。
public enum StickerAnchor: String, Codable { case eyes = "EYES", upperLip = "UPPER_LIP" }
public struct StickerLayer: Codable {
    public let id: String
    public let image: String
    public let anchor: StickerAnchor
    public let widthInAnchorSpans: Double
    public let pivot: [Double]
    public let offset: [Double]
    public let rotationDegrees: Double
}
public struct StickerManifest: Codable {
    public let schemaVersion: Int
    public let id: String
    public let name: String
    public let preview: String
    public let layers: [StickerLayer]

    /// 严格拒绝未知字段、字符串数字、布尔数字和未来协议，避免跨端静默分叉。
    public static func parse(_ data: Data) throws -> StickerManifest {
        try check(data.count <= 65536, "Manifest too large")
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw StickerResourceError.invalid("Expected object")
        }
        try check(Set(root.keys) == Set(["schemaVersion", "id", "name", "preview", "layers"]), "Unexpected manifest fields")
        func number(_ value: Any?) -> Double? {
            guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
            return n.doubleValue.isFinite ? n.doubleValue : nil
        }
        try check(number(root["schemaVersion"]) == 1, "Unsupported version")
        guard let layers = root["layers"] as? [[String: Any]] else { throw StickerResourceError.invalid("Expected layers") }
        for l in layers {
            try check(Set(l.keys) == Set(["id","image","anchor","widthInAnchorSpans","pivot","offset","rotationDegrees"]), "Unexpected layer fields")
            try check(number(l["widthInAnchorSpans"]) != nil && number(l["rotationDegrees"]) != nil, "Expected numeric placement")
            for key in ["pivot", "offset"] {
                guard let a = l[key] as? [Any] else { throw StickerResourceError.invalid("Expected coordinates") }
                try check(a.count == 2 && a.allSatisfy { number($0) != nil }, "Expected two numeric coordinates")
            }
        }
        let m = try JSONDecoder().decode(StickerManifest.self, from: data)
        let idPattern = "^[a-z][a-z0-9_]{0,47}$"
        try check(matches(m.id,idPattern) && !m.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && m.name.utf16.count <= 80 && !m.name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }), "Invalid identity")
        try check(pngName(m.preview) && (1...3).contains(m.layers.count) && Set(m.layers.map(\.id)).count == m.layers.count, "Invalid layers")
        for l in m.layers {
            try check(matches(l.id,idPattern) && pngName(l.image), "Invalid layer identity")
            try check((0.1...10).contains(l.widthInAnchorSpans) && (-180...180).contains(l.rotationDegrees), "Invalid size/rotation")
            try check((l.pivot + l.offset).allSatisfy { $0.isFinite && (-4...4).contains($0) }, "Invalid coordinates")
        }
        return m
    }
}

/// 只在加载线程执行PNG校验和解码。保留原图，逐帧合成不解码或创建UIImage。
public final class StickerResourcePack {
    public let manifest: StickerManifest
    public let images: [String: CGImage]
    public let preview: CGImage
    public let encoded: [String: Data]
    private init(manifest: StickerManifest, images: [String: CGImage], preview: CGImage, encoded: [String: Data]) {
        self.manifest=manifest; self.images=images; self.preview=preview; self.encoded=encoded
    }
    public static func load(url: URL) throws -> StickerResourcePack {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        try check(size <= 16777216, "ZIP too large")
        return try load(data: Data(contentsOf: url))
    }
    public static func load(data: Data) throws -> StickerResourcePack {
        try check(data.count <= 16777216, "ZIP too large")
        let archive = try Archive(data: data, accessMode: .read)
        var entries: [String: Data] = [:], total=0
        for entry in archive {
            try check(entry.type == .file && entries.count < 5 && entries[entry.path] == nil && (entry.path == "manifest.json" || pngName(entry.path)), "Invalid ZIP entry")
            let limit = entry.path == "manifest.json" ? 65536 : 4194304
            try check(entry.uncompressedSize <= UInt64(limit), "Entry too large")
            var bytes=Data()
            let crc = try archive.extract(entry) { chunk in
                try check(bytes.count + chunk.count <= limit, "Entry expanded too far")
                bytes.append(chunk)
            }
            try check(crc == entry.checksum && bytes.count == Int(entry.uncompressedSize), "ZIP checksum/size mismatch")
            total += bytes.count; try check(total <= 16777216, "ZIP content too large")
            entries[entry.path]=bytes
        }
        guard let description=entries.removeValue(forKey: "manifest.json") else { throw StickerResourceError.invalid("Missing manifest") }
        let m=try StickerManifest.parse(description), refs=Set(m.layers.map(\.image)+[m.preview])
        try check(Set(entries.keys) == refs, "Missing or unreferenced PNG")
        var pixels=0
        // 全部检查尺寸后才解码，遵守Android相同的总像素预算。
        for bytes in entries.values { let size=try validatePNG(bytes); pixels += size.0*size.1; try check(pixels <= 4194304, "Pixel budget exceeded") }
        var decoded: [String: CGImage]=[:]
        for (name,bytes) in entries {
            guard let source=CGImageSourceCreateWithData(bytes as CFData, nil), let image=CGImageSourceCreateImageAtIndex(source,0,nil) else { throw StickerResourceError.invalid("PNG decode failed") }
            decoded[name]=image
        }
        return StickerResourcePack(manifest:m, images:decoded, preview:decoded[m.preview]!, encoded:entries)
    }
}

func crc32(_ bytes: ArraySlice<UInt8>) -> UInt32 {
    var crc: UInt32=0xffffffff
    for b in bytes { crc ^= UInt32(b); for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0) } }
    return crc ^ 0xffffffff
}
func validatePNG(_ data: Data) throws -> (Int,Int) {
    let b=[UInt8](data)
    try check(b.count >= 33 && b.count <= 4194304 && Array(b.prefix(8)) == [137,80,78,71,13,10,26,10], "Invalid PNG")
    func uint(_ i:Int) -> Int { (0..<4).reduce(0) { ($0 << 8) | Int(b[i+$1]) } }
    var p=8, size=(0,0)
    while p+12 <= b.count {
        let n=uint(p); try check(n <= b.count-p-12, "PNG chunk overflow")
        let type=String(bytes:b[(p+4)..<(p+8)],encoding:.ascii) ?? ""
        try check(!["acTL","fcTL","fdAT"].contains(type) && crc32(b[(p+4)..<(p+8+n)]) == UInt32(uint(p+8+n)), "APNG or PNG checksum error")
        if p == 8 { try check(type == "IHDR" && n == 13, "Invalid PNG header"); size=(uint(p+8),uint(p+12)); try check((1...2048).contains(size.0) && (1...2048).contains(size.1), "Invalid dimensions") }
        p += n+12
        if type == "IEND" { try check(n == 0 && p == b.count, "Invalid PNG end"); return size }
    }
    throw StickerResourceError.invalid("Missing PNG end")
}

/// 锚点姿态使用原始帧像素坐标；角度为atan2(dy,dx)，非UIKit点或屏幕尺寸。
public struct StickerPose {
    public let center: CGPoint
    public let span: Double
    public let angle: Double
    public init(center: CGPoint, span: Double, angle: Double) { self.center=center; self.span=span; self.angle=angle }
    public static func from(left: CGPoint, right: CGPoint, center: CGPoint? = nil) -> StickerPose? {
        let dx=Double(right.x-left.x), dy=Double(right.y-left.y), span=hypot(dx,dy)
        guard span.isFinite && span >= 2 else { return nil }
        return StickerPose(center:center ?? CGPoint(x:(left.x+right.x)/2,y:(left.y+right.y)/2),span:span,angle:atan2(dy,dx))
    }
}
public enum StickerGeometry {
    /// 返回左上、右上、左下、右下四点，与Android逆采样公式对应。
    public static func corners(layer: StickerLayer, imageSize: CGSize, pose: StickerPose, mirrored: Bool) -> [CGPoint] {
        let hand=mirrored ? -1.0 : 1.0, c=cos(pose.angle), s=sin(pose.angle)
        let cx=Double(pose.center.x)+(c*layer.offset[0]-s*layer.offset[1]*hand)*pose.span
        let cy=Double(pose.center.y)+(s*layer.offset[0]+c*layer.offset[1]*hand)*pose.span
        let angle=pose.angle+layer.rotationDegrees * .pi/180*hand, a=cos(angle), b=sin(angle)
        let w=pose.span*layer.widthInAnchorSpans, h=w*Double(imageSize.height/imageSize.width)
        return [(0.0,0.0),(1.0,0.0),(0.0,1.0),(1.0,1.0)].map { u,v in
            let x=(u-layer.pivot[0])*w, y=(v-layer.pivot[1])*h*hand
            return CGPoint(x:cx+a*x-b*y,y:cy+b*x+a*y)
        }
    }
}
