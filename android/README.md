# Engage Ads SDK for Android

The Android SDK requires API 24 or newer, compiles against API 35, and uses JDK 17. The standalone build contains `core`, `mobile`, `tv`, `sample-mobile`, and `sample-tv` modules. Applications normally depend on `engage-ads-mobile` or `engage-ads-tv`; both bring in `engage-ads-core`.

## Build and test

From the repository root, run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
scripts/android-gradle.sh testDebugUnitTest assemble lintDebug
```

The build copies the canonical `shared/mraid/mraid.js` into the core AAR before compiling. Run `scripts/android-gradle.sh syncMraidBridge` to perform that copy explicitly.

## Local samples

Start the deterministic fixture server from the repository root:

```sh
node contracts/mock-server/server.js --port 8787
scripts/android-gradle.sh :sample-mobile:installDebug
```

The checked-in samples only contact the local fixture server. Android Emulator reaches the host through `10.0.2.2`; the mobile sample exposes banner, video, rewarded, native image, and native video opportunity buttons, and the TV sample exposes a pod opportunity button. Their visible status is also written to logcat under `EngageSample`.

The following Gradle properties become sample `BuildConfig` values:

| Property | Values | Default |
| --- | --- | --- |
| `engageSampleOpenRtbEndpoint` | OpenRTB 2.6 URL for the mobile banner | `http://10.0.2.2:8787/openrtb/2.6/auto` |
| `engageSampleVideoEndpoint` | OpenRTB or VAST URL for mobile video | `http://10.0.2.2:8787/openrtb/2.6/auto` |
| `engageSampleVideoProtocol` | `openrtb26` or `vast` | `openrtb26` |
| `engageSamplePodEndpoint` | OpenRTB or VAST URL for TV pods | `http://10.0.2.2:8787/openrtb/2.6/auto` |
| `engageSamplePodProtocol` | `openrtb26` or `vast` | `openrtb26` |

For example:

```sh
scripts/android-gradle.sh :sample-mobile:installDebug \
  -PengageSampleVideoProtocol=vast \
  -PengageSampleVideoEndpoint=http://10.0.2.2:8787/vast/linear.xml
```

Use the development machine's LAN address instead of `10.0.2.2` on a physical device. Cleartext HTTP is enabled only in the sample manifests for local testing.

## Client and ad lifecycle

Create one client for an immutable endpoint configuration and update privacy before the next load when host consent changes:

```kotlin
val client = EngageMobile.create(
    context,
    EngageConfiguration(
        endpoint = Endpoint.OpenRTB26("https://ads.example/openrtb", headers = mapOf("X-Publisher" to "example")),
        app = AppMetadata(context.packageName, "Example app"),
    ),
)
client.updatePrivacy(PrivacyContext(gdprApplies = true, consentString = consent))

var ad: BannerAd? = null
ad = client.createBannerAd(
    AdRequest("home-banner", AdFormat.BANNER, AdSize(320, 50)),
) { event ->
    when (event) {
        AdEvent.Loaded -> ad?.display(slot)
        AdEvent.NoFill -> showFallback()
        is AdEvent.Error -> report(event.error.code)
        else -> Unit
    }
}
ad.load()
```

Every ad is single-use and moves through `IDLE`, `LOADING`, `READY`, `DISPLAYING`, and a terminal state. Call `destroy()` when its screen or owner is disposed, and call `client.destroy()` when the client owner is disposed. The format facades are `BannerAd`, `InterstitialAd`, `RewardedAd`, `NativeAd`, and `InStreamAd`. Events are the `AdEvent` sealed types, including `Loaded`, `Displayed`, `AdCompleted`, `BreakCompleted`, `Dismissed`, `NoFill`, `RewardEarned`, `Clicked`, and `Error`.

For instream playback, pass a `ContentController` to `InStreamAd.display` so IMA pauses and resumes host content. For custom native layout, create a `NativeAdView`, add its child views, register each requested asset ID with `registerAssetView`, optionally register click targets with `registerClickView`, then call `NativeAd.bind(nativeAdView)`. A registered native video asset must be a `ViewGroup`.

IMA owns video controls and skip UI. Video display requires a visible, focused foreground window; rewarded completion is emitted only after display and full completion. TV applications should provide a stable focus target because the renderer restores prior focus after an ad break.

## Local Maven staging

Create all three release artifacts and metadata in `android/build/staging-repository`:

```sh
scripts/android-gradle.sh publishAllPublicationsToStagingRepository
```

The coordinates are `com.github.engage-media:engage-ads-core`, `com.github.engage-media:engage-ads-mobile`, and `com.github.engage-media:engage-ads-tv`. Supply optional in-memory signing with `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword`; unsigned local compilation and staging remain supported. Release automation must also supply the user-approved license as the paired `ORG_GRADLE_PROJECT_licenseName` and `ORG_GRADLE_PROJECT_licenseUrl` values. Development POMs omit the license element when that pair is absent.
