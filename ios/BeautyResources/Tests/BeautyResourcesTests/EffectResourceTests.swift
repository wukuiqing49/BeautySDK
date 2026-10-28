import XCTest
@testable import BeautyResources
final class EffectResourceTests: XCTestCase {
    func testWebV3MakeupAndSignedShapes() throws {
        let url=Bundle.module.url(forResource:"beauty_v3",withExtension:"zip",subdirectory:"Fixtures/effects")!
        let pack=try EffectResourcePack.load(url:url)
        let a=pack.config["adjustments"] as! [String:NSNumber]
        XCTAssertEqual(a["lipstick"]!.doubleValue,0.45,accuracy:0.00001)
        XCTAssertEqual(a["chinLength"]!.doubleValue,-0.2,accuracy:0.00001)
        XCTAssertEqual(a["mouthSize"]!.doubleValue,0.1,accuracy:0.00001)
    }
    func testWebExportedTypesAndTimeline() throws {
        for name in ["background","motion","beauty"] {
            let url=Bundle.module.url(forResource:name,withExtension:"zip",subdirectory:"Fixtures/effects")!
            let pack=try EffectResourcePack.load(url:url)
            XCTAssertEqual(pack.type.rawValue,name.uppercased())
            if let timeline=pack.timeline { XCTAssertEqual(timeline.sample(1000).zoom,1.5,accuracy:0.00001);XCTAssertEqual(timeline.sample(4000).zoom,1) }
        }
    }
}
