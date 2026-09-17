import Foundation

public enum JSONValue: Codable, Hashable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
}

public enum DeviceCategory: String, Codable, Sendable { case mobile, tv }

public struct DeviceMetadata: Sendable, Equatable {
    public var operatingSystem: String?
    public var operatingSystemVersion: String?
    public var userAgent: String?
    public var make: String?
    public var model: String?
    /// Retained for source compatibility. Request identity is read exclusively from `PrivacyContext.advertisingID`
    /// so a later privacy update can withdraw it.
    public var advertisingID: String?
    public init(operatingSystem: String? = nil, operatingSystemVersion: String? = nil, userAgent: String? = nil,
                make: String? = nil, model: String? = nil, advertisingID: String? = nil) {
        self.operatingSystem = operatingSystem; self.operatingSystemVersion = operatingSystemVersion
        self.userAgent = userAgent; self.make = make; self.model = model; self.advertisingID = advertisingID
    }
}

public struct AppMetadata: Sendable, Equatable {
    public let bundle: String
    public let name: String?
    public let storeURL: URL?
    public let publisherID: String?

    public init(bundle: String, name: String? = nil, storeURL: URL? = nil, publisherID: String? = nil) {
        self.bundle = bundle; self.name = name; self.storeURL = storeURL; self.publisherID = publisherID
    }
}

public enum Endpoint: Sendable, Equatable {
    case openRTB26(url: URL, headers: [String: String] = [:])
    case vastTag(url: URL, parameters: [String: String] = [:])
}

public struct Privacy: Sendable, Equatable {
    public var gdprApplies: Bool?
    public var consentString: String?
    public var gppString: String?
    public var gppSectionIDs: [Int]
    public var isChildDirected: Bool?
    public var limitAdTracking: Bool?
    public var advertisingID: String?
    public var usPrivacyString: String?

    public init(gdprApplies: Bool? = nil, consentString: String? = nil, gppString: String? = nil,
                gppSectionIDs: [Int] = [], isChildDirected: Bool? = nil, limitAdTracking: Bool? = nil,
                advertisingID: String? = nil, usPrivacyString: String? = nil) {
        self.gdprApplies = gdprApplies; self.consentString = consentString; self.gppString = gppString
        self.gppSectionIDs = gppSectionIDs; self.isChildDirected = isChildDirected
        self.limitAdTracking = limitAdTracking
        self.advertisingID = advertisingID
        self.usPrivacyString = usPrivacyString
    }
}

public typealias PrivacyContext = Privacy

public struct EngageConfiguration: Sendable {
    public let endpoint: Endpoint
    public let app: AppMetadata
    public let deviceCategory: DeviceCategory
    public let device: DeviceMetadata
    public let requestTimeout: TimeInterval
    public let creativeTimeout: TimeInterval
    public let diagnostics: Diagnostics
    public let measurementBackend: (any MeasurementBackend)?
    public let measurementCapabilities: MeasurementCapabilities?

    public init(endpoint: Endpoint, app: AppMetadata, deviceCategory: DeviceCategory, device: DeviceMetadata = DeviceMetadata(),
                requestTimeout: TimeInterval = 5, creativeTimeout: TimeInterval = 15,
                diagnostics: Diagnostics = .disabled, measurementBackend: (any MeasurementBackend)? = nil) {
        self.endpoint = endpoint; self.app = app; self.deviceCategory = deviceCategory; self.device = device
        // This initializer is intentionally nonthrowing. Invalid host configuration must not
        // trap the process or overflow renderer nanosecond conversions, so use documented defaults.
        self.requestTimeout = Self.safeTimeout(requestTimeout, default: 5)
        self.creativeTimeout = Self.safeTimeout(creativeTimeout, default: 15)
        self.diagnostics = diagnostics
        let snapshot = try? measurementBackend?.capabilitySnapshot()
        if let snapshot, snapshot.isUsable {
            self.measurementBackend = measurementBackend
            self.measurementCapabilities = snapshot
        } else {
            self.measurementBackend = nil
            self.measurementCapabilities = nil
            if measurementBackend != nil {
                diagnostics.emit(.warning, "measurement_backend_unavailable", "Measurement backend is not ready")
            }
        }
    }

    private static func safeTimeout(_ value: TimeInterval, default fallback: TimeInterval) -> TimeInterval {
        value.isFinite && value > 0 && value <= 18_000_000_000 ? value : fallback
    }
}

public enum AdFormat: String, Codable, Sendable { case banner, interstitial, rewarded, native, instream }

public struct AdSize: Codable, Hashable, Sendable {
    public let width: Int
    public let height: Int
    public init(width: Int, height: Int) { self.width = width; self.height = height }
}

