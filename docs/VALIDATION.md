# SDK v2 acceptance matrix

Record tool versions, command, device/OS, result, and relevant diagnostic IDs for each release. Use only test campaigns for ad-server checks.

| Area | Required cases |
| --- | --- |
| Transport | OpenRTB header/body, explicit VAST parameters, no endpoint substitution, 204/empty no-fill, malformed bodies, ambiguous bids, nurl markup, timeout, cancellation |
| Privacy | Consent update snapshots, absent IDs, no generated advertising IDs, correct mobile/TV category, no automatic location lookup |
| Billing | Zero calls on preload, one on visible display/first IMA STARTED event, no repeat after duplicate callbacks, no retry after uncertain failure, no separate direct-VAST notice |
| Video | Inline VAST, wrapper, ordered pod, pre/mid/post-roll, skip, error, background/resume, content restoration, native video |
| Mobile display | Banner/interstitial HTML and images, rich media expand/resize/close, orientation, external navigation, visibility/exposure, WebView destruction |
| Native | Required assets, custom clickable views, image readiness, video readiness, tracking ownership, unavailable/unsupported asset |
| Rewards | Complete once, no reward on skip/error/dismissal, duplicate completion callbacks, invalid multi-ad reward response |
| TV | Android TV, supported Fire TV and Apple TV remote focus/skip/back, break cleanup, content focus restoration |
| Install | New Kotlin/Java, Swift iOS and Swift tvOS consumers; dependency resolved from staged/released artifacts; no SDK source edits |

Automated fixture success is a prerequisite, not a replacement for physical-device checks. MRAID standard conformance creatives must be exercised on both Android WebView and WKWebView. OM SDK certification is not claimed by this project; publish the actual renderer capability matrix.

## Environment discovered during implementation

At the start of implementation, the local host had Swift 6.3.2, a Homebrew JDK 17 and Android command-line tools, but no full Xcode installation. The native build follow-up below supersedes that tooling limitation. No physical devices, production server test campaign, or publishing credentials were supplied with this implementation request.

## Reproducible local checks

```sh
npm --prefix shared test
node scripts/sync-mraid.mjs --check
python3 -m unittest discover -s scripts/tests -v
scripts/android-gradle.sh testDebugUnitTest assemble lintDebug publishAllPublicationsToStagingRepository
python3 scripts/check-android-consumer.py
swift test --package-path apple/Core
node scripts/check-apple-consumer.mjs
swift package resolve
bash apple/Examples/generate.sh
```

The Android consumer check generates two temporary, standalone applications that depend only on the staged Maven artifacts and initialize a client using `BuildConfig.ADS_ENDPOINT`. The Swift consumer is a separate Foundation executable that uses a live local HTTP fixture server. It checks protocol headers, privacy fields, no-fill and malformed responses, native video assets, `nurl` markup, and display-time billing. It does not exercise UIKit or IMA.

The GitHub Actions workflow adds full-Xcode compilation of both native Apple products and generated sample applications. Configuration of that workflow is not evidence that it has run successfully. Syntax parsing on a Command Line Tools host cannot validate UIKit or IMA types.

For an already booted Android emulator, build the mobile sample with its default emulator endpoint, start `node contracts/mock-server/server.js --port 8787`, and run:

```sh
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario banner
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario video
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario rewarded
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario native
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario native-video
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario mraid
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario pod
```

This smoke check installs the sample, taps its format button, and verifies a single billing notice at the local fixture server. The rewarded case also requires a completion reward callback. It is narrower than the full renderer, MRAID, remote-control, and physical-device gates.

## Implementation validation — 17 September 2026

