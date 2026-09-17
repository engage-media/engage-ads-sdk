# Open Measurement integration

Open Measurement is a production requirement for supported ad inventory. It is separate from Engage billing, ad impression callbacks, viewability thresholds, and rewards. An OM session or verification script failure must not prevent content restoration or create another billing attempt.

## Implementation plan and ownership

1. Freeze session ownership and the wire profile below. The orchestrator owns this document and the shared SDK contract.
2. Android and Apple implementations own their renderer hooks, safe session lifecycle, capability reporting, friendly obstructions, and platform tests.
3. Shared fixtures own verification metadata, VAST inline/wrapper/pod examples, a local verification collector, and conformance scenarios.
4. Integrate staged consumers, test real IMA verification where tools permit, and remeasure release artifact size. Record unavailable checks explicitly.
5. Complete Engage's IAB partner onboarding, integrate its namespaced native SDK builds, run the physical-device and verification-provider matrix, and complete the applicable compliance process before claiming certified custom-renderer measurement.

## Session ownership

| Rendering path | Session owner | Integration boundary |
| --- | --- | --- |
| IMA video, including rewarded and native embedded VAST | IMA | Preserve VAST verification resources; register only eligible playback controls as friendly obstructions; never create a second Engage session for that video |
| HTML/MRAID banner or display interstitial | Engage's namespaced OM backend | Bind the actual WebView and ad view; creative-side OMID JavaScript runs within a real OM HTML session |
| Native image/text | Engage's namespaced OM backend | Pass bounded verification resources, bind the rendered native view, publish loaded/impression once, finish on destruction/failure |
| TV video | IMA where supported by the pinned platform build | Platform-specific evidence is required; iOS or mobile Android results do not qualify tvOS or physical TV devices |

Custom renderer session hooks are not an OM runtime. Without Engage's namespaced backend, those paths must report measurement unavailable and must not advertise OMID capability. Test doubles verify SDK lifecycle behavior only. They do not establish third-party viewability, measurement correctness, or certification.

## Protocol profile

- Advertise OMID (`api: [7]`, alongside other supported frameworks) only for the relevant renderer with an available implementation. Native display uses the outer `imp.native.api`, not a field inside its serialized Native 1.2 request. A request containing a native video asset omits custom native OM flags and event 555, because IMA will own the delivered video's session. Its embedded video object may advertise the IMA capability without claiming measurement for the surrounding image/text view.
- `source.ext.omidpn` and `omidpv`, when emitted, must identify the actual integration partner and integration version. Never infer Google's partner identity from an Engage version or invent an Engage namespace.
- Responses may require frameworks through legacy `bid.api` or OpenRTB 2.6 `bid.apis`. Both are checked against the actual renderer path. `apis` is bounded to 64 nonnegative integer entries; invalid fields are malformed responses and unavailable frameworks are unsupported creatives.
- VAST 4.2 passes `<AdVerifications>` through to IMA intact, including wrappers and ordered pods. IMA owns script loading, OM session events, and VAST verification-error trackers.
- Native 1.2 uses `eventtrackers` with `event: 555`, `method: 2`, script `url`, and optional `ext.vendorKey` / `ext.verification_parameters`. Its request advertises `{event: 555, methods: [2]}` only when the native measurement backend is available. Generic JavaScript trackers and `jstracker` are not an alternative OM implementation.
- Native verification metadata retains at most the first 32 valid resources, 2,048 UTF-8 bytes per URL, 256 per vendor key, and 4,096 per parameters string. URLs must be HTTPS, with HTTP loopback fixtures permitted for tests. Malformed optional measurement metadata is skipped with a diagnostic; no partial script execution or raw parameter logging. The existing structural limit of 128 total native event trackers still applies to the response as a whole.

Billing remains one `burl` attempt on first display per winning bid. Pods may have multiple IMA measurement sessions; that does not authorize multiple billings. Direct VAST has no separate Engage billing notice. Reward completion remains independent of verification success.

## Reliability and footprint

Create no standalone display sessions at SDK startup or merely on auction preload. Bind measurement to the renderer lifecycle, deduplicate loaded/impression/finish events, clear obstruction and view references during cleanup, and isolate ordinary backend errors. Do not install a global exception handler or claim recovery from out-of-memory, native crashes, or all failures inside third-party code.

For HTML, prepare/inject the licensed OM service before creative JavaScript runs, then create/start the native session after the page finishes loading. The prepared service plus HTML is bounded to 4 MiB. Preparation failure disables that session and reports a diagnostic while the ad continues rendering. The tracked view is the actual WebView; eligible controls are separate friendly obstructions. While custom measurement is active, MRAID two-part expansion returns an unsupported-operation error until child-document measurement is implemented and qualified; unmeasured expansion retains its existing behavior.

Measure the final linked/shrunk consumer with measurement enabled. The existing Android 3 MiB growth budget continues to apply; the current size result cannot predict the cost of a partner SDK that has not been linked. Verification JavaScript and native view scanning also require device CPU/memory checks during playback and after cleanup.

## External prerequisites and qualification

Engage's partner namespace, Android/Apple SDK artifacts, and corresponding OM service JavaScript must come from IAB's distribution process. These are not present in the repository at the start of this work. Google IMA's internal namespaced OM binaries are not a replacement for Engage's custom-renderer integration.

The intended production packaging keeps the namespaced custom backend behind the mobile facade so publishers still install one dependency. TV video reuses IMA and should not pull in an additional display-measurement runtime. The current backend interface is an integration boundary, not a requirement for each publisher to write an OM adapter; the Engage-owned adapter and automatic wiring remain dependent on those artifacts.

