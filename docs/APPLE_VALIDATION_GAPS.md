# Apple validation gaps

This note separates Apple build checks from acceptance coverage that has not been implemented. Installing Xcode is necessary, but it does not by itself close the renderer release gate.

## Existing build and smoke checks

The hosted Xcode 16.4 run for commit `d377337` compiled both native package products successfully on September 17, 2026. It then exposed a test-runner configuration error: individual product schemes had no test action. The runner now selects the aggregate `EngageAdsSDK-Package` scheme and the relevant platform test target. See [validation status](VALIDATION.md) for the subsequent run results.

- The workflow builds the `EngageAdsMobile` and `EngageAdsTV` package schemes for generic simulators (`.github/workflows/sdk-v2.yml`, Apple job).
- The workflow regenerates and builds `EngageMobileExample` and `EngageTVExample` (`apple/Examples/project.yml` and `apple/Examples/generate.sh`).
- `scripts/check-apple-simulators.py` runs the package test schemes on available iOS and tvOS simulators.
- The current platform tests are intentionally small compile/smoke checks: mobile configuration and the content-controller protocol in `apple/Tests/EngageAdsMobileTests/PlatformSmokeTests.swift`; TV configuration and focus eligibility in `apple/Tests/EngageAdsTVTests/PlatformSmokeTests.swift`.

These checks establish compilation, linkage, basic package test execution, and sample buildability once they pass on a full-Xcode host. They do not launch a sample or validate ad rendering.

## Missing renderer harness and sample coverage

The iOS example currently exposes banner and OpenRTB instream actions (`apple/Examples/MobileDemo/AppDelegate.swift`). The tvOS example exposes one OpenRTB instream-pod action (`apple/Examples/TVDemo/AppDelegate.swift`). There is no Apple sample action or automated runtime harness for:

- rewarded completion, skip, playback error, duplicate completion, or invalid multi-ad reward responses;
- native image registration/readiness/tracking or embedded native video readiness;
- HTML or video interstitial presentation and cleanup;
- direct-VAST endpoint playback, wrapper resolution, ordered pods, or pre/mid/post-roll behavior;
- WKWebView MRAID expand, two-part expand, resize, close, orientation, navigation, exposure, backgrounding, or destruction;
- IMA `STARTED` billing de-duplication, media error, content pause/restoration, or background cancellation;
- Apple TV remote skip/back behavior, break cleanup, and content-focus restoration.

No Apple counterpart to `scripts/check-android-emulator.py` starts the fixture server, launches the sample, selects a scenario, observes events, and verifies notice counts. These cases remain uncovered even after the existing Xcode commands pass.

## Foundation coverage

`apple/Core/Tests/EngageAdsCoreTests/CoreTests.swift` covers request/response validation, privacy fields and identity withdrawal, direct-VAST URL construction, native payload validation, notice separation, diagnostics, loaded-creative lifecycle, and actual `URLSession` request timeout behavior. It also deterministically covers:

- cancellation of an in-flight load and rejection of a non-cooperative stale completion;
- privacy/consent snapshot isolation while a request is pending;
- one-attempt failed `burl` delivery with a sanitized diagnostic and no retry;
- exact configured OpenRTB and VAST endpoint use without substitution.

`integration/AppleCoreConsumer` uses a real local HTTP fixture server. Its native-video check parses the Native 1.2 VAST asset; its wrapper/pod checks fetch and normalize those VAST documents; its billing check calls the Foundation loaded-creative display transition. It does not invoke UIKit, WebKit, AVPlayer, or IMA, resolve a wrapper, play a pod, or establish visible playback.

## External release gates

After full-Xcode builds pass, release acceptance still requires the missing renderer campaign above, WKWebView MRAID conformance creatives, supported physical iPhone/iPad and Apple TV checks, a clean external iOS/tvOS Swift Package consumer, and an authorized real-server campaign for consent, `nurl`, `burl`, wrappers, pods, and rewards. Results must record the Xcode version, simulator/device model and OS, commands, scenario, events, notice counts, and relevant diagnostic IDs.
