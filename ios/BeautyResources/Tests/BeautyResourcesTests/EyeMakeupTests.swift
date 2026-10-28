import XCTest
import Metal
@testable import BeautyResources

final class EyeMakeupTests:XCTestCase {
    func testThreeWebStylesAndThemeRequirements() throws {
        for name in ["natural","dense","cat"] {
            let url=Bundle.module.url(forResource:"lash_"+name,withExtension:"zip",subdirectory:"Fixtures/effects")!
            let pack=try EffectResourcePack.load(url:url),eye=try XCTUnwrap(EyeMakeupResource.from(pack))
            XCTAssertEqual(eye.root.count,9);XCTAssertEqual(eye.strength,0.85,accuracy:0.00001)
        }
        let url=Bundle.module.url(forResource:"eye_theme",withExtension:"zip",subdirectory:"Fixtures")!
        let theme=try BeautyThemePack.load(url:url)
        XCTAssertTrue(theme.requirements.faceTracking);XCTAssertTrue(theme.requirements.extendedBeauty);XCTAssertFalse(theme.requirements.segmentation)
    }
    func testEveryWebVertexMatchesSharedRawFrameVectors() throws {
        let url=Bundle.module.url(forResource:"eye-geometry",withExtension:"json",subdirectory:"Fixtures")!
        let cases=try JSONSerialization.jsonObject(with:Data(contentsOf:url)) as! [[String:Any]]
        let pack=try EffectResourcePack.load(url:Bundle.module.url(forResource:"lash_natural",withExtension:"zip",subdirectory:"Fixtures/effects")!)
        let source=try XCTUnwrap(EyeMakeupResource.from(pack))
        var vertices=[SIMD4<Float>](repeating:.zero,count:288)
        for c in cases {
            let p=c["pack"] as! [String:Any],root=p["root"] as! [[NSNumber]]
            let resource=EyeMakeupResource(image:source.image,root:root.map {SIMD2($0[0].floatValue,$0[1].floatValue)},length:(p["length"] as! NSNumber).floatValue,mirrorRight:p["mirrorRight"] as! Bool,strength:1)
            let points=(c["points"] as! [[NSNumber]]).map {SIMD2($0[0].floatValue,$0[1].floatValue)}
            XCTAssertTrue(EyeMakeupGeometry.fill(points:points,width:c["width"] as! Int,height:c["height"] as! Int,resource:resource,vertices:&vertices))
            let expected=c["expected"] as! [NSNumber]
            for i in vertices.indices {for component in 0..<4 {XCTAssertEqual(vertices[i][component],expected[i*4+component].floatValue,accuracy:0.00002)}}
        }
        XCTAssertEqual(cases.count,6)
    }
    /// GPU等待仅在测试中使用；SDK encode不提交或等待commandBuffer。
    func testMetalMaterialDrawPreservesVideoAlpha() throws {
        guard let device=MTLCreateSystemDefaultDevice(),let queue=device.makeCommandQueue(),let command=queue.makeCommandBuffer() else {throw XCTSkip("Metal device unavailable")}
        let pack=try EffectResourcePack.load(url:Bundle.module.url(forResource:"lash_natural",withExtension:"zip",subdirectory:"Fixtures/effects")!)
        let renderer=MetalEyeMakeupRenderer(device:device);try renderer.setPack(pack)
        let url=Bundle.module.url(forResource:"eye-geometry",withExtension:"json",subdirectory:"Fixtures")!
        let vectors=try JSONSerialization.jsonObject(with:Data(contentsOf:url)) as! [[String:Any]]
        let points=(vectors[0]["points"] as! [[NSNumber]]).map {SIMD2($0[0].floatValue,$0[1].floatValue)}
        let d=MTLTextureDescriptor.texture2DDescriptor(pixelFormat:.rgba8Unorm,width:640,height:480,mipmapped:false)
        d.storageMode = .shared;d.usage=[.shaderRead,.renderTarget]
        let input=try XCTUnwrap(device.makeTexture(descriptor:d)),output=try XCTUnwrap(device.makeTexture(descriptor:d))
        let pixels=(0..<(640*480*4)).map {UInt8([150,125,100,173][$0%4])}
        pixels.withUnsafeBytes {input.replace(region:MTLRegionMake2D(0,0,640,480),mipmapLevel:0,withBytes:$0.baseAddress!,bytesPerRow:640*4)}
        try renderer.encode(input:input,output:output,landmarks:points,commandBuffer:command)
        command.commit();command.waitUntilCompleted();XCTAssertNil(command.error)
        var actual=[UInt8](repeating:0,count:pixels.count)
        actual.withUnsafeMutableBytes {output.getBytes($0.baseAddress!,bytesPerRow:640*4,from:MTLRegionMake2D(0,0,640,480),mipmapLevel:0)}
        XCTAssertGreaterThan(actual.indices.filter {$0%4 != 3 && actual[$0] != pixels[$0]}.count,8)
        XCTAssertTrue(stride(from:3,to:actual.count,by:4).allSatisfy {actual[$0]==173})
    }
}
