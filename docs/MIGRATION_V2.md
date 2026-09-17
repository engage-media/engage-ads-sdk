# Migrating from Android v1 to v2

v2 is a breaking API release. Keep the existing published v1 dependency until the host application has been migrated and tested. Do not mix v1 and v2 in the same integration.

| v1 | v2 |
| --- | --- |
| Global EMAdsModule initialization | Explicit EngageClient with immutable EngageConfiguration |
| baseUrl and implicit production/demo defaults | Required Endpoint.OpenRTB26 or Endpoint.VastTag; no default or debug substitution |
| publisherId/channelId appended automatically | App metadata and placement ID in OpenRTB; explicit parameters for direct VAST |
| isGdprApproved boolean | Independent applicability, consent-string, GPP, child-directed and identifier inputs |
| Synthetic device identifiers | Host-permitted advertising identifier or omitted identifier |
| EMAdView for video | Format-specific ad objects and SDK-owned rendering containers |
| Ambiguous onAdEnded callbacks | Separate displayed, adCompleted, breakCompleted, dismissed and error events |
| View lifetime owns incomplete cleanup | Explicit destroy, cancellation, stale-callback suppression and content restoration |

## Host application changes

1. Select the mobile or TV facade dependency. v2 Android requires API 24+, JDK 17 for builds, and Java API desugaring as required by IMA. Follow the sample app's Gradle configuration.
2. Store the endpoint in the host application's build/environment configuration and pass it into the SDK. Do not embed server-side bidder secrets in the application.
3. Supply app metadata and an explicit privacy snapshot. Update privacy before subsequent loads when consent changes. The SDK does not display a consent dialog or request tracking permission for the host.
4. Create a single-use ad object for each opportunity. Attach listeners before loading, present only after readiness, and destroy it when its host screen is disposed.
5. Connect the content-controller callbacks for video breaks. Trigger pre-, mid-, or post-roll using the application's content schedule.
6. Grant rewards only from rewardEarned. A loaded, skipped, dismissed, or failed video does not earn a reward.

## Server changes to verify

The SDK talks to one configured ad server. It does not fan out to bidder endpoints. The SDK's supported OpenRTB profile expects one server-selected winning bid for its single requested impression; competing or unmatched bids are rejected. Use inline adm or nurl-delivered markup, and resolve SDK-facing notification macros on the server.

The SDK sends burl when the ad first displays, not on receipt. The server must avoid duplicating that same SDK-facing billing notice. Upstream exchange/bidder notification policy remains server-owned. Direct VAST has normal player-owned VAST tracking and no separate billing callback.

See [the integration contract](../contracts/SDK_V2.md) for precise boundaries and [validation](VALIDATION.md) for release acceptance cases.
