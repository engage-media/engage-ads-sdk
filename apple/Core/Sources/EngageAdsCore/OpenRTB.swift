import Foundation
import CoreFoundation

struct BuiltOpenRTBRequest: Sendable { let id: String; let impressionID: String; let body: Data }

enum OpenRTBRequestBuilder {
    private static let maximumNativeAssets = 64

    static func build(configuration: EngageConfiguration, privacy: Privacy, request: AdRequest,
                      requestID: String = UUID().uuidString, impressionID: String = UUID().uuidString) throws -> BuiltOpenRTBRequest {
        try validate(configuration: configuration, request: request)
        let nativeRequestsVideo = request.format == .native && (
            request.video != nil || request.nativeAssets.contains(where: {
                if case .video = $0.kind { return true }
                return false
            })
        )
        let customMeasurementType: MeasurementCreativeType? = switch request.format {
        case .banner: .html
        case .interstitial where request.video == nil: .html
        case .native where !nativeRequestsVideo: .native
        default: nil
        }
        let customOMID = customMeasurementType.map {
            configuration.measurementCapabilities?.supportedTypes.contains($0) == true
        } ?? false
        // This Core package is Apple-specific. Pinned IMA iOS documents OM support; tvOS remains
        // conservative until its OM path is exercised on a device.
        let imaVideoOMID = configuration.deviceCategory == .mobile
        var imp: [String: Any] = ["id": impressionID, "tagid": request.placementID, "secure": 1]
        if !request.ext.isEmpty { imp["ext"] = try object(request.ext) }
        switch request.format {
        case .banner:
            guard let s = request.size else { throw EngageError.invalidRequest("Banner size is required") }
            imp["banner"] = bannerObject(s, supportsOMID: customOMID)
        case .interstitial:
            if let video = request.video {
                imp["video"] = videoObject(video, size: request.size, placement: 3, supportsOMID: imaVideoOMID)
            } else {
                guard let s = request.size else { throw EngageError.invalidRequest("Interstitial size is required") }
                imp["banner"] = bannerObject(s, supportsOMID: customOMID)
            }
            imp["instl"] = 1
        case .rewarded:
            imp["video"] = videoObject(request.video ?? VideoConstraints(), size: request.size, placement: 3, supportsOMID: imaVideoOMID)
            imp["rwdd"] = 1
            imp["instl"] = 1
        case .instream:
            imp["video"] = videoObject(request.video ?? VideoConstraints(), size: request.size, placement: 1, supportsOMID: imaVideoOMID)
        case .native:
            let assets = request.nativeAssets.isEmpty ? defaultNativeAssets(video: request.video) : request.nativeAssets
            let native = try nativeRequest(assets: assets, supportsOMID: customOMID, supportsVideoOMID: imaVideoOMID)
            let data = try JSONSerialization.data(withJSONObject: native, options: [.sortedKeys])
            guard let string = String(data: data, encoding: .utf8) else { throw EngageError.invalidRequest("Native request encoding failed") }
            var nativeObject: [String: Any] = ["request": string, "ver": "1.2"]
            if customOMID { nativeObject["api"] = [7] }
            imp["native"] = nativeObject
        }

        var app: [String: Any] = ["id": configuration.app.bundle, "bundle": configuration.app.bundle]
        if let name = configuration.app.name { app["name"] = name }
        if let store = configuration.app.storeURL { app["storeurl"] = store.absoluteString }
        if let publisher = configuration.app.publisherID { app["publisher"] = ["id": publisher] }
        if let content = request.content {
            var value: [String: Any] = [:]
            if let id = content.id { value["id"] = id }
            if let title = content.title { value["title"] = title }
            if let url = content.url { value["url"] = url.absoluteString }
            if !content.keywords.isEmpty { value["keywords"] = content.keywords.joined(separator: ",") }
            if !value.isEmpty { app["content"] = value }
        }

        var device: [String: Any] = ["devicetype": configuration.deviceCategory == .tv ? 3 : 4, "js": 1]
        if let os = configuration.device.operatingSystem { device["os"] = os }
        if let osv = configuration.device.operatingSystemVersion { device["osv"] = osv }
        if let ua = configuration.device.userAgent { device["ua"] = ua }
        if let make = configuration.device.make { device["make"] = make }
        if let model = configuration.device.model { device["model"] = model }
        if privacy.limitAdTracking != true, let ifa = privacy.advertisingID { device["ifa"] = ifa }
        if let lmt = privacy.limitAdTracking { device["lmt"] = lmt ? 1 : 0 }
        var root: [String: Any] = [
            "id": requestID, "imp": [imp], "app": app,
            "device": device,
            "at": 1, "tmax": Int(configuration.requestTimeout * 1_000)
        ]
        var regs: [String: Any] = [:]
        if let gdpr = privacy.gdprApplies { regs["gdpr"] = gdpr ? 1 : 0 }
        if let gpp = privacy.gppString { regs["gpp"] = gpp }
        if !privacy.gppSectionIDs.isEmpty { regs["gpp_sid"] = privacy.gppSectionIDs }
        if let usPrivacy = privacy.usPrivacyString { regs["us_privacy"] = usPrivacy }
        if let coppa = privacy.isChildDirected { regs["coppa"] = coppa ? 1 : 0 }
        if !regs.isEmpty { root["regs"] = regs }
        if let consent = privacy.consentString { root["user"] = ["consent": consent] }
        if customOMID, let partner = configuration.measurementCapabilities?.partner {
            root["source"] = ["ext": ["omidpn": partner.name, "omidpv": partner.version]]
        }
        let body = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        return BuiltOpenRTBRequest(id: requestID, impressionID: impressionID, body: body)
    }

