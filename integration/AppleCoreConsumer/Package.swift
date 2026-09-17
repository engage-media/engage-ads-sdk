// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "AppleCoreConsumer",
    platforms: [.macOS(.v13)],
    dependencies: [.package(path: "../../apple/Core")],
    targets: [.executableTarget(name: "AppleCoreConsumer", dependencies: [.product(name: "EngageAdsCore", package: "Core")])]
)
