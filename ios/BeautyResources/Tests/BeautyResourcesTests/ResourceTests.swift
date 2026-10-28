import XCTest
import Metal
@testable import BeautyResources
final class ResourceTests: XCTestCase {
    func testMetalCompositionOnOpaqueFrame() throws {
        guard let device=MTLCreateSystemDefaultDevice(),let queue=device.makeCommandQueue(),let command=queue.makeCommandBuffer() else { throw XCTSkip("Metal unavailable") }
        let d=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:256,height:256,mipmapped:false)
        d.storageMode = .shared;d.usage=[.renderTarget,.shaderRead]
        let input=try XCTUnwrap(device.makeTexture(descriptor:d)),output=try XCTUnwrap(device.makeTexture(descriptor:d))
        let bytes=[UInt8](repeating:255,count:256*256*4)
        bytes.withUnsafeBytes { input.replace(region:MTLRegionMake2D(0,0,256,256),mipmapLevel:0,withBytes:$0.baseAddress!,bytesPerRow:1024) }
        let url=Bundle.module.url(forResource:"round_glasses",withExtension:"zip",subdirectory:"Fixtures")!
        let renderer=MetalStickerRenderer(device:device)
        try renderer.setPack(StickerResourcePack.load(url:url))
        try renderer.encode(input:input,output:output,commandBuffer:command,poses:[.eyes:StickerPose(center:CGPoint(x:128,y:128),span:80,angle:0)],mirrored:false)
        command.commit();command.waitUntilCompleted();XCTAssertEqual(command.status,.completed)
        var actual=bytes
        actual.withUnsafeMutableBytes { output.getBytes($0.baseAddress!,bytesPerRow:1024,from:MTLRegionMake2D(0,0,256,256),mipmapLevel:0) }
        XCTAssertTrue(actual.enumerated().contains { $0.offset % 4 != 3 && $0.element < 200 })
        XCTAssertTrue(stride(from:3,to:actual.count,by:4).allSatisfy { actual[$0] == 255 })
        try renderer.setPack(nil)
    }
    func testWebExportsLoadAndRejectCorruption() throws {
        for name in ["round_glasses","heart_glasses","curled_moustache","blue_top_hat","gold_crown","cat_ears"] {
            let url=Bundle.module.url(forResource:name,withExtension:"zip",subdirectory:"Fixtures")!
            let data=try Data(contentsOf:url), pack=try StickerResourcePack.load(data:data)
            XCTAssertEqual(pack.manifest.id,name)
            XCTAssertEqual(pack.manifest.layers[0].offset,[0.2,-0.3])
            XCTAssertEqual(pack.manifest.layers[0].rotationDegrees,15)
            XCTAssertFalse(pack.images.isEmpty)
            var broken=data; broken[50] ^= 1
            XCTAssertThrowsError(try StickerResourcePack.load(data:broken))
        }
    }
    func testSharedGeometry() throws {
        let url=Bundle.module.url(forResource:"geometry-vectors",withExtension:"json",subdirectory:"Fixtures")!
        let vectors=try JSONSerialization.jsonObject(with:Data(contentsOf:url)) as! [[String:Any]]
        for v in vectors {
            let layer=try JSONDecoder().decode(StickerLayer.self,from:JSONSerialization.data(withJSONObject:v["layer"]!))
            let size=v["imageSize"] as! [Double], p=v["pose"] as! [String:Double]
            let pose=StickerPose(center:CGPoint(x:p["x"]!,y:p["y"]!),span:p["span"]!,angle:p["angle"]!)
            let points=StickerGeometry.corners(layer:layer,imageSize:CGSize(width:size[0],height:size[1]),pose:pose,mirrored:v["mirrored"] as! Bool)
            let expected=v["expected"] as! [[Double]]
            for i in 0..<4 { XCTAssertEqual(Double(points[i].x),expected[i][0],accuracy:1e-7);XCTAssertEqual(Double(points[i].y),expected[i][1],accuracy:1e-7) }
        }
    }
    func testUnknownVersionAndFieldsRejected() throws {
        let url=Bundle.module.url(forResource:"round_glasses",withExtension:"zip",subdirectory:"Fixtures")!
        let pack=try StickerResourcePack.load(url:url)
        var m=try JSONSerialization.jsonObject(with:JSONEncoder().encode(pack.manifest)) as! [String:Any]
        m["schemaVersion"]=2
        XCTAssertThrowsError(try StickerManifest.parse(JSONSerialization.data(withJSONObject:m)))
        m["schemaVersion"]=1;m["unknown"]=true
        XCTAssertThrowsError(try StickerManifest.parse(JSONSerialization.data(withJSONObject:m)))
    }
}
