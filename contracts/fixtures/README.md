# Engage SDK v2 conformance fixtures

All fixtures are deterministic and contain no production endpoints or monetized traffic. The mock server replaces `{{BASE_URL}}` with its listening origin and adjusts successful OpenRTB bids to the posted request's `id` and `imp[0].id`.

| Scenario | Request | Response / route |
| --- | --- | --- |
| Sample-app endpoint | banner, native, or video capabilities | `POST /openrtb/2.6/auto` selects a local fixture from the impression's format, rewarded flag, and pod constraints |
| Banner MRAID | `requests/banner.json` | `responses/banner.json`, `POST /openrtb/2.6/banner` |
| Two-part MRAID banner | banner request | `responses/banner-two-part.json`, `POST /openrtb/2.6/banner-two-part`; expands to `/creatives/banner-expanded.html` |
| Native 1.2 | `requests/native.json` | `responses/native.json`, `POST /openrtb/2.6/native` |
| Native video | `requests/native-video.json` | `responses/native-video.json`, `POST /openrtb/2.6/native-video` |
| Video interstitial | `requests/interstitial.json` | `responses/interstitial.json`, `POST /openrtb/2.6/interstitial` |
| Rewarded video | `requests/rewarded.json` | `responses/rewarded.json`, `POST /openrtb/2.6/rewarded` |
| Skippable rewarded video | rewarded request | `responses/rewarded-skippable.json`, `POST /openrtb/2.6/rewarded-skippable`; 15-second local media with a one-second skip offset |
| VAST pod | `requests/pod.json` | `responses/pod.json`, `POST /openrtb/2.6/pod` |
| No fill | any valid request | `POST /openrtb/2.6/no-fill` (204) |
| Malformed response | any valid request | `POST /openrtb/2.6/malformed` |
| Ambiguous auction | any valid request | `POST /openrtb/2.6/ambiguous` |
| Delayed response | any valid request | `POST /openrtb/2.6/delayed?ms=250` |
| NURL-fetched markup | `requests/banner.json` | `responses/nurl-markup.json`, `POST /openrtb/2.6/nurl-markup` |
| Empty seatbid / bid no fill | any valid request | `POST /openrtb/2.6/no-fill-empty-seatbid`, `/no-fill-empty-bid` |
| Parser rejection cases | any valid request | routes named for `malformed-seatbid-type`, `malformed-bid-type`, `unmatched-extra-bid`, `invalid-price-string`, `invalid-price-boolean`, and `invalid-native-missing-required` response fixtures |
| Native asset id 0 | `requests/native-id-zero.json` | `responses/native-id-zero.json`, `POST /openrtb/2.6/native-id-zero` |
| More auction boundaries | any valid request | `POST /openrtb/2.6/wrong-request-id`, `/negative-price`, `/missing-burl` |
| OMID VAST inline | `requests/omid-inline.json` | `responses/omid-inline.json`, `POST /openrtb/2.6/omid-inline`, direct `/vast/omid-inline.xml` |
| OMID VAST wrapper | `requests/omid-wrapper.json` | `responses/omid-wrapper.json`, `POST /openrtb/2.6/omid-wrapper`, direct `/vast/omid-wrapper.xml` |
| OMID VAST pod | `requests/omid-pod.json` | `responses/omid-pod.json`, `POST /openrtb/2.6/omid-pod`, direct `/vast/omid-pod.xml` |
| OMID failed resource | `requests/omid-failure.json` | `responses/omid-failure.json`, `POST /openrtb/2.6/omid-failure`, direct `/vast/omid-failure.xml` |
| OMID HTML | `requests/omid-banner.json` | `responses/omid-banner.json`, `POST /openrtb/2.6/omid-banner` |
| OMID Native 1.2 | `requests/omid-native.json` | `responses/omid-native.json`, `POST /openrtb/2.6/omid-native` |

Direct VAST is available at `/vast/linear.xml`, `/vast/wrapper.xml`, `/vast/pod.xml`, and `/vast/rewarded-skippable.xml`; `/vast/media-error.xml` is valid VAST whose media URL returns 404, `/vast/no-fill.xml` returns 204, and `/vast/empty.xml` returns an empty VAST 4.2 document. `/media/test-card.mp4` is a local two-second H.264/AAC test card and `/media/test-card-15s.mp4` is its stream-looped 15-second counterpart; both support byte ranges. Rich media creatives are served from `/creatives/banner-mraid.html`, `/creatives/banner-two-part.html`, `/creatives/banner-expanded.html`, and `/creatives/interstitial-mraid.html`.

VAST XML responses reflect an incoming `Origin` and allow credentials so IMA's browser-backed wrapper loader can read subsequent VAST documents. Requests without an `Origin` receive `Access-Control-Allow-Origin: *`; this server is local test infrastructure and never serves monetized traffic.

Notice and tracking calls are recorded in order. `GET /_inspect` returns `{requests, notices, measurement}`; each auction request contains its parsed body and only the `content-type` and `x-openrtb-version` protocol headers. `POST /_reset` clears all inspection arrays. NURL, BURL, win, impression and click routes accept either GET or POST. Start with `node contracts/mock-server/server.js --port 8787`; passing port `0` from the module API selects an ephemeral port.

OM measurement observations are separate from ad notices. `GET /_inspect` also returns `measurement: {scriptRequests, events}`. Fetching `/verification/omid-verification.js?id=FIXTURE_ID` records only a script request; it cannot create `sessionStart` or `impression`. The bundled official verification client sends an event to `POST /verification/event/{event}/{fixtureId}` only when it receives that callback from an actual OMID context. `/verification/omid-failure.js` deterministically returns 404 after recording the fetch. Verification script and collector routes support CORS and preflight for IMA's browser context. Identifiers and payloads are bounded and the server stores at most 512 script requests and 512 events between resets.

The VAST fixtures carry VAST 4.2 `AdVerifications` with `apiFramework="omid"`, `browserOptional="true"`, test vendor `engage-fixture`, verification parameters, and `verificationNotExecuted` tracking. OpenRTB requests advertise API framework `7`; bids return `apis:[7]`. The Native 1.2 pair uses standard event `555`, method `2`, `url`, and only `vendorKey` plus `verification_parameters` inside `ext`. It never treats a generic `jstracker` as OM metadata.

The generated verification resource uses the official IAB Tech Lab JavaScript verification client pinned as a dev/test dependency. See `verification/PROVENANCE.md`. These fixtures verify wire preservation and allow a real host integration to expose genuine OMID callbacks. Fixture fetches and unit tests do not prove that an SDK has an OMID implementation, comply with integration requirements, or hold IAB certification.

Every request fixture uses the OpenRTB 2.6 standard locations `device.lmt`, `regs.gdpr`, `regs.gpp`, `regs.gpp_sid`, and `user.consent`. Tests should treat the values as opaque host-provided fixture inputs.