    private static func validate(configuration: EngageConfiguration, request: AdRequest) throws {
        guard !request.placementID.isEmpty else { throw EngageError.invalidRequest("placementID must not be empty") }
        guard configuration.deviceCategory != .tv || request.format == .instream else {
            throw EngageError.unsupported("tvOS accepts instream requests only")
        }
        guard request.size.map({ $0.width > 0 && $0.height > 0 }) ?? true else {
            throw EngageError.invalidRequest("Ad dimensions must be positive")
        }
        if let video = request.video { try validate(video) }
        if request.format == .native {
            for asset in request.nativeAssets {
                if case .video(let video) = asset.kind { try validate(video) }
            }
        }
    }

    private static func validate(_ video: VideoConstraints) throws {
        guard !video.mimes.isEmpty else { throw EngageError.invalidRequest("Video mimes must not be empty") }
        guard !video.protocols.isEmpty else { throw EngageError.invalidRequest("Video protocols must not be empty") }
        guard video.minimumDuration >= 0, video.maximumDuration >= video.minimumDuration else {
            throw EngageError.invalidRequest("Video duration range is invalid")
        }
        guard video.podDuration.map({ $0 > 0 }) ?? true else { throw EngageError.invalidRequest("Pod duration must be positive") }
        guard video.maximumAds.map({ $0 > 0 }) ?? true else { throw EngageError.invalidRequest("Maximum pod ads must be positive") }
    }

    private static func bannerObject(_ size: AdSize, supportsOMID: Bool) -> [String: Any] {
        ["w": size.width, "h": size.height,
         "mimes": ["text/html", "application/xhtml+xml"], "api": supportsOMID ? [6, 7] : [6]]
    }

    private static func videoObject(_ video: VideoConstraints, size: AdSize?, placement: Int,
                                    supportsOMID: Bool) -> [String: Any] {
        var value: [String: Any] = ["mimes": video.mimes, "minduration": video.minimumDuration,
                                    "maxduration": video.maximumDuration, "protocols": video.protocols,
                                    "startdelay": video.startDelay, "plcmt": placement, "linearity": 1]
        if let s = size { value["w"] = s.width; value["h"] = s.height }
        if let duration = video.podDuration { value["poddur"] = duration }
        if let count = video.maximumAds { value["maxseq"] = count }
        if supportsOMID { value["api"] = [7] }
        return value
    }

    private static func defaultNativeAssets(video: VideoConstraints?) -> [NativeAssetRequest] {
        var result = [NativeAssetRequest(id: 1, required: true, kind: .title(maxLength: 90)),
                      NativeAssetRequest(id: 2, required: true, kind: .image(type: 3, minimumWidth: 1200, minimumHeight: 627)),
                      NativeAssetRequest(id: 3, required: false, kind: .data(type: 2, maxLength: 140))]
        if let video { result.append(NativeAssetRequest(id: 4, required: false, kind: .video(video))) }
        return result
    }