| Check | Result and scope |
| --- | --- |
| Shared JavaScript and mock-server tests | 26 passed |
| Apple Foundation core | 13 passed, including identity withdrawal, malformed responses, billing, and native asset validation |
| Independent Apple HTTP consumer | Passed against an ephemeral local mock server; includes direct VAST wrappers/pods and no-fill, without rendering |
| Android unit/Robolectric tests | 51 passed; final packaging includes the synchronized MRAID close fix |
| Android build and static checks | Debug/release libraries and both samples assemble; `lintDebug` passes |
| Android development publications | Core, mobile, and TV staged with sources/javadocs; unsigned and unpublished |
| Independent Android consumers | Both mobile and TV resolve staged Maven dependencies and compile explicit client initialization with a build-supplied endpoint |
| Android emulator runtime | Android 15 / API 35, arm64 Pixel 7 profile: banner, video, rewarded completion, native image, and embedded native video passed with one billing notice each |
| Android MRAID runtime smoke | Expand, native close, resize, native close, and final creative dismissal passed with no duplicate billing; this is not full MRAID conformance certification |
| TV sample pod runtime | Two creative completions, one break completion, and one billing notice on the same Android mobile emulator; this is not Android TV/Fire TV device qualification |
| Apple packaging preparation | Google IMA packages resolve; both sample projects generate; Swift source syntax parses; native UIKit/IMA compilation has not run locally |
| Central bundle tooling | 7 tests passed, including rejection of unsigned packages and unspecified licensing; no upload attempted |

The emulator checks caught Android ICU regex and XML-provider incompatibilities that JVM tests did not expose, plus a shared MRAID default-state close restriction. These were corrected and the affected paths passed on the emulator. Results above do not replace the remaining native Apple, physical-device, MRAID conformance, and real-server gates.

## Gates requiring external validation

- Complete native iOS/tvOS ad-renderer tests; the native build follow-up below closes compilation and basic platform-smoke gates only.
- Complete renderer cases beyond the recorded Android emulator checks, plus the physical mobile/TV device matrix and MRAID conformance creatives.
- Test an authorized real ad-server campaign, including `nurl`, `burl`, wrappers, pods, consent, and reward behavior.
- Verify Maven namespace ownership and signing/publishing access; publish and test clean Maven/SPM consumers of released versions.
- Finalize the owner's license choice and release terms URL.

Local Maven staging is unsigned development staging. No Maven Central upload or Apple release tag is created by these checks.

## Requested validation rerun — 17 September 2026

This rerun started from a clean Android build and exercised additional native runtime paths. Evidence is saved locally under `dist/validation-rerun/`; that directory is intentionally ignored by Git. The source-controlled harness is `scripts/check-android-emulator.py`.

| Automated gate | Final result |
| --- | --- |
| Android unit/Robolectric suite | 59 passed; zero failures, errors, or skipped tests |
| Swift Foundation suite | 18 passed; no Core implementation change needed |
| Shared JavaScript/fixture-server suite | 30 passed |
| Release-bundle tooling suite | 7 passed |
| Android clean build, lint, local staging | Passed; 475 tasks, 448 executed; lint warnings remain, no errors |
| Fresh Android Maven consumers | Mobile and TV independently resolve final staged artifacts and compile with build-supplied endpoints |
| Apple real-HTTP consumer, package resolution, sample project generation/reproducibility | Passed; these do not compile or exercise Apple renderers |

The four test suites total **114 passing tests**. Apple Foundation additions cover actual URLSession timeout enforcement, in-flight cancellation and stale completion, consent snapshot isolation, endpoint non-substitution, and uncertain billing failure without retry.

After the final clean build, banner, video, rewarded completion, native click tracking, native video, and the Android TV remote pod all passed again against the rebuilt packages. The TV emulator had stopped before its final APK installation; restarting it resolved that infrastructure failure. Both temporary emulators and fixture servers were stopped after validation. Local aggregate evidence is in `dist/validation-rerun/summary.json`.

The harness also accepts `--background`, `--cancel-after-display`, `--click-native`, `--remote`, `--two-part`, `--skip-reward`, `--expect-no-fill`, and `--expect-error CODE`. Select the corresponding fixture through the sample's build properties before running an option. For example, with the fixture server listening on port 8787:

```sh
scripts/android-gradle.sh :sample-mobile:assembleDebug \
  -PengageSampleVideoProtocol=vast \
  -PengageSampleVideoEndpoint=http://10.0.2.2:8787/vast/wrapper.xml
python3 scripts/check-android-emulator.py --serial emulator-5554 --scenario video \
  --direct-vast --tracker-id wrapper-1 --tracker-id linear-1
scripts/android-gradle.sh :sample-mobile:assembleDebug
```

The last command restores the default sample endpoints. Run emulator scenarios sequentially per fixture server because each run resets that server's notice log.

### Android runtime evidence

