# Using Engage alongside other mediation SDKs

Engage can be integrated as a separate ad-serving path in an application that also uses Google Mobile Ads/AdMob or AppLovin MAX. This is an architectural integration option, not a completed compatibility qualification. Engage does not currently include AdMob custom-event or MAX custom-network adapters, bid into their auctions, or load their demand SDKs.

## Host responsibilities

- Select one provider for each placement opportunity. Serialize fullscreen and audible video ads across all providers, including app-open ads. Engage has no cross-SDK presentation lock.
- Give Engage a dedicated ad container. Do not let another provider render into it or manipulate its views while an Engage ad is active.
- Coordinate content pause/resume, audio, orientation, and TV focus in the host. An Engage break can restore content playback, focus, or a previous orientation; another provider must not take ownership before that break is cleaned up.
- Use one consent flow and propagate its results to every SDK before their ad requests. Engage's `PrivacyContext` governs Engage request construction; it does not configure AdMob, MAX, or every internal behavior of bundled IMA. Review merged Android permissions and each vendor's privacy requirements.
- Keep each provider's tracking and rewards separate. Engage owns its winning bid's `burl` dispatch and reward callback; IMA owns its VAST trackers and video OM session. Do not create a second OM session for the same video, reuse another SDK's private OM runtime, or register another provider's ads as friendly obstructions.
- Resolve a single compatible version of each shared dependency through Gradle or Swift Package Manager. Do not manually bundle duplicate IMA frameworks or jars. Measure the final combined application: Engage's isolated size budget does not cover other providers and their adapters.

## Integrating inside a mediation platform

To make Engage a selectable demand source inside AdMob requires a custom-event adapter implementing Google's Android/iOS mediation interfaces and a configured custom event. See [Android custom events](https://developers.google.com/admob/android/custom-events/setup) and [iOS custom events](https://developers.google.com/admob/ios/custom-events/setup).

MAX uses a custom SDK network connection and an adapter for an otherwise unsupported network. Such an integration does not automatically provide bidding or Auto-CPM support. See [MAX integration guidance](https://developers.applovin.com/en/max/getting-started/) and [network configuration](https://developers.applovin.com/en/max/max-dashboard/networks/connect-networks/).

Those adapters are separate future work. Running independent provider SDKs in one app does not merge their auctions or reporting.

## Compatibility acceptance

On September 17, 2026, an isolated Android consumer successfully built a release APK containing staged Engage mobile `2.0.0-alpha.1`, Google Mobile Ads `25.4.0`, and AppLovin MAX `13.6.4`. The tested host used Kotlin `2.3.0`, Android Gradle Plugin `8.13.2` (R8 `8.13.19`), Gradle `8.13`, and JDK 17. Dependency resolution, manifest merge, and packaging passed without duplicate-class or Kotlin-metadata errors. The Engage production dependencies were unchanged. This probe establishes build compatibility for that version set, not combined ad playback or TV qualification.

The same current Google Mobile Ads version cannot compile with the repository sample's Kotlin `2.0.21` compiler because its metadata requires Kotlin `2.3`. Use a matching host compiler and Android Gradle Plugin/R8 combination; see [Android's Kotlin compatibility table](https://developer.android.com/build/kotlin-support). MAX `13.6.4` alone also passed the repository-toolchain build. Probe projects and logs are retained locally under `dist/xcode-validation/coexistence/`.

An isolated iOS consumer also resolved official Swift packages and built a Release simulator app containing Engage mobile with IMA `3.33.0`, Google Mobile Ads `13.9.0` / UMP `3.1.0`, and AppLovinSDK `13.6.4`. Xcode 27 linked both arm64 and x86_64 simulator slices with `-ObjC`. No provider initialization or ad request was performed. This proves package resolution and app linkage, not combined runtime behavior. Neither mobile probe establishes that another provider supports Engage's TV targets.

Before advertising a supported combination, record the exact Engage, Google Mobile Ads, MAX, adapter, IMA, and build-tool versions, then validate:

1. Clean Android release/R8 builds and iOS device/simulator links with all selected dependencies.
2. Initialization with the host's consent flow, including denied consent and missing identifiers.
3. Alternating providers, competing load completions, background/foreground transitions, click return, cancellation, and destruction.
4. Single fullscreen ownership, correct audio/content/focus restoration, one billing/reward event per eligible Engage ad, and independent OM sessions for different creatives.
5. Device memory, startup time, and installed size with the actual production adapter set.

Google documents IMA's advertising-ID permissions separately from the host's own request builder: [IMA Android privacy requirements](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/android-12). Use the host CMP consistently with [Google's consent flow](https://developers.google.com/admob/android/privacy) and [MAX's consent flow](https://developers.applovin.com/en/max/android/overview/terms-and-privacy-policy-flow/).
