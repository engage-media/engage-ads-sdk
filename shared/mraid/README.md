# Engage MRAID 3 bridge

`mraid.js` is the canonical JavaScript copied unchanged into Android and Apple resources. It has no runtime dependencies. Native code must install `EngageMraidNative.postMessage(string)` before creative JavaScript runs, then deliver authoritative updates through `window.__engageMraid.receive(message)`.

The creative-to-native envelope is exactly:

```json
{"id":1,"command":"open","args":{"url":"https://example.test"}}
```

IDs are increasing integers. Supported commands are `open`, `close`, `expand`, `resize`, `setOrientationProperties`, `playVideo`, `storePicture`, `createCalendarEvent`, and `unload`. The bridge rejects commands before ready or after hidden, except that `unload` is valid during initialization and remains dispatchable after hidden. Inline close remains available in the default state so the SDK close affordance and creative close can both dismiss an ad. The bridge rejects interstitial expand/resize, resize before valid resize properties, and resize while expanded. A repeated expand in the expanded state is ignored.

Native ready messages must provide the complete contract fields, including:

```json
{
  "type": "ready",
  "state": "default",
  "placementType": "inline",
  "screenSize": {"width": 390, "height": 844},
  "maxSize": {"width": 390, "height": 800},
  "currentPosition": {"x": 0, "y": 100, "width": 320, "height": 50},
  "defaultPosition": {"x": 0, "y": 100, "width": 320, "height": 50},
  "currentAppOrientation": {"orientation": "portrait", "locked": false},
  "location": null,
  "supports": {"sms": false, "tel": false, "calendar": false, "storePicture": false, "inlineVideo": true, "location": false}
}
```

Geometry updates also include `currentAppOrientation`. Visibility uses an exposed percentage from 0 through 100 plus a visible rectangle and occlusion rectangles. Audio is `null` or a percentage from 0 through 100. MRAID location collection is intentionally unsupported in SDK v2: `supports("location")` is false and `getLocation()` returns `-1`. `vpaid` support is false. Calendar and picture methods remain present for creative compatibility and native returns an error because their support flags are false.

The exposed API covers version, state, placement, viewability, geometry, expansion, resize, orientation, current app orientation, location, feature support, commands, and MRAID events. Deprecated `isViewable`, `viewableChange`, and `useCustomClose` remain available for backwards compatibility. The custom-close preference is retained for getters and expansion arguments; MRAID 3 native renderers must keep their own close affordance accessible.

The bridge treats both creative calls and messages delivered through `__engageMraid.receive` as untrusted input. A native message or outbound command envelope is limited to 65,536 UTF-16 code units. Structured input is limited to depth 8, 1,024 values, 256 array entries, 64 object fields, 8,192 code units per string, and 65,536 combined code units across field names and string values; URL inputs have the same 8,192-code-unit limit. Cycles, non-finite numbers, dangerous prototype field names, and coordinates or dimensions outside the supported range are rejected through the MRAID error event. These limits are far above normal MRAID payload sizes.

To prevent a creative from flooding native callbacks or recursively exhausting the JavaScript stack, the bridge permits 64 commands and 512 authoritative-message calls per one-second window, 64 listeners per event, and 256 queued events. Reentrant events are drained iteratively. Cached exposure/audio callbacks share one pending timer, and removing a listener before the timer runs cancels its delivery. Only the first rate-limit error in each window is emitted.

These checks bound work performed by the bridge. They cannot guarantee that arbitrary creative JavaScript will not consume excessive CPU or memory, and JavaScript cannot recover a terminated WebView content process. Native WebView process isolation, lifecycle teardown, navigation policy, and platform-level recovery remain required.

`mraid.test.js` executes the script in an isolated browser-like VM and verifies the exact outbound envelope, state and placement restrictions, property defaults and validation, lifecycle updates, getter copies, listener behavior, exposure and audio semantics, orientation, unsupported location, deprecated compatibility, and malformed-use error events. It also uses deterministic malformed-input fuzzing and fault injection for oversized/cyclic structures, callback reentrancy, listener and command floods, timer coalescing, native bridge exceptions, and cleanup after listener removal. Run it together with the fixture server tests using:

```sh
npm --prefix shared test
```