The mobile runs used Android 15 / API 35 on an arm64 Pixel 7 emulator. The TV run used a separate Android TV 14 / API 34 emulator with the television and Leanback system features, not a phone running the TV sample.

| Case | Result |
| --- | --- |
| Banner, video, rewarded completion, native image, native video | Passed; one display-time billing dispatch per winning bid |
| Native asset click | Passed after correcting notification connection reuse; one click event and one click tracker |
| MRAID one-part expand, resize, close and dismissal | Passed after correcting sample screen insets |
| MRAID two-part child WebView | Passed; child close restores the original creative, which can expand again; no duplicate billing |
| Rewarded background/foreground | Passed; no completion/reward while backgrounded, successful completion after returning |
| Rewarded cancellation and skip | Passed; no completion or reward after cancellation/skip |
| Rewarded response containing a pod | Rejected with `UNSUPPORTED_CREATIVE`; no display, billing, or reward |
| Direct VAST wrapper | Passed after fixing mock-server CORS; wrapper and inline completion trackers received, zero separate billing notices |
| Direct VAST HTTP 204 | `NoFill`; zero display, billing, or reward |
| Malformed direct VAST | `MALFORMED_RESPONSE`; zero display, billing, or reward |
| Valid VAST with missing media | `RENDER`; zero display, billing, or reward |
| OpenRTB HTTP 204, malformed JSON, competing winners | Expected no-fill/typed errors; zero display, billing, or reward |
| Android TV pod with remote input | Passed; two creative completions, one break completion, one billing dispatch, both VAST completion trackers, and remote focus restored |

The original failed attempts are retained alongside their reruns. Initial banner/MRAID/mobile-emulated pod failures were caused by Android 15 edge-to-edge placing sample controls under the status bar; both sample activities now apply system-bar/display-cutout insets. Direct-wrapper playback exposed missing CORS headers in the fixture server; the server now permits IMA's wrapper fetch and has a regression test.

Native clicking exposed a transport failure after an idle period: the click event and navigation succeeded, but the tracker reused a server-closed connection and failed with an unexpected end of stream. Notifications now use a separate connection pool that retains no idle sockets. Automatic retries remain disabled, and renderer destruction still cancels its pending work. A connection-level regression test and the emulator click check verify the fix.

Additional Android lifecycle tests verify zero billing on preload, one dispatch for duplicate display callbacks, no billing after destruction, one attempt plus diagnostics on notification failure, and reward gating. A reward now requires a displayed, successfully completed rewarded creative; early, duplicate, and failed/stale callbacks cannot grant one.

### Limits of this run

At that stage full Xcode was absent and Command Line Tools were selected. Five native Apple commands were attempted and blocked: the mobile SDK build, TV SDK build, simulator tests, mobile sample build, and TV sample build. The native build follow-up below supersedes these build blockers. Foundation tests and the HTTP consumer do not validate UIKit, WKWebView, AVPlayer, or IMA rendering. The Apple validation audit also identified missing renderer automation; installing Xcode alone does not close all Apple gates. See [Apple validation gaps](APPLE_VALIDATION_GAPS.md).

Remaining renderer qualification includes the full standard MRAID creative suite, HTML/image interstitial runtime coverage, orientation and broader foreground/visibility transitions, custom native-view binding, actual publisher content playback restoration, pre/mid/post-roll host integration, and TV skip/back navigation. The passing Android smoke cases do not establish all of these behaviors.

No physical Android phone, iPhone/iPad, Android TV, Fire TV, or Apple TV was available. No authorized real ad-server test endpoint was supplied. Maven publications remain unsigned local staging; there was no Central upload or Apple release tag. Released-package installation, publishing access, and the owner's license choice remain open release gates.

## Performance and reliability hardening — 17 September 2026

The later hardening pass supersedes the earlier test counts: **139 tests passed** (74 Android, 21 Swift Core, 37 shared JavaScript/fixture-server, 7 release tooling). Full Android assembly/lint and fresh staged consumers pass. Optimized release APKs add about 2.25 MiB per facade and are guarded by a 3 MiB CI budget.

