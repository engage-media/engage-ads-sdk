# Engage Ads SDK v2 for Apple platforms

`EngageAdsMobile` supports iOS 15 and newer. `EngageAdsTV` supports tvOS 15 and newer and accepts instream requests only. Both products export the Foundation models in `EngageAdsCore` and use the official Google IMA binary packages pinned by the root manifest.

Run the independently testable core with:

```sh
swift test --package-path apple/Core
```

Generate the sample Xcode project with:

```sh
apple/Examples/generate.sh
```

The generated schemes are `EngageMobileExample` and `EngageTVExample`. Simulator builds require full Xcode, for example:

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -project apple/Examples/EngageAdsExamples.xcodeproj -scheme EngageMobileExample -sdk iphonesimulator build
xcodebuild -project apple/Examples/EngageAdsExamples.xcodeproj -scheme EngageTVExample -sdk appletvsimulator build
```

Both examples read `ENGAGE_AD_ENDPOINT` from the scheme environment, then fall back to the generated `EngageAdsEndpoint` Info.plist key. The checked-in defaults use the local mock server on port 8787 (`/openrtb/2.6/auto` for iOS and `/openrtb/2.6/pod` for tvOS); local-network HTTP is enabled only in the sample targets.

Use `EngageInstreamAd`, `EngageRewardedAd`, or `EngageVideoInterstitialAd` on iOS; each constructor rejects a creative loaded for another format. `EngageTVInstreamAd` is the tvOS video object. These objects accept an `AVPlayer` for IMA playhead tracking and an optional `EngageContentPlaybackController`/`EngageTVContentPlaybackController` when the host uses another content engine. The session records whether content was playing, pauses it for the break, and resumes it on completion, failure, background cancellation, or destruction.

Supply the advertising identifier through the updateable `PrivacyContext.advertisingID`. `DeviceMetadata.advertisingID` remains available for source compatibility but is ignored for requests, which lets `updatePrivacy(PrivacyContext(advertisingID: nil))` withdraw a previously allowed identifier immediately.

Native SDK builds, both sample apps' simulator/unsigned-device links, and four platform smoke tests now pass with full Xcode; see [validation results](../docs/VALIDATION.md). Actual ad-renderer and physical-device qualification remain separate gates. Setting `DEVELOPER_DIR` above also works when the system-wide `xcode-select` setting still points at Command Line Tools. IMA has no public `IMPRESSION` callback; the renderer uses its deduplicated `STARTED` event as the first visible playback transition for `burl`, while IMA alone owns VAST impression and playback trackers.

The canonical MRAID bridge must be copied byte-for-byte from `shared/mraid/mraid.js` to `apple/Sources/EngageAdsMobile/Resources/mraid.js` before packaging.
