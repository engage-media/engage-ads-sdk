# v2 capability boundaries

The request builder advertises capabilities implemented by its renderer. The matrix describes the implemented v2 integration profile, not a completed release qualification. Native Apple SDK compilation has passed on hosted Xcode; renderer validation remains outstanding. Automated and physical-device evidence is tracked in [VALIDATION.md](VALIDATION.md).

| Capability | Android mobile / iOS | Android TV / tvOS |
| --- | --- | --- |
| OpenRTB 2.6 server-selected winner | Yes | Yes |
| Direct VAST 4.2 linear ads, wrappers, pods | IMA renderer | IMA renderer |
| HTML/image banner and interstitial | Native WebView container | Not requested |
| MRAID rich media | Shared JS + native bridge | Not requested |
| Native Ads 1.2 image/text/video | Native renderer | Not requested |
| Rewarded video | Completion callback to host app | Not requested |
| On-device bidding / network SDK mediation | No | No |
| Server-side ad insertion | No | No |
| VPAID | No | No |
| Server-verified rewards | No | No |
| OM measurement for IMA video | IMA owns verification and sessions; platform qualification required | IMA owns sessions where available; physical TV qualification required |
| OM measurement for custom HTML/native display | Backend lifecycle hooks; Engage-namespaced runtime not bundled yet | Not applicable to supported TV formats |

VAST 4.2 support means the IMA-supported linear-video subset. It is not a claim to implement every optional VAST feature. VAST trackers belong to IMA; the SDK does not also fire them. TV support excludes Samsung Tizen, LG webOS, Roku, and non-Android Fire TV operating systems.

The pinned native IMA SDKs do not expose an IMPRESSION event in their public enums. Android and Apple therefore use the first STARTED event with a visible foreground container for the SDK's display-time billing trigger. Both deduplicate by winning bid across the pod; IMA still fires the actual VAST tracking URLs. Android's event surface was checked against the 3.40.0 AAR; see also [Apple IMA events](https://developers.google.com/interactive-media-ads/docs/sdks/ios/client-side/reference/Enums/IMAAdEventType).

MRAID optional location, SMS, telephone, calendar, and picture-storage support is reported unavailable. No automatic location request occurs. Containers provide geometry, exposure, foreground state, orientation, and audio updates; feature support must match native behavior. Desktop Node conformance tests exercise the JavaScript bridge, while native container qualification requires Android WebView and WKWebView device runs.

On iOS 15, MRAID orientation-forcing requests return a defined unsupported-operation error. On iOS 16 and later, orientation requests use the scene geometry API and remain subject to the host application's supported orientations. Apple video playback cancels the ad opportunity when the application enters the background; the host receives an error and content restoration runs. These behaviors require device qualification alongside the remaining renderer cases.

Native image/video requests enumerate their assets. Native OM verification uses bounded event 555 / method 2 metadata and is advertised only with a ready native measurement backend. Unknown verification/event methods are not advertised as supported. The native renderer owns its supported image impression/click trackers; embedded video retains IMA-owned VAST tracking and OM measurement without a competing native display session.

There is no standalone Open Measurement SDK certification claim. IMA's built-in measurement support varies by platform and creative. Custom HTML/native support is not complete until Engage's namespaced runtime is integrated and validated. The backend interface alone does not enable measurement. See [Open Measurement](OPEN_MEASUREMENT.md) for ownership, wire behavior, and qualification gates. A bid explicitly requiring OMID is rejected when its actual rendering path lacks the capability.

Android video and iOS video advertise IMA OM support. tvOS preserves VAST verification metadata and exposes friendly-obstruction integration, but conservatively omits OMID request capability pending platform validation. Custom-measured MRAID creatives currently reject two-part expansion with a defined error to avoid measuring the wrong document; unmeasured two-part expansion is unchanged.

## Protocol restrictions

- One impression per OpenRTB request, one server-selected winning bid in the response. Pod composition remains server-owned.
- Supported bid creative delivery is inline `adm` or a markup response obtained from `nurl`.
- OpenRTB notification macros must be resolved upstream. The client never calculates an auction clearing price.
- Direct VAST requires a full tag URL and explicit endpoint-specific parameters; the SDK does not infer server query conventions.
- Unsupported creatives, mismatched impressions, invalid prices, and ambiguous bid responses produce typed failures rather than a silent fallback auction.

Sources: [OpenRTB 2.6](https://github.com/InteractiveAdvertisingBureau/openrtb2.x/blob/main/2.6.md), [Google IMA compatibility](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/compatibility), [MRAID](https://iabtechlab.com/standards/mobile-rich-media-ad-interface-definitions-mraid/).
