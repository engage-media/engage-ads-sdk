# SDK v2 release procedure

Android and Apple have independent versions. Android tags use `android-v<version>`; Apple Swift Package Manager tags use `<version>`. Never rewrite a published tag. The v1 Gradle project and historical releases are retained, but all v2 work is under `android/` and `apple/`.

## Verification

1. Run `npm --prefix shared ci --ignore-scripts`, shared Node tests, and `node scripts/sync-mraid.mjs --check`.
2. Run `scripts/android-gradle.sh testDebugUnitTest assemble` with JDK 17 and Android SDK 35.
3. Run `swift test --package-path apple/Core`.
4. On a full-Xcode host, build both package products and all sample apps for iOS/tvOS simulators.
5. Run the device matrix and real-server interoperability cases in `docs/VALIDATION.md`. Do not equate fixture tests with a production server integration or MRAID certification.

## Android publication

The Maven namespace is `com.github.engage-media`. Verify organization ownership in the Maven Central portal before the first upload. Publish core, mobile, and TV artifacts together at the same Android version. Consumers install only the mobile or TV facade.

Generate signed release publications into the staging repository, then create a Central bundle using `scripts/bundle-central.py`. Central requires signed artifacts, source/javadoc JARs, metadata, and checksums. Supply signing keys through CI secrets; never commit them. The release workflow produces a reviewable bundle and can upload it to Central in validation-only mode. Final publication is a separate portal action after validation succeeds.

The repository does not yet contain an agreed license file. Supply the owner's chosen license through Gradle properties `licenseName` and `licenseUrl`, or the release environment's `MAVEN_LICENSE_NAME` and `MAVEN_LICENSE_URL` variables. Do not infer an open-source license from repository visibility. Development staging can omit this metadata, but the Central bundle check rejects unspecified licensing.

Build a clean consumer against staged artifacts before publication. Preserve its dependency-resolution results with the release. Maven Central publishing access is an external prerequisite; local staging does not constitute publication.

## Apple publication

Run the Apple checks, synchronize the bundled MRAID source, and tag the reviewed commit using its Apple semantic version. Xcode consumers add this repository URL and select EngageAdsMobile or EngageAdsTV. Conditional IMA dependencies keep each product tied to its supported OS family.

No binary distribution repository is required. Keep Apple version tags independent of Android tags. Sample apps must use build-supplied endpoints; there is no default production endpoint embedded in the SDK.

## Release gates

Do not declare a production release until native builds, device behavior, real-server notifications, Maven ownership/publishing, and clean-package installation have been verified. Missing credentials, Xcode, or devices are recorded as unverified gates, never as passing checks.

Open Measurement adds the gates in [OPEN_MEASUREMENT.md](OPEN_MEASUREMENT.md). Production claims must distinguish IMA-owned video measurement from Engage's custom HTML/native integration. Custom-renderer support requires Engage's actual namespaced OM artifacts and validation; a test backend or successful parser tests cannot satisfy this gate. Repeat footprint measurements with those artifacts linked and run the applicable IAB compliance process before claiming certification.
