# Engage Ads SDK v2

Native Android and Apple SDKs for server-mediated advertising. Your application supplies an OpenRTB 2.6 endpoint or a VAST ad-tag URL. The ad server runs bidding; the SDK requests, renders, and tracks the selected ad.

This tree contains the v2 implementation and release tooling. The initial version is **2.0.0-alpha.1**. Packages are not published merely by building this repository; see [release checks](docs/RELEASE.md) and [validation status](docs/VALIDATION.md).

See [performance and reliability](docs/PERFORMANCE_AND_RELIABILITY.md) for the Android size budget, measurement commands, input limits, fault containment, and remaining device-validation gates.

See [Open Measurement](docs/OPEN_MEASUREMENT.md) for IMA versus custom-renderer session ownership, verification metadata, and the remaining partner-artifact and certification gates.

See [using other mediation SDKs](docs/OTHER_MEDIATION_SDKS.md) for host coordination, dependency checks, and the distinction between side-by-side installation and AdMob/MAX adapters.

| Platform | Formats | Minimum OS |
| --- | --- | --- |
| Android mobile | Banner, interstitial, rewarded, native image/video, in-stream video | Android 7 / API 24 |
| iOS / iPadOS | Banner, interstitial, rewarded, native image/video, in-stream video | 15 |
| Android TV / supported Android-based Fire TV | In-stream video and ad pods | API 24 |
| Apple TV | In-stream video and ad pods | tvOS 15 |

Mobile rich media uses a shared MRAID 3 bridge with native platform containers. Video uses Google IMA with Media3 on Android and AVPlayer on Apple. Capabilities are platform-specific; read [the capability matrix](docs/CAPABILITIES.md).

## Install

After the Android release is published, use Maven Central and add **one** facade dependency:

```kotlin
repositories { google(); mavenCentral() }
dependencies {
    implementation("com.github.engage-media:engage-ads-mobile:2.0.0-alpha.1")
    // TV apps use engage-ads-tv instead.
}
```

Android applications must enable core-library desugaring for IMA; the sample apps show the complete Gradle setup. For unpublished builds, publish to the local staging repository and use its Maven URL. `scripts/check-android-consumer.py` verifies both facades from that repository without project dependencies.

For Apple, add this repository through Xcode's Swift Package Manager and select **EngageAdsMobile** or **EngageAdsTV**. Until an Apple version tag is published, use a reviewed commit or the local package. IMA resolves as a platform-specific package dependency.

## Configure your endpoint

Put the endpoint in your host app's build configuration, then pass an explicit `Endpoint` into `EngageConfiguration` when creating `EngageClient`. OpenRTB uses JSON POST requests; direct VAST uses the supplied tag URL and explicit query parameters. There is no hidden Engage production endpoint, automatic protocol fallback, or debug-mode endpoint substitution.

Supply privacy signals from the host app's consent flow. Missing advertising identifiers are omitted. The SDK does not generate advertising IDs or call a location service.

Ad objects are single-use. Attach event listeners, load an opportunity, display or bind the ready ad, and destroy it when its screen is disposed. Connect content pause/resume callbacks for video breaks. Sample apps live under [Android](android/) and [Apple](apple/Examples/).

## Tracking contract

- `nurl` is the OpenRTB win notice, or supplies markup when `adm` is absent.
- `burl` fires when the winning ad **first displays**, never on receipt or preload. Duplicate callbacks do not cause duplicate dispatches. There is no automatic retry after an uncertain notification result.
- Direct VAST uses player-owned VAST tracking, without a separate billing notice.
- Rewarded video grants a single app callback only after successful completion. The host app owns reward fulfillment.

The server must return one selected winner per requested impression and resolve monetary notification macros. Read the complete [SDK/server contract](contracts/SDK_V2.md).

## Develop

```sh
npm --prefix shared ci --ignore-scripts
npm --prefix shared test
node scripts/sync-mraid.mjs --check
scripts/android-gradle.sh testDebugUnitTest assemble
swift test --package-path apple/Core
python3 -m unittest discover -s scripts/tests -v
```

The Android helper downloads checksum-verified Gradle 8.9 and uses JDK 17. Full Xcode is required to build iOS/tvOS renderers and simulator apps. The Foundation-only Apple core can be tested independently with Swift.

For local deterministic ad responses, run the [mock server](contracts/mock-server/). It serves rich-media creatives, Native 1.2 responses, VAST wrappers/pods, local media, and observable tracking endpoints without production ad traffic.

## Repository and releases

- `android/`: standalone v2 Gradle build, core, facade modules, and sample apps.
- `apple/`: Swift core, mobile/TV renderers, tests, and sample projects.
- `shared/`: canonical MRAID JavaScript and tests.
- `contracts/`: wire contract, deterministic fixtures, and mock server.
- `scripts/`: build helpers, resource synchronization, consumer checks, and signed release bundles.

Android tags use `android-v<version>`; Apple tags use `<version>`. Releases are independent. The old root Gradle project is preserved for v1 history; **use the v2 Android helper rather than root `./gradlew` for new work**. Existing applications should follow [the v2 migration guide](docs/MIGRATION_V2.md); the [v1 README](docs/V1.md) remains available.