Five release rendering cases, intentional WebView process termination, and 50 consecutive load/display/destroy cycles pass. The host survives renderer termination with a typed error. Weak references show zero retained tested ad/view objects after explicit test GC; app-process PSS still reaches about 102 MiB under WebView rendering. Detailed measurement methodology, input limits, fault containment, and remaining gates are in [PERFORMANCE_AND_RELIABILITY.md](PERFORMANCE_AND_RELIABILITY.md). Evidence is under `dist/hardening/`. Native Apple runtime, physical-device qualification and real-server acceptance remain open.

## Open Measurement validation — 2026-09-17

The later OM integration pass has **177 passing automated tests**: Android 97, Swift core 27, shared 46, and release tooling 7. Final Android builds/lint, staged consumers, and the 3 MiB release-size gate pass. The shrunk release consumer receives genuine OM session/impression/finish callbacks from IMA through the official IAB verification client; inline/wrapper/pod/direct-VAST and verification-download failure scenarios pass without changing billing ownership. The dedicated Android TV API 34 emulator also passed a two-ad pod with two OM sessions and one billing notice. All five existing renderer smoke cases, WebView termination recovery, and 50 cleanup cycles were rerun successfully.

See [OPEN_MEASUREMENT.md](OPEN_MEASUREMENT.md) and `dist/measurement/` for precise artifact hashes, scope, retained cold-run failure evidence, and measurements. Custom HTML/native session tests use backend doubles; Engage's namespaced OM runtime is absent and is not production-qualified. Apple native syntax parsing is not a successful Xcode build or runtime test. Physical-device measurement, real-server interoperability, the Engage backend, and applicable IAB compliance remain release gates.

## Native build follow-up — 2026-09-17

Full Xcode 27.0 (27A266a), iOS 27.0 (24A434), and tvOS 27.0 (24J360) simulator runtimes are now installed locally. Commands explicitly used `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; the system-wide developer-directory setting was not required to change.

| Check | Result |
| --- | --- |
| Android unit tests, assembly, and lint | Passed; 97 unit tests |
| Swift Foundation core tests and real-HTTP consumer | Passed; 27 core tests |
| Shared bridge/fixture tests and release-tooling tests | Passed; 46 shared and 7 tooling tests |
| Native iOS and tvOS SDK simulator compilation | Both passed on local Xcode 27 and hosted Xcode 16.4 |
| iOS 27 simulator smoke tests | Passed; 2 tests |
| tvOS 27 simulator smoke tests | Passed; 2 tests |
| Mobile and TV example simulator app builds | Both passed, including final app linkage |
| Mobile and TV example unsigned device app builds | Both passed; this does not mean execution on physical hardware |

This pass has **181 passing automated tests**, including four native platform smoke tests. Local logs are under `dist/xcode-validation/`; native result bundles are `apple/DerivedData/EngageAdsMobile.xcresult` and `apple/DerivedData/EngageAdsTV.xcresult`.

The first hosted run exposed an obsolete Android SDK setup package and a SwiftPM product scheme without a test action. CI now installs `platform-tools` explicitly, installs the shared npm dependencies in the release workflow, and runs the aggregate Swift package test scheme with the appropriate platform test target. Once tests reached final linkage, they exposed an unsupported tvOS IMA companion-slot class reference. The TV renderer now uses IMA's two-argument display-container initializer. CI also links both example applications for unsigned physical-device targets, since a standalone library compilation did not catch the missing symbol.

These checks establish native compilation, app linkage, and the existing configuration/focus smoke tests. They do not establish actual Apple ad playback, Apple OM verification, physical-device behavior, or the missing renderer campaign described in [Apple validation gaps](APPLE_VALIDATION_GAPS.md). See [other mediation SDKs](OTHER_MEDIATION_SDKS.md) for the separate coexistence acceptance scope.

The complete hosted workflow for source commit `04c78e6` also passed: [GitHub Actions run 35250428752](https://github.com/engage-media/engage-ads-sdk/actions/runs/35250428752). Its Android job passed staged consumer installation and the 3 MiB release-size gate; its Apple job passed core/consumer checks, native simulator tests, and both sample apps' simulator and unsigned-device builds.

Separate isolated mobile consumers built with Engage, Google Mobile Ads, and AppLovin MAX together: Android release APK packaging and iOS Release simulator final linkage passed with the exact version sets recorded in [other mediation SDKs](OTHER_MEDIATION_SDKS.md). These probes made no ad requests and do not close cross-provider runtime or TV qualification.
