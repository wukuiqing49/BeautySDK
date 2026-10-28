import XCTest
@testable import BeautyResources
final class ThemeResourceTests:XCTestCase {
    func testCompleteThemeAndCapabilities() throws {
        let url=Bundle.module.url(forResource:"full_theme",withExtension:"zip",subdirectory:"Fixtures")!
        let theme=try BeautyThemePack.load(url:url)
        XCTAssertEqual(theme.id,"full_theme");XCTAssertNotNil(theme.sticker);XCTAssertEqual(theme.effects.count,3)
        XCTAssertTrue(theme.requirements.faceTracking);XCTAssertTrue(theme.requirements.segmentation)
        XCTAssertThrowsError(try theme.validateCapabilities(ThemeCapabilities()))
        XCTAssertNoThrow(try theme.validateCapabilities(ThemeCapabilities(faceTracking:true,segmentation:true,extendedBeauty:true)))
    }
}
