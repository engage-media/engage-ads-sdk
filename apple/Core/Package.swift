// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "EngageAdsCore",
    platforms: [.macOS(.v13), .iOS(.v15), .tvOS(.v15)],
    products: [.library(name: "EngageAdsCore", targets: ["EngageAdsCore"])],
    dependencies: [.package(url: "https://github.com/swiftlang/swift-testing.git", exact: "0.9.0")],
    targets: [
        .target(name: "EngageAdsCore"),
        .testTarget(name: "EngageAdsCoreTests", dependencies: ["EngageAdsCore", .product(name: "Testing", package: "swift-testing")]),
    ]
)