Before release, verify real OM events with an IAB/measurement-provider validation creative, obstruction geometry, background/foreground state, WebView termination, rapid destroy/rebind, MRAID resizing/expansion, native custom views, pod boundaries, and measurement failure. Exercise Android mobile, iOS/iPadOS, Android TV, supported Fire TV, and tvOS. Complete native Apple builds with full Xcode and retain the actual qualification results.

## Verification results — 2026-09-17

The implemented default path is IMA-owned video measurement. Custom HTML/static-native session plumbing is implemented and tested using backend doubles, but Engage's actual namespaced backend is still unavailable. No custom-renderer measurement or certification claim follows from those tests.

| Check | Result |
| --- | --- |
| Android automated tests | 97 passed, including 21 dedicated Open Measurement tests |
| Swift Foundation tests | 27 passed |
| Shared bridge/protocol/verification tests | 46 passed; OM VAST fixtures also validate against IAB's VAST 4.2 schema |
| Release-tooling tests | 7 passed |
| Fresh staged Android mobile/TV consumers | Both compile with a custom endpoint and the public measurement-backend API |
| Swift real-HTTP consumer | Passed, including verification-markup preservation and honest default capability reporting |
| Android real OM verification | All six scenarios passed on the final shrunk release consumers after clearing probe app data |
| Apple native renderer build/runtime | Native SDK compilation passed on hosted Xcode 16.4; actual ad playback and measurement remain unverified (see [validation status](VALIDATION.md)) |
| Engage custom OM runtime | Not verified: namespaced SDK artifacts and adapter implementation remain outstanding |

The six Android scenarios are OpenRTB inline, wrapper, pod, failed verification-resource download, direct VAST inline, and the TV facade's pod on the same mobile emulator. Positive cases received actual `sessionStart`, `impression`, and `sessionFinish` callbacks through the official IAB verification client. Wrappers reached both verification resources; pods produced two distinct ad-session IDs. OpenRTB still billed once per bid, direct VAST billed zero times through Engage, and preload sent no measurement or billing. The deliberately failing verification download did not interrupt playback. This does not qualify physical Android TV/Fire TV devices or viewability geometry.

An initial cold run produced VAST verification-not-executed reason 3 (resource-load error) before the local verification script was requested. Its exact cause is unproven. A repeat with probe app data cleared and the final six-case run passed; the original failure evidence remains in `dist/measurement/first-runtime/`. IMA's own runtime still uses external Google resources even when ads and verification resources are local fixtures.

Final Android compressed APK growth is **2,373,889 bytes (mobile)** and **2,373,293 bytes (TV)**, about **2.26 MiB**, below the 3 MiB CI budget. These figures include IMA, SDK code, and the consumer probe; they exclude the missing custom namespaced OM runtime. The official IAB verification client is a test dependency and is not shipped in the core AAR. Final measured core AAR SHA-256: `27f741d00392a0cbff87430ad52ebb1a59d068a83bbf942b2ec554db67b520da`.

The dedicated Android TV API 34 emulator also passed the pod scenario on the final artifact with probe data cleared: two real OM impression/session IDs, both sessions finished, no preload measurement or billing, and one billing notice. Evidence is in `dist/measurement/tv-runtime/`. This is emulator evidence, not physical TV or Fire TV qualification.

The same final Android artifact passed all five standard format smoke cases, deliberate WebView renderer termination without a host crash, and 50 load/display/destroy cycles with one billing notice per cycle. After explicit test-only GC, weak references retained zero tested ad objects and zero container views. App-process PSS reached 100,590 KiB (98.2 MiB) and remained 100,058 KiB (97.7 MiB) after cleanup; this includes retained browser/runtime caches and excludes separate GPU/renderer processes. The probe does not establish that every allocation is leak-free or that playback memory is small.

Latest process-cold initialization medians were 4.3 ms for the mobile facade and 5.6 ms for the TV facade, measured on the mobile emulator before loading ads. These observations came from the local validation environment, including concurrent TV-emulator work, and are not a controlled comparison against the earlier hardening baseline. Raw timing/PSS samples are in `dist/measurement/footprint/profile.json`; aggregate results and remaining gates are in `dist/measurement/summary.json`.

Reproduce after staging Android packages:

```sh
npm --prefix shared ci --ignore-scripts
npm --prefix shared test
swift test --package-path apple/Core
node scripts/check-apple-consumer.mjs
python3 scripts/check-android-consumer.py
python3 scripts/measure-android-footprint.py --output dist/measurement/footprint --max-added-mib 3 --refresh-dependencies
# Start the fixture server and a booted Android emulator before the runtime check.
node contracts/mock-server/server.js --port 8787
# In another terminal:
python3 scripts/check-android-measurement.py --serial emulator-5554 --clear-data
```

Evidence is under `dist/measurement/`: platform test reports, `runtime/measurement.json`, per-case collector events, and `footprint/report.json`. The fixture script includes the official pinned `@iabtechlab-omsdk/open-measurement@1.6.10` verification client, records genuine callbacks, and never synthesizes a successful OM session from a script fetch.

Sources: [IAB OM SDK](https://iabtechlab.com/standards/open-measurement-sdk/), [native onboarding](https://iabtechlab.com/wp-content/uploads/2025/02/integration_onboard.pdf), [OpenRTB OM guidance](https://github.com/InteractiveAdvertisingBureau/AdCOM/blob/main/OpenRTB%20support%20for%20OMSDK.md), [Android IMA measurement](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/omsdk), [iOS IMA measurement](https://developers.google.com/interactive-media-ads/docs/sdks/ios/client-side/omsdk), [IAB compliance](https://iabtechlab.com/compliance-programs/open-measurement/).