public struct ContentMetadata: Sendable, Equatable {
    public var id: String?
    public var title: String?
    public var url: URL?
    public var keywords: [String]
    public init(id: String? = nil, title: String? = nil, url: URL? = nil, keywords: [String] = []) {
        self.id = id; self.title = title; self.url = url; self.keywords = keywords
    }
}

public struct VideoConstraints: Sendable, Hashable {
    public var mimes: [String]
    public var minimumDuration: Int
    public var maximumDuration: Int
    public var protocols: [Int]
    public var startDelay: Int
    public var podDuration: Int?
    public var maximumAds: Int?
    public init(mimes: [String] = ["video/mp4", "application/x-mpegURL"], minimumDuration: Int = 0,
                maximumDuration: Int = 120, protocols: [Int] = [2, 3, 5, 6, 7, 8, 11, 12, 13, 14],
                startDelay: Int = 0, podDuration: Int? = nil, maximumAds: Int? = nil) {
        self.mimes = mimes; self.minimumDuration = minimumDuration; self.maximumDuration = maximumDuration
        self.protocols = protocols; self.startDelay = startDelay
        self.podDuration = podDuration; self.maximumAds = maximumAds
    }
}

public enum NativeAssetKind: Hashable, Sendable {
    case title(maxLength: Int)
    case image(type: Int, minimumWidth: Int, minimumHeight: Int)
    case data(type: Int, maxLength: Int?)
    case video(VideoConstraints)
}

public struct NativeAssetRequest: Hashable, Sendable {
    public let id: Int
    public let required: Bool
    public let kind: NativeAssetKind
    public init(id: Int, required: Bool = false, kind: NativeAssetKind) { self.id = id; self.required = required; self.kind = kind }
}

public struct AdRequest: Sendable {
    public let placementID: String
    public let format: AdFormat
    public let size: AdSize?
    public let content: ContentMetadata?
    public let video: VideoConstraints?
    public let nativeAssets: [NativeAssetRequest]
    public let ext: [String: JSONValue]

    public init(placementID: String, format: AdFormat, size: AdSize? = nil,
                content: ContentMetadata? = nil, video: VideoConstraints? = nil,
                nativeAssets: [NativeAssetRequest] = [], ext: [String: JSONValue] = [:]) {
        self.placementID = placementID; self.format = format; self.size = size; self.content = content
        self.video = video; self.nativeAssets = nativeAssets; self.ext = ext
    }
}

public enum CreativeKind: String, Sendable { case html, vast, native }
public enum AdState: String, Sendable { case idle, loading, ready, displaying, finished, failed, destroyed }

public enum EngageEvent: Sendable, Equatable {
    case loaded, displayed, adCompleted, breakCompleted, dismissed, noFill, rewardEarned, clicked, error(EngageError)
}

public enum EngageError: Error, Sendable, Equatable, CustomStringConvertible {
    case invalidRequest(String), unsupported(String), noFill, transport(String), malformedResponse(String)
    case ambiguousBid, unmatchedBid, expiredBid, invalidPrice, unresolvedMacro, invalidState(expected: String, actual: String)
    case creativeTimeout, destroyed, rendering(String)

    public var description: String {
        switch self {
        case .invalidRequest(let s), .unsupported(let s), .transport(let s), .malformedResponse(let s), .rendering(let s): return s
        case .noFill: return "No fill"
        case .ambiguousBid: return "Response selected more than one bid"
        case .unmatchedBid: return "Response did not contain a bid for this impression"
        case .expiredBid: return "Selected bid is expired"
        case .invalidPrice: return "Selected bid has an invalid price"
        case .unresolvedMacro: return "Notification URL contains an unresolved macro"
        case .invalidState(let expected, let actual): return "Expected state \(expected), found \(actual)"
        case .creativeTimeout: return "Creative readiness timed out"
        case .destroyed: return "Ad was destroyed"
        }
    }
}

public struct Diagnostic: Sendable, Equatable {
    public enum Level: String, Sendable { case debug, info, warning, error }
    public let level: Level
    public let code: String
    public let message: String
    public let metadata: [String: String]
    public init(level: Level, code: String, message: String, metadata: [String: String] = [:]) {
        self.level = level; self.code = code; self.message = message; self.metadata = metadata
    }
}

public struct Diagnostics: Sendable {
    private let sink: (@Sendable (Diagnostic) -> Void)?
    public static let disabled = Diagnostics()
    public init(_ sink: (@Sendable (Diagnostic) -> Void)? = nil) { self.sink = sink }
    func emit(_ level: Diagnostic.Level, _ code: String, _ message: String, metadata: [String: String] = [:]) {
        sink?(Diagnostic(level: level, code: code, message: message, metadata: metadata))
    }
}