    private static func nativeRequest(assets: [NativeAssetRequest], supportsOMID: Bool,
                                      supportsVideoOMID: Bool) throws -> [String: Any] {
        guard assets.count <= maximumNativeAssets else {
            throw EngageError.invalidRequest("Native request exceeds the asset limit")
        }
        guard assets.allSatisfy({ $0.id >= 0 }), Set(assets.map(\.id)).count == assets.count else {
            throw EngageError.invalidRequest("Native asset ids must be unique nonnegative integers")
        }
        let encoded: [[String: Any]] = assets.map { asset in
            var item: [String: Any] = ["id": asset.id, "required": asset.required ? 1 : 0]
            switch asset.kind {
            case .title(let length): item["title"] = ["len": length]
            case .image(let type, let w, let h): item["img"] = ["type": type, "wmin": w, "hmin": h]
            case .data(let type, let length):
                var data: [String: Any] = ["type": type]; if let length { data["len"] = length }; item["data"] = data
            case .video(let v): item["video"] = videoObject(v, size: nil, placement: 4, supportsOMID: supportsVideoOMID)
            }
            return item
        }
        var eventTrackers: [[String: Any]] = [["event": 1, "methods": [1]]]
        if supportsOMID { eventTrackers.append(["event": 555, "methods": [2]]) }
        return ["ver": "1.2", "context": 1, "plcmttype": 1, "eventtrackers": eventTrackers, "assets": encoded]
    }

    private static func object(_ value: [String: JSONValue]) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }
}

public struct NativeImage: Sendable, Equatable { public let url: URL; public let width: Int?; public let height: Int? }
public enum NativeAssetValue: Sendable, Equatable { case title(String), image(NativeImage), data(String), video(String) }
public struct NativeAsset: Sendable, Equatable { public let id: Int; public let value: NativeAssetValue }
public struct NativePayload: Sendable, Equatable {
    public let assets: [NativeAsset]
    public let requiredAssetIDs: Set<Int>
    public let clickURL: URL?
    public let impressionTrackers: [URL]
    public let clickTrackers: [URL]
    public let eventTrackers: [URL]
    public let verificationResources: [MeasurementVerificationResource]
}

struct ParsedBid: Sendable {
    let bidID: String; let markup: String?; let nurl: URL?; let burl: URL?; let expiry: Date?; let native: NativePayload?
    let requiredAPIs: Set<Int>
}

enum OpenRTBResponseParser {
    private static let maximumResponseBytes = 2 * 1_024 * 1_024
    private static let maximumJSONNesting = 64
    private static let maximumNativeAssets = 64
    private static let maximumTrackerURLs = 128

