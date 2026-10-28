// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "BeautyResources", platforms: [.iOS(.v13), .macOS(.v11)],
    products: [.library(name: "BeautyResources", targets: ["BeautyResources"])],
    dependencies: [.package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.19")],
    targets: [.target(name: "BeautyResources", dependencies: ["ZIPFoundation"]),
              .testTarget(name: "BeautyResourcesTests", dependencies: ["BeautyResources"], resources: [.copy("Fixtures")])])
