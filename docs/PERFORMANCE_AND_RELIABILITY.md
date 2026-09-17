# Performance and host-app reliability

The later [Open Measurement validation](OPEN_MEASUREMENT.md#verification-results--2026-09-17) reports the current artifact: about **2.26 MiB** Android release APK growth, **177** passing automated tests, and rerun renderer/50-cycle cleanup checks. The custom OM backend is not yet bundled; the original hardening measurements below remain historical evidence rather than a size estimate for the missing namespaced runtime.

This SDK uses bounded input handling, lazy rendering, explicit cleanup, and recoverable error boundaries. It cannot guarantee that a host application will never crash. OS memory termination, native framework faults, fatal runtime errors, arbitrary creative JavaScript, and host integration errors remain outside a general exception handler's protection. Native Apple validation is still blocked by the missing full Xcode installation.

## Reproducible footprint checks

```sh
scripts/android-gradle.sh testDebugUnitTest assemble lintDebug publishAllPublicationsToStagingRepository
python3 scripts/measure-android-footprint.py --max-added-mib 3 --refresh-dependencies
python3 scripts/profile-android-footprint.py --serial emulator-5554
node contracts/mock-server/server.js --port 8787
# In another terminal, while the local fixture server is running:
python3 scripts/check-android-release-runtime.py --serial emulator-5554 --cycles 50
```

The size check builds three standalone release apps from staged Maven dependencies: a minimal baseline, the mobile facade, and the TV facade. R8 and resource shrinking are enabled without broad SDK keep rules. Runtime-selected ad requests keep rendering paths reachable. The release-runtime check exercises those optimized artifacts, MRAID expansion/resizing, IMA playback, rewarded completion, embedded native video, renderer-process termination, and repeated load/display/destroy cycles.

CI rejects compressed APK growth above **3 MiB per Android facade**. This is an initial regression budget based on local measurements, not an Android App Bundle download-size guarantee or an Apple size budget. APK growth includes the SDK, its resolved dependencies, and the small probe UI; a real host already using overlapping dependencies may have a different delta. Logs and machine-readable measurements are under `dist/hardening/`.

Initialization profiling uses five process-cold starts per app and 50 warm client create/destroy operations. Startup figures come from one API 35 emulator and include host scheduling noise. Idle CPU is a two-second observation, not a battery-life test. App-process PSS excludes separate renderer/GPU processes and cannot establish total application memory or prove the absence of leaks.

The lifecycle probe records weak references to each destroyed ad and its container view, then requests GC in the test application only. A zero retained count is evidence for those objects in that scenario; the SDK does not force GC in publisher apps. Browser, codec, framework and GPU caches may remain resident after ad destruction.

## Measured snapshot — 17 September 2026

| Consumer | Added compressed release APK | Median cold client initialization | Added idle app-process PSS | Warm create/destroy p95 |
| --- | ---: | ---: | ---: | ---: |
| Android mobile | 2.25 MiB | 2.98 ms | 3.43 MiB | 0.278 ms |
| Android tv | 2.25 MiB | 3.69 ms | 3.37 MiB | 0.371 ms |

The core AAR is 268,474 bytes (262.2 KiB); the mobile and TV facade AARs are about 2.2 KiB each. These archive sizes exclude transitive dependencies; the APK column includes them. No app-process CPU ticks were observed in the two-second idle samples. This does not establish an energy budget during ad loading or playback.

The standalone release consumers and both normal staged-package consumers compile successfully. R8 runtime checks cover banner/MRAID, video interstitial, rewarded, native video and the TV video facade. The TV footprint probe runs on the same mobile emulator for comparable profiling; it is not a new TV-device performance qualification.

Automated validation passes **139 tests**: 74 Android, 21 Swift Foundation, 37 JavaScript/fixture-server and 7 release-tooling tests. Android callback/fault tests include throwing host listeners, renderer initialization/cleanup, process death, input limits, billing/cancellation, and a deterministic client create/destroy race. Apple native source remains uncompiled locally despite passing Core and real-HTTP checks.

Apple's cached IMA device dylibs measure **2,941,384 bytes on iOS** and **12,697,296 bytes on tvOS**. These are raw vendor binaries, not measured final app download increases. A signed, thinned consumer build on full Xcode is required before claiming an Apple footprint budget.

Final optimized-artifact stress completed **50** load/display/destroy cycles with 50 billing notices and **zero retained ad objects or container views** after test GC. Deliberately terminating the WebView renderer returned a typed `RENDER` error while the host process remained alive. During the stress run, app-process PSS peaked at **102.5 MiB** and remained **99.8 MiB** after cleanup/GC. Browser/runtime memory stays resident even when the measured ad/view instances are collectible; this is not a claim of a small total rendering footprint or proof that every allocation is leak-free.

## Input and buffering limits

| Resource | Default defensive limit |
| --- | --- |
| SDK HTTP/OpenRTB response | 2 MiB; incremental reads stop at the limit |
| Serialized OpenRTB request | 1 MiB |
| VAST markup | 1 MiB UTF-8 |
| JSON / XML nesting | 64 levels; checked before deep parsing |
| Native response assets | 64 |
| Native tracker arrays | 128 entries per array |
| Native image download | 8 MiB compressed |
| Decoded native image output | At most 4,194,304 pixels; downsample before full display decoding |
| Android video buffering | 16 MiB allocator target, maximum 10 seconds; smaller buffer increases sensitivity to network jitter |
| MRAID envelope | 65,536 UTF-16 code units |
| MRAID structured values | Depth 8; JS additionally bounds node, property, array and string counts |
| Native MRAID ingress | 64 commands/second, enforced even if creative code bypasses the JS wrapper |
| JS MRAID receives/listeners/events | 512 receives/second, 64 listeners/event, 256 queued/deferred events |

Oversized or malformed ad input is rejected with a typed failure rather than silently truncated. Android samples large images; Apple uses ImageIO thumbnail decoding off the main actor. Decoded pixel limits and media allocator targets do **not** strictly cap decoder, framework, GPU, or total process memory.

These limits cover SDK-owned requests, parsing, image loading and bridge processing. IMA, Media3, WebView and WKWebView can fetch media or subresources internally; those paths do not automatically inherit the SDK HTTP limit. Arbitrary creative JavaScript can still exhaust its renderer process. The SDK handles supported WebView process-death callbacks by failing the opportunity and releasing the affected view; it does not promise to prevent all renderer exits.

## Recoverable failures and cleanup

Android isolates ordinary exceptions from host event, diagnostics and content-control callbacks, renderer initialization, player callbacks and teardown. Cancellation remains cancellation. It does not install a process-wide exception handler or swallow fatal VM errors. Invalid configuration and programmer-misuse exceptions remain part of the existing public API contract.

Apple Core rejects malformed/deep/oversized inputs and cancels streamed requests. Native changes address WKWebView termination, weak display-link targets, player delegate/playhead cleanup, callback reentrancy, and bounded image decoding. Swift `do/catch` does not catch `fatalError`, out-of-memory termination, or Objective-C exceptions. Native Apple source has been syntax checked; UIKit/WebKit/IMA compilation and runtime behavior still require full Xcode.

The MRAID bridge bounds work and isolates listener exceptions. Floods produce bounded error notifications; listener recursion and deferred callbacks do not create unbounded queues. Native ingress repeats the essential checks because the JavaScript wrapper is not a security boundary.

## Required release qualification

- Profile first ad load, sustained video, renderer processes, GPU memory, frame timing and energy use on supported low-memory physical devices, including Fire TV and Apple TV.
- Run repeated lifecycle and fault tests on real WebView/WKWebView versions, interrupted networks, background/foreground transitions, memory pressure, and publisher player integration.
- Compile and run native Apple products, add the missing renderer scenarios described in [APPLE_VALIDATION_GAPS.md](APPLE_VALIDATION_GAPS.md), and measure signed/thinned iOS/tvOS app size.
- Exercise an authorized real-server campaign and inspect structured diagnostics for silent failures as well as crashes.

Reference for Apple's incremental network API: [URLSession bytes(for:delegate:)](https://developer.apple.com/documentation/foundation/urlsession/bytes(for:delegate:)).
