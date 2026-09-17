import Foundation
import EngageAdsCore

struct CheckFailure: Error, CustomStringConvertible {
    let description: String
}

func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw CheckFailure(description: message) }
}

@main struct Consumer {
    static func main() async throws {
        guard let origin = ProcessInfo.processInfo.environment["ENGAGE_FIXTURE_ORIGIN"] else {
            throw CheckFailure(description: "Run through scripts/check-apple-consumer.mjs")
        }
        func client(_ scenario: String) -> EngageClient {
            EngageClient(configuration: EngageConfiguration(
                endpoint: .openRTB26(url: URL(string: origin + "/openrtb/2.6/" + scenario)!),
                app: AppMetadata(bundle: "com.engage.consumer", name: "Fresh Consumer"),
                deviceCategory: .mobile
            ))
        }
        func inspect() async throws -> [[String: Any]] {
            let (data, _) = try await URLSession.shared.data(from: URL(string: origin + "/_inspect")!)
            let value = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            return value["notices"] as! [[String: Any]]
        }
        func waitForBilling() async throws -> [[String: Any]] {
            for _ in 0..<100 {
                let notices = try await inspect()
                if notices.contains(where: { $0["kind"] as? String == "burl" }) { return notices }
                try await Task.sleep(for: .milliseconds(20))
            }
            throw CheckFailure(description: "Display did not dispatch billing within two seconds")
        }
        let banner = AdRequest(placementID: "consumer-banner", format: .banner, size: AdSize(width: 320, height: 50))
        let bannerClient = client("banner")
        try bannerClient.updatePrivacy(PrivacyContext(gdprApplies: true, consentString: "fixture-consent",
                                                     gppString: "fixture-gpp", gppSectionIDs: [2],
                                                     limitAdTracking: true))
        let loaded = try await bannerClient.load(banner)
        try require(loaded.kind == .html, "Banner response did not normalize as HTML")
        let (wireData, _) = try await URLSession.shared.data(from: URL(string: origin + "/_inspect")!)
        let wireState = try JSONSerialization.jsonObject(with: wireData) as! [String: Any]
        let firstRequest = (wireState["requests"] as! [[String: Any]])[0]
        let headers = firstRequest["headers"] as! [String: String]
        let body = firstRequest["body"] as! [String: Any]
        let regs = body["regs"] as! [String: Any]
        let user = body["user"] as! [String: Any]
        let device = body["device"] as! [String: Any]
        try require(headers["x-openrtb-version"] == "2.6", "OpenRTB version header missing")
        try require(regs["gdpr"] as? Int == 1 && regs["gpp"] as? String == "fixture-gpp", "Privacy is not in standard 2.6 fields")
        try require(user["consent"] as? String == "fixture-consent", "Consent was not forwarded")
        try require(device["lmt"] as? Int == 1 && device["ifa"] == nil, "Tracking-limited request unexpectedly contains an advertising ID")
        let impression = (body["imp"] as! [[String: Any]])[0]
        let bannerCapabilities = impression["banner"] as! [String: Any]
        try require(!(bannerCapabilities["api"] as? [Int] ?? []).contains(7), "Banner without an OM backend advertised OMID")
        try require(body["source"] == nil, "Missing OM backend must not invent a partner identity")
        let before = try await inspect()
        try require(!before.contains { $0["kind"] as? String == "burl" }, "Preload incorrectly sent billing")
        try await loaded.beginDisplay(visibleArea: 100, foreground: true)
        let displayed = try await waitForBilling()
        try require(displayed.filter { $0["kind"] as? String == "burl" }.count == 1, "Display must send one billing notice")
        do {
            try await loaded.beginDisplay(visibleArea: 100, foreground: true)
        } catch { /* A duplicate display may be rejected or ignored, but must not bill again. */ }
        let repeated = try await inspect()
        try require(repeated.filter { $0["kind"] as? String == "burl" }.count == 1, "Duplicate display billed again")
        loaded.destroy()

        for scenario in ["no-fill", "no-fill-empty-seatbid", "no-fill-empty-bid"] {
            do {
                _ = try await client(scenario).load(banner)
                throw CheckFailure(description: "\(scenario) unexpectedly loaded")
            } catch EngageError.noFill { }
        }
        for scenario in ["malformed", "ambiguous", "malformed-seatbid-type", "malformed-bid-type", "unmatched-extra-bid", "invalid-price-string", "invalid-price-boolean", "wrong-request-id", "negative-price"] {
            do {
                _ = try await client(scenario).load(banner)
                throw CheckFailure(description: "\(scenario) unexpectedly loaded")
            } catch is EngageError { }
        }
        let fetched = try await client("nurl-markup").load(banner)
        try require(fetched.markup.contains("mraid"), "nurl response did not supply creative markup")
        fetched.destroy()
        let native = try await client("native-video").load(AdRequest(
            placementID: "consumer-native", format: .native, video: VideoConstraints(),
            nativeAssets: [NativeAssetRequest(id: 1, kind: .title(maxLength: 80)),
                           NativeAssetRequest(id: 4, kind: .video(VideoConstraints()))]
        ))
        try require(native.native?.assets.contains { if case .video = $0.value { return true }; return false } == true,
                    "Native video asset missing")
        native.destroy()
        for scenario in ["linear", "wrapper", "pod", "omid-inline", "omid-wrapper", "omid-pod"] {
            let vastClient = EngageClient(configuration: EngageConfiguration(
                endpoint: .vastTag(url: URL(string: origin + "/vast/" + scenario + ".xml?existing=kept")!,
                                   parameters: ["custom": "a&b"]),
                app: AppMetadata(bundle: "com.engage.consumer"), deviceCategory: .tv))
            let video = try await vastClient.load(AdRequest(placementID: "consumer-break", format: .instream))
            try require(video.kind == .vast && video.markup.contains("<VAST"), "Direct VAST markup was not loaded")
            if scenario.hasPrefix("omid-") {
                try require(video.markup.contains("<AdVerifications>") && video.markup.contains("apiFramework=\"omid\""),
                            "VAST OM verification metadata was lost in transport")
            }
            try video.beginDisplayImmediately()
            video.destroy()
            vastClient.destroy()
        }
        for scenario in ["no-fill", "empty"] {
            let vastClient = EngageClient(configuration: EngageConfiguration(
                endpoint: .vastTag(url: URL(string: origin + "/vast/" + scenario + ".xml")!),
                app: AppMetadata(bundle: "com.engage.consumer"), deviceCategory: .tv))
            do {
                _ = try await vastClient.load(AdRequest(placementID: "consumer-break", format: .instream))
                throw CheckFailure(description: "Direct VAST \(scenario) unexpectedly loaded")
            } catch EngageError.noFill { }
            vastClient.destroy()
        }
        let after = try await inspect()
        try require(after.filter { $0["kind"] as? String == "burl" }.count == 1, "Unused creatives or direct VAST incorrectly billed")
        print("PASS: fresh Swift core consumer, real HTTP, no-fill/rejections, nurl, native video, VAST wrappers/pods/OM metadata preservation, honest default capabilities, and display-time billing")
    }
}
