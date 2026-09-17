// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "EngageAdsSDK",
    platforms: [.iOS(.v15), .tvOS(.v15)],
    products: [
        .library(name: "EngageAdsMobile", targets: ["EngageAdsMobile"]),
        .library(name: "EngageAdsTV", targets: ["EngageAdsTV"]),
    ],
    dependencies: [
        .package(url: "https://github.com/googleads/swift-package-manager-google-interactive-media-ads-ios.git", exact: "3.33.0"),
        .package(url: "https://github.com/googleads/swift-package-manager-google-interactive-media-ads-tvos.git", exact: "4.17.0"),
    ],
    targets: [
        .target(name: "EngageAdsCore", path: "apple/Core/Sources/EngageAdsCore"),
        .target(
            name: "EngageAdsMobile",
            dependencies: [
                "EngageAdsCore",
                .product(name: "GoogleInteractiveMediaAds", package: "swift-package-manager-google-interactive-media-ads-ios", condition: .when(platforms: [.iOS])),
            ],
            path: "apple/Sources/EngageAdsMobile",
            resources: [.process("Resources")]
        ),
        .target(
            name: "EngageAdsTV",
            dependencies: [
                "EngageAdsCore",
                .product(name: "GoogleInteractiveMediaAdsTvOS", package: "swift-package-manager-google-interactive-media-ads-tvos", condition: .when(platforms: [.tvOS])),
            ],
            path: "apple/Sources/EngageAdsTV"
        ),
        .testTarget(name: "EngageAdsMobileTests", dependencies: ["EngageAdsMobile"], path: "apple/Tests/EngageAdsMobileTests"),
        .testTarget(name: "EngageAdsTVTests", dependencies: ["EngageAdsTV"], path: "apple/Tests/EngageAdsTVTests"),
    ]
)