    static func parse(_ response: HTTPResponse, requestID: String, impressionID: String, request: AdRequest,
                      now: Date = Date(), diagnostics: Diagnostics = .disabled,
                      measurementCapabilities: MeasurementCapabilities? = nil,
                      supportsIMAVideoOMID: Bool = false) throws -> ParsedBid {
        if response.statusCode == 204 { throw EngageError.noFill }
        guard (200..<300).contains(response.statusCode) else { throw EngageError.transport("HTTP \(response.statusCode)") }
        guard !response.body.isEmpty else { throw EngageError.malformedResponse("Empty OpenRTB response body") }
        guard response.body.count <= maximumResponseBytes else { throw EngageError.malformedResponse("OpenRTB response exceeds the size limit") }
        guard !exceedsJSONNestingLimit(response.body) else { throw EngageError.malformedResponse("OpenRTB response exceeds the nesting limit") }
        let decoded: Any
        do { decoded = try JSONSerialization.jsonObject(with: response.body) }
        catch { throw EngageError.malformedResponse("OpenRTB response is invalid JSON") }
        guard let root = decoded as? [String: Any] else { throw EngageError.malformedResponse("OpenRTB response is not an object") }
        guard let responseID = root["id"] as? String, responseID == requestID else {
            throw EngageError.malformedResponse("OpenRTB response id does not match the request")
        }
        guard let rawSeats = root["seatbid"] else { throw EngageError.noFill }
        guard let seats = rawSeats as? [[String: Any]] else { throw EngageError.malformedResponse("seatbid must be an array of objects") }
        if seats.isEmpty { throw EngageError.noFill }
        var all: [[String: Any]] = []
        for seat in seats {
            guard let rawBids = seat["bid"] else { throw EngageError.malformedResponse("seatbid.bid is missing") }
            guard let bids = rawBids as? [[String: Any]] else { throw EngageError.malformedResponse("seatbid.bid must be an array of objects") }
            all.append(contentsOf: bids)
        }
        if all.isEmpty { throw EngageError.noFill }
        guard all.count == 1 else { throw EngageError.ambiguousBid }
        let matches = all.filter { ($0["impid"] as? String) == impressionID }
        guard !matches.isEmpty else { throw EngageError.unmatchedBid }
        guard matches.count == 1 else { throw EngageError.ambiguousBid }
        let bid = matches[0]
        guard let id = bid["id"] as? String, !id.isEmpty else { throw EngageError.malformedResponse("Bid id is missing") }
        guard !isBoolean(bid["price"]), let price = number(bid["price"]), price.isFinite, price >= 0 else { throw EngageError.invalidPrice }
        let requiredAPIs = try requiredAPIs(from: bid)
        let expiry: Date?
        if let rawExp = bid["exp"] {
            guard !isBoolean(rawExp), let exp = number(rawExp), exp.isFinite, exp.rounded() == exp, exp > 0 else { throw EngageError.expiredBid }
            expiry = now.addingTimeInterval(exp)
        } else { expiry = nil }
        let adm = (bid["adm"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let nurl = try notificationURL(bid["nurl"] as? String)
        let burl = try notificationURL(bid["burl"] as? String)
        guard adm?.isEmpty == false || nurl != nil else { throw EngageError.malformedResponse("Bid has neither adm nor nurl") }
        var native: NativePayload?
        if request.format == .native, let adm, !adm.isEmpty {
            native = try parseNative(adm, requested: request.nativeAssets, defaultVideo: request.video, diagnostics: diagnostics)
        }
        if !requiredAPIs.isEmpty {
            let supportedAPIs: Set<Int>
            if request.format == .native {
                if let native {
                    let containsVideo = native.assets.contains(where: { if case .video = $0.value { return true }; return false })
                    supportedAPIs = (containsVideo ? supportsIMAVideoOMID :
                        (measurementCapabilities?.supportedTypes.contains(.native) == true)) ? [7] : []
                } else {
                    // A fetched nurl may resolve to either static native or native video. Defer the
                    // exact-path check until the fetched markup has been normalized.
                    supportedAPIs = (supportsIMAVideoOMID || measurementCapabilities?.supportedTypes.contains(.native) == true) ? [7] : []
                }
            } else if request.format == .banner || (request.format == .interstitial && request.video == nil) {
                supportedAPIs = measurementCapabilities?.supportedTypes.contains(.html) == true ? [6, 7] : [6]
            } else {
                supportedAPIs = supportsIMAVideoOMID ? [7] : []
            }
            if !requiredAPIs.isSubset(of: supportedAPIs) {
                throw EngageError.unsupported("Selected bid requires an unavailable rendering API")
            }
        }
        return ParsedBid(bidID: id, markup: adm, nurl: nurl, burl: burl, expiry: expiry, native: native,
                         requiredAPIs: requiredAPIs)
    }

    static func notificationURL(_ value: String?) throws -> URL? {
        guard let value, !value.isEmpty else { return nil }
        if value.range(of: #"\$\{[^}]+\}"#, options: .regularExpression) != nil { throw EngageError.unresolvedMacro }
        return try httpURL(value, description: "Notification URL")
    }

    private static func number(_ value: Any?) -> Double? {
        if let n = value as? NSNumber { return n.doubleValue }
        return value as? Double
    }

    private static func isBoolean(_ value: Any?) -> Bool {
        guard let value = value as? CFTypeRef else { return false }
        return CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    static func parseNative(_ markup: String, requested: [NativeAssetRequest], defaultVideo: VideoConstraints? = nil,
                            diagnostics: Diagnostics = .disabled) throws -> NativePayload {
        guard let data = markup.data(using: .utf8) else { throw EngageError.malformedResponse("Native response is not UTF-8") }
        guard data.count <= maximumResponseBytes else { throw EngageError.malformedResponse("Native response exceeds the size limit") }
        guard !exceedsJSONNestingLimit(data) else { throw EngageError.malformedResponse("Native response exceeds the nesting limit") }
        let decoded: Any
        do { decoded = try JSONSerialization.jsonObject(with: data) }
        catch { throw EngageError.malformedResponse("Native response is invalid JSON") }
        guard let root = decoded as? [String: Any],
              let native = (root["native"] as? [String: Any]) ?? (root["response"] as? [String: Any]) ?? root as [String: Any]?,
              let rawAssets = native["assets"] as? [[String: Any]] else {
            throw EngageError.malformedResponse("Native 1.2 response is malformed")
        }
        guard rawAssets.count <= maximumNativeAssets else { throw EngageError.malformedResponse("Native response exceeds the asset limit") }
        var assets: [NativeAsset] = []
        var seenIDs = Set<Int>()
        let requirements = requested.isEmpty ? defaultNativeRequirements(video: defaultVideo) : requested
        for raw in rawAssets {
            guard let number = raw["id"] as? NSNumber, !isBoolean(raw["id"]) else { throw EngageError.malformedResponse("Native asset id is invalid") }
            let numericID = number.doubleValue
            guard numericID.isFinite, numericID >= 0, numericID.rounded() == numericID,
                  numericID <= Double(Int.max) else { throw EngageError.malformedResponse("Native asset id is invalid") }
            let id = Int(numericID)
            guard seenIDs.insert(id).inserted else { throw EngageError.malformedResponse("Native asset ids must be unique") }
            guard let requestedAsset = requirements.first(where: { $0.id == id }) else {
                throw EngageError.malformedResponse("Native response contains an unrequested asset")
            }
            let value: NativeAssetValue
            if let t = raw["title"] as? [String: Any], let text = t["text"] as? String, !text.isEmpty {
                value = .title(text)
            } else if let i = raw["img"] as? [String: Any], let s = i["url"] as? String {
                value = .image(NativeImage(url: try httpURL(s, description: "Native image URL"),
                                           width: (i["w"] as? NSNumber)?.intValue, height: (i["h"] as? NSNumber)?.intValue))
            } else if let d = raw["data"] as? [String: Any], let text = d["value"] as? String, !text.isEmpty { value = .data(text)
            } else if let v = raw["video"] as? [String: Any], let vast = v["vasttag"] as? String, !vast.isEmpty { value = .video(vast)
            } else { throw EngageError.malformedResponse("Native asset payload is invalid") }
            guard matches(value, requestedAsset.kind) else { throw EngageError.malformedResponse("Native asset has the wrong type") }
            assets.append(NativeAsset(id: id, value: value))
        }
        for required in requirements where required.required {
            guard let actual = assets.first(where: { $0.id == required.id }), matches(actual.value, required.kind) else {
                throw EngageError.malformedResponse("A required native asset is missing or has the wrong type")
            }
        }
        guard let link = native["link"] as? [String: Any], let clickString = link["url"] as? String else {
            throw EngageError.malformedResponse("Native click link is missing or invalid")
        }
        let clickURL = try safeClickURL(clickString)
        let clickTrackers = try httpURLArray(link["clicktrackers"], description: "Native click tracker URL")
        let impressions = try httpURLArray(native["imptrackers"], description: "Native impression tracker URL")
        var events: [URL] = []
        var verifications: [MeasurementVerificationResource] = []
        var skippedVerifications = 0
        let rawEvents: [[String: Any]]
        if let raw = native["eventtrackers"] {
            if let parsed = raw as? [[String: Any]] { rawEvents = parsed }
            else {
                rawEvents = []
                skippedVerifications += 1
            }
        } else { rawEvents = [] }
        guard rawEvents.count <= maximumTrackerURLs else { throw EngageError.malformedResponse("Native event tracker list exceeds the limit") }
        for event in rawEvents {
            let eventCode = strictInteger(event["event"])
            let method = strictInteger(event["method"])
            if eventCode == 555 {
                guard method == 2, verifications.count < 32,
                      let value = event["url"] as? String,
                      let url = URL(string: value), value.utf8.count <= 2_048 else {
                    skippedVerifications += 1; continue
                }
                let ext: [String: Any]
                if let rawExt = event["ext"] {
                    guard let parsed = rawExt as? [String: Any] else { skippedVerifications += 1; continue }
                    ext = parsed
                } else { ext = [:] }
                guard ext["vendorKey"] == nil || ext["vendorKey"] is String,
                      ext["verification_parameters"] == nil || ext["verification_parameters"] is String,
                      let resource = try? MeasurementVerificationResource(
                        javascriptURL: url,
                        vendorKey: ext["vendorKey"] as? String,
                        verificationParameters: ext["verification_parameters"] as? String
                      ) else {
                    skippedVerifications += 1; continue
                }
                verifications.append(resource)
                continue
            }
            guard eventCode == 1, method == 1 else { continue }
            guard let value = event["url"] as? String else {
                throw EngageError.malformedResponse("Native event tracker URL is invalid")
            }
            events.append(try httpURL(value, description: "Native event tracker URL"))
        }
        if skippedVerifications > 0 {
            diagnostics.emit(.warning, "measurement_verification_skipped",
                             "Invalid optional measurement verification metadata was skipped",
                             metadata: ["count": String(skippedVerifications)])
        }
        return NativePayload(assets: assets, requiredAssetIDs: Set(requirements.filter(\.required).map(\.id)), clickURL: clickURL, impressionTrackers: impressions,
                             clickTrackers: clickTrackers, eventTrackers: events, verificationResources: verifications)
    }

    private static func defaultNativeRequirements(video: VideoConstraints?) -> [NativeAssetRequest] {
        var requirements = [NativeAssetRequest(id: 1, required: true, kind: .title(maxLength: 90)),
                            NativeAssetRequest(id: 2, required: true, kind: .image(type: 3, minimumWidth: 1200, minimumHeight: 627)),
                            NativeAssetRequest(id: 3, required: false, kind: .data(type: 2, maxLength: 140))]
        if let video { requirements.append(NativeAssetRequest(id: 4, required: false, kind: .video(video))) }
        return requirements
    }

    private static func httpURLArray(_ raw: Any?, description: String) throws -> [URL] {
        guard let raw else { return [] }
        guard let values = raw as? [String] else { throw EngageError.malformedResponse("\(description) list is invalid") }
        guard values.count <= maximumTrackerURLs else { throw EngageError.malformedResponse("\(description) list exceeds the limit") }
        return try values.map { try httpURL($0, description: description) }
    }

    private static func exceedsJSONNestingLimit(_ data: Data) -> Bool {
        var depth = 0
        var inString = false
        var escaped = false
        for byte in data {
            if inString {
                if escaped { escaped = false }
                else if byte == 0x5c { escaped = true }
                else if byte == 0x22 { inString = false }
                continue
            }
            if byte == 0x22 { inString = true }
            else if byte == 0x7b || byte == 0x5b {
                depth += 1
                if depth > maximumJSONNesting { return true }
            } else if byte == 0x7d || byte == 0x5d {
                depth = max(0, depth - 1)
            }
        }
        return false
    }

    private static func httpURL(_ value: String, description: String) throws -> URL {
        guard let url = URL(string: value), let scheme = url.scheme?.lowercased(),
              (scheme == "http" || scheme == "https"), url.host?.isEmpty == false else {
            throw EngageError.malformedResponse("\(description) is invalid")
        }
        return url
    }

    private static func safeClickURL(_ value: String) throws -> URL {
        guard let url = URL(string: value), let scheme = url.scheme?.lowercased(), !scheme.isEmpty,
              !["javascript", "data", "file", "about"].contains(scheme) else {
            throw EngageError.malformedResponse("Native click link is missing or invalid")
        }
        if scheme == "http" || scheme == "https" {
            guard url.host?.isEmpty == false else { throw EngageError.malformedResponse("Native click link is missing or invalid") }
        }
        return url
    }

    private static func requiredAPIs(from bid: [String: Any]) throws -> Set<Int> {
        var result = Set<Int>()
        if let raw = bid["api"] {
            guard let value = strictInteger(raw), value >= 0 else { throw EngageError.malformedResponse("Bid api is invalid") }
            result.insert(value)
        }
        if let raw = bid["apis"] {
            guard let values = raw as? [Any] else { throw EngageError.malformedResponse("Bid apis is invalid") }
            guard values.count <= 64 else { throw EngageError.malformedResponse("Bid apis exceeds the limit") }
            for rawValue in values {
                guard let value = strictInteger(rawValue), value >= 0 else { throw EngageError.malformedResponse("Bid apis is invalid") }
                result.insert(value)
            }
        }
        return result
    }

    private static func strictInteger(_ value: Any?) -> Int? {
        guard !isBoolean(value), let number = value as? NSNumber else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double >= Double(Int.min), double <= Double(Int.max), double.rounded() == double else { return nil }
        return Int(double)
    }

    private static func matches(_ value: NativeAssetValue, _ kind: NativeAssetKind) -> Bool {
        switch (value, kind) {
        case (.title, .title), (.image, .image), (.data, .data), (.video, .video): return true
        default: return false
        }
    }
}
