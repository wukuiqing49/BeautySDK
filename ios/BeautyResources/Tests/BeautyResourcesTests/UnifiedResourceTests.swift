import XCTest
@testable import BeautyResources

final class UnifiedResourceTests:XCTestCase {
    private func fixture(_ name:String) throws -> Data {try Data(contentsOf:Bundle.module.resourceURL!.appendingPathComponent("Fixtures/unified/"+name))}
    func testAllSharedResourcesAndDefaultConfiguration() throws {
        let dir=Bundle.module.resourceURL!.appendingPathComponent("Fixtures/unified")
        let urls=try FileManager.default.contentsOfDirectory(at:dir,includingPropertiesForKeys:nil).filter{$0.pathExtension=="zip"}
        XCTAssertEqual(urls.count,17)
        for url in urls {let r=try UnifiedResource.load(data:Data(contentsOf:url));XCTAssertFalse(r.descriptor.name.isEmpty);XCTAssertFalse(r.preview.isEmpty)}
        let defaults=try ResourceDefaults.parse(fixture("defaults.json"));XCTAssertEqual(defaults.categoryOrder.count,8)
        let state=ResourceUserState(parameters:["beauty.smoothing":0.8],selections:["makeup.eyelash":nil],enabled:false)
        let merged=defaults.merge(state);XCTAssertEqual(merged.parameters["beauty.smoothing"],0.8);XCTAssertFalse(merged.enabled);XCTAssertTrue(merged.selections.keys.contains("makeup.eyelash"))
    }
    func testInstallLeaseAndPersistence() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:root)}
        let manager=try UnifiedResourceManager(root:root),data=try fixture("lash_natural_1.zip"),hash=try manager.install(data)
        XCTAssertEqual(try manager.install(data),hash)
        let lease=try manager.acquire(hash);XCTAssertThrowsError(try manager.uninstall(hash));lease.close();try manager.uninstall(hash)
        XCTAssertTrue(try manager.installed().isEmpty)
        let state=ResourceUserState(parameters:["makeup.lipstick":0.4],selections:["makeup.eyelash":nil],enabled:true);try manager.saveState(state)
        XCTAssertEqual(try manager.loadState()?.parameters["makeup.lipstick"],0.4)
        XCTAssertThrowsError(try manager.install(data,expectedHash:String(repeating:"0",count:64)))
    }
}
