import Foundation

public final class LoadedCreative: @unchecked Sendable {
    public let markup: String
    public let kind: CreativeKind
    public let format: AdFormat
    public let requestID: String
    public let bidID: String?
    public let expiry: Date?
    public let native: NativePayload?
    public let creativeTimeout: TimeInterval

    private let billingURL: URL?
    private let transport: any HTTPTransport
    private let timeout: TimeInterval
    private let diagnostics: Diagnostics
    private let measurementBackend: (any MeasurementBackend)?
    private let measurementCapabilities: MeasurementCapabilities?
    private let lock = NSLock()
    private var _state: AdState = .ready
    private var billed = false
    private var nativeImpressionSent = false
    private var nativeClickSent = false
    private var notificationTasks: [Task<Void, Never>] = []
    private var measurementClaimed = false
    private var measurementFinish: (@Sendable () -> Void)?

    init(markup: String, kind: CreativeKind, format: AdFormat, requestID: String, bidID: String?, billingURL: URL?,
         expiry: Date?, native: NativePayload?, transport: any HTTPTransport, timeout: TimeInterval, creativeTimeout: TimeInterval,
         diagnostics: Diagnostics, measurementBackend: (any MeasurementBackend)?,
         measurementCapabilities: MeasurementCapabilities?) {
        self.markup = markup; self.kind = kind; self.format = format; self.requestID = requestID; self.bidID = bidID
        self.billingURL = billingURL; self.expiry = expiry; self.native = native
        self.creativeTimeout = creativeTimeout
        self.transport = transport; self.timeout = timeout; self.diagnostics = diagnostics
        self.measurementBackend = measurementBackend
        self.measurementCapabilities = measurementCapabilities
    }

    public var state: AdState { lock.withLock { _state } }

    public func beginDisplay(visibleArea: Double = 1, foreground: Bool = true) async throws {
        try beginDisplayImmediately(visibleArea: visibleArea, foreground: foreground)
    }

    public func beginDisplayImmediately(visibleArea: Double = 1, foreground: Bool = true) throws {
        let billing: URL? = try lock.withLock {
            if _state == .destroyed { throw EngageError.destroyed }
            guard _state == .ready else { throw EngageError.invalidState(expected: AdState.ready.rawValue, actual: _state.rawValue) }
            if let expiry, expiry <= Date() { _state = .failed; throw EngageError.expiredBid }
            guard visibleArea > 0, foreground else { throw EngageError.rendering("Creative is not visible in an active foreground window") }
            _state = .displaying
            guard !billed else { return nil }
            billed = true
            return billingURL
        }
        if let billing {
            let task = Task<Void, Never> { [weak self] in
                guard let self else { return }
                await fire([billing], code: "billing_notice_failed")
            }
            lock.withLock { if _state == .destroyed { task.cancel() } else { notificationTasks.append(task) } }
        }
        diagnostics.emit(.info, "displayed", "Creative display began", metadata: ["kind": kind.rawValue])
    }

    public func finish(success: Bool = true) {
        let transition = lock.withLock { () -> (failed: Bool, finish: (@Sendable () -> Void)?) in
            if success {
                guard _state == .displaying else { return (false, nil) }
                _state = .finished
                let finish = measurementFinish; measurementFinish = nil
                return (false, finish)
            }
            guard _state == .ready || _state == .displaying else { return (false, nil) }
            _state = .failed
            let finish = measurementFinish; measurementFinish = nil
            return (true, finish)
        }
        transition.finish?()
        if transition.failed { diagnostics.emit(.error, "render_failed", "Creative rendering failed", metadata: ["kind": kind.rawValue]) }
    }

    public func reportRenderFailure(_ error: EngageError) {
        let transition = lock.withLock { () -> (failed: Bool, finish: (@Sendable () -> Void)?) in
            guard _state == .ready || _state == .displaying else { return (false, nil) }
            _state = .failed
            let finish = measurementFinish; measurementFinish = nil
            return (true, finish)
        }
        transition.finish?()
        if transition.failed {
            diagnostics.emit(.error, "render_failed", "Creative renderer reported a failure",
                             metadata: ["kind": kind.rawValue, "error": error.diagnosticCode])
        }
    }

    public func destroy() {
        let cleanup = lock.withLock { () -> ([Task<Void, Never>], (@Sendable () -> Void)?) in
            _state = .destroyed
            let tasks = notificationTasks; notificationTasks.removeAll()
            let finish = measurementFinish; measurementFinish = nil
            return (tasks, finish)
        }
        cleanup.0.forEach { $0.cancel() }
        cleanup.1?()
    }

    @MainActor
    public func prepareMeasurement(creativeType: MeasurementCreativeType,
                                   target: any MeasurementTarget) -> Bool {
        let backend: (any MeasurementBackend)? = lock.withLock {
            guard _state == .ready,
                  measurementCapabilities?.supportedTypes.contains(creativeType) == true else { return nil }
            return measurementBackend
        }
        guard let backend else { return false }
        let metadata = MeasurementSessionMetadata(creativeType: creativeType,
                                                  verificationResources: native?.verificationResources ?? [])
        do {
            try backend.prepare(metadata: metadata, target: target)
            return true
        } catch {
            diagnostics.emit(.warning, "measurement_session_failed", "Measurement session operation failed",
                             metadata: ["stage": "prepare", "type": creativeType.rawValue])
            return false
        }
    }

    @MainActor
    public func makeMeasurementSession(creativeType: MeasurementCreativeType,
                                       target: any MeasurementTarget) -> MeasurementSessionLifecycle? {
        let backend: (any MeasurementBackend)? = lock.withLock {
            guard _state == .ready, !measurementClaimed,
                  measurementCapabilities?.supportedTypes.contains(creativeType) == true else { return nil }
            measurementClaimed = true
            return measurementBackend
        }
        guard let backend else { return nil }
        let metadata = MeasurementSessionMetadata(creativeType: creativeType,
                                                  verificationResources: native?.verificationResources ?? [])
        let lifecycle: MeasurementSessionLifecycle
        do {
            let session = try backend.makeSession(metadata: metadata, target: target)
            lifecycle = MeasurementSessionLifecycle(session: session, creativeType: creativeType, diagnostics: diagnostics)
            lifecycle.start()
        } catch {
            diagnostics.emit(.warning, "measurement_session_failed", "Measurement session operation failed",
                             metadata: ["stage": "create", "type": creativeType.rawValue])
            return nil
        }
        let finish: @Sendable () -> Void = { Task { @MainActor in lifecycle.finish() } }
        let accepted = lock.withLock { () -> Bool in
            guard _state == .ready else { return false }
            measurementFinish = finish
            return true
        }
        if !accepted { lifecycle.finish(); return nil }
        return lifecycle
    }

    public func recordNativeImpression() async {
        let urls: [URL] = lock.withLock {
            guard kind == .native, _state == .displaying, !nativeImpressionSent else { return [] }
            nativeImpressionSent = true
            return (native?.impressionTrackers ?? []) + (native?.eventTrackers ?? [])
        }
        await fire(urls, code: "native_impression_tracker_failed")
    }

    public func recordNativeClick() async {
        let urls: [URL] = lock.withLock {
            guard kind == .native, _state == .displaying, !nativeClickSent else { return [] }
            nativeClickSent = true
            return native?.clickTrackers ?? []
        }
        await fire(urls, code: "native_click_tracker_failed")
    }

    private func fire(_ urls: [URL], code: String) async {
        for url in urls {
            guard !Task.isCancelled, state != .destroyed else { return }
            do {
                let response = try await transport.execute(HTTPRequest(url: url, method: .get, timeout: timeout))
                if !(200..<300).contains(response.statusCode) { diagnostics.emit(.warning, code, "A notification request returned HTTP \(response.statusCode)") }
            } catch { diagnostics.emit(.warning, code, "A notification request failed") }
        }
    }
}

public final class EngageClient: @unchecked Sendable {
    private static let maximumRequestBytes = 1 * 1_024 * 1_024
    private static let maximumCreativeResponseBytes = 2 * 1_024 * 1_024
    public let configuration: EngageConfiguration
    private let transport: any HTTPTransport
    private let lock = NSLock()
    private var privacy: Privacy
    private var destroyed = false
    private var generation: UInt64 = 0
    private var activeTasks: [UUID: @Sendable () -> Void] = [:]
    private var creatives: [WeakCreative] = []

    public init(configuration: EngageConfiguration, privacy: Privacy = Privacy(),
                transport: any HTTPTransport = URLSessionTransport()) {
        self.configuration = configuration; self.privacy = privacy; self.transport = transport
    }

    public func updatePrivacy(_ privacy: Privacy) throws {
        try lock.withLock {
            guard !destroyed else { throw EngageError.destroyed }
            self.privacy = privacy
        }
    }

    public func destroy() {
        let cancellations: [@Sendable () -> Void] = lock.withLock {
            destroyed = true; generation &+= 1
            let values = Array(activeTasks.values); activeTasks.removeAll()
            creatives.forEach { $0.value?.destroy() }; creatives.removeAll()
            return values
        }
        cancellations.forEach { $0() }
    }

    public func load(_ request: AdRequest) async throws -> LoadedCreative {
        let started = DispatchTime.now().uptimeNanoseconds
        let endpoint = switch configuration.endpoint {
        case .openRTB26: "openrtb26"
        case .vastTag: "vast"
        }
        let baseMetadata = ["format": request.format.rawValue, "endpoint": endpoint]
        configuration.diagnostics.emit(.info, "load_start", "Creative load started", metadata: baseMetadata)
        do {
            let creative = try await loadImpl(request)
            configuration.diagnostics.emit(.info, "load_success", "Creative load succeeded",
                                           metadata: baseMetadata.merging(["duration_ms": elapsedMilliseconds(since: started),
                                                                           "kind": creative.kind.rawValue]) { _, new in new })
            return creative
        } catch {
            var metadata = baseMetadata
            metadata["duration_ms"] = elapsedMilliseconds(since: started)
            metadata["error"] = (error as? EngageError)?.diagnosticCode ?? "unknown"
            if (error as? EngageError) == .noFill {
                configuration.diagnostics.emit(.info, "no_fill", "Creative request returned no fill", metadata: metadata)
            } else {
                configuration.diagnostics.emit(.error, "load_failed", "Creative load failed", metadata: metadata)
            }
            throw error
        }
    }

    private func loadImpl(_ request: AdRequest) async throws -> LoadedCreative {
        let (snapshot, currentGeneration): (Privacy, UInt64) = try lock.withLock {
            guard !destroyed else { throw EngageError.destroyed }
            return (privacy, generation)
        }
        let result: LoadedCreative
        switch configuration.endpoint {
        case .openRTB26(let url, let headers):
            let built = try OpenRTBRequestBuilder.build(configuration: configuration, privacy: snapshot, request: request)
            guard built.body.count <= Self.maximumRequestBytes else {
                throw EngageError.invalidRequest("OpenRTB request exceeds the size limit")
            }
            var requestHeaders = headers
            requestHeaders["content-type"] = "application/json"
            requestHeaders["x-openrtb-version"] = "2.6"
            let response = try await perform(HTTPRequest(url: url, method: .post, headers: requestHeaders,
                                                         body: built.body, timeout: configuration.requestTimeout))
            guard response.body.count <= Self.maximumCreativeResponseBytes else {
                throw EngageError.malformedResponse("OpenRTB response exceeds the size limit")
            }
            let bid: ParsedBid
            do {
                bid = try OpenRTBResponseParser.parse(
                    response, requestID: built.id, impressionID: built.impressionID, request: request,
                    diagnostics: configuration.diagnostics,
                    measurementCapabilities: configuration.measurementCapabilities,
                    supportsIMAVideoOMID: configuration.deviceCategory == .mobile
                )
            }
            catch EngageError.unresolvedMacro {
                configuration.diagnostics.emit(.warning, "unresolved_notice_macro", "A notice URL contains an unresolved macro")
                throw EngageError.unresolvedMacro
            }
            let markup: String
            if let inline = bid.markup, !inline.isEmpty {
                markup = inline
                if let win = bid.nurl { Task { [weak self] in await self?.notify(win, code: "win_notice_failed") } }
            } else if let markupURL = bid.nurl {
                markup = try await fetchMarkup(markupURL)
            } else { throw EngageError.malformedResponse("Selected bid has no creative markup") }
            let kind: CreativeKind = request.format == .native ? .native :
                ([AdFormat.instream, .rewarded].contains(request.format) || (request.format == .interstitial && request.video != nil) ? .vast : .html)
            if kind == .vast { try VASTValidator.validate(markup) }
            let native: NativePayload?
            if request.format == .native {
                native = try bid.native ?? OpenRTBResponseParser.parseNative(
                    markup, requested: request.nativeAssets, defaultVideo: request.video,
                    diagnostics: configuration.diagnostics
                )
                if bid.requiredAPIs.contains(7), let native {
                    let containsVideo = native.assets.contains(where: { if case .video = $0.value { return true }; return false })
                    let supported = containsVideo ? configuration.deviceCategory == .mobile :
                        configuration.measurementCapabilities?.supportedTypes.contains(.native) == true
                    guard supported else {
                        throw EngageError.unsupported("Selected bid requires an unavailable rendering API")
                    }
                }
            }
            else { native = nil }
            result = LoadedCreative(markup: markup, kind: kind, format: request.format, requestID: built.id, bidID: bid.bidID,
                                    billingURL: bid.burl, expiry: bid.expiry, native: native,
                                    transport: transport, timeout: configuration.requestTimeout, creativeTimeout: configuration.creativeTimeout,
                                    diagnostics: configuration.diagnostics, measurementBackend: configuration.measurementBackend,
                                    measurementCapabilities: configuration.measurementCapabilities)
        case .vastTag(let baseURL, let parameters):
            guard request.format == .instream || request.format == .rewarded || (request.format == .interstitial && request.video != nil) else {
                throw EngageError.unsupported("Direct VAST accepts instream, rewarded, and video interstitial requests")
            }
            guard configuration.deviceCategory != .tv || request.format == .instream else {
                throw EngageError.unsupported("tvOS accepts instream requests only")
            }
            let url = try Self.vastURL(baseURL, parameters: parameters, configuration: configuration, privacy: snapshot)
            let markup = try await fetchMarkup(url)
            try VASTValidator.validate(markup)
            result = LoadedCreative(markup: markup, kind: .vast, format: request.format, requestID: UUID().uuidString, bidID: nil,
                                    billingURL: nil, expiry: nil, native: nil, transport: transport,
                                    timeout: configuration.requestTimeout, creativeTimeout: configuration.creativeTimeout, diagnostics: configuration.diagnostics,
                                    measurementBackend: nil, measurementCapabilities: nil)
        }
        try lock.withLock {
            guard !destroyed, generation == currentGeneration else { result.destroy(); throw EngageError.destroyed }
            creatives.removeAll { $0.value == nil }; creatives.append(WeakCreative(result))
        }
        return result
    }

    static func vastURL(_ base: URL, parameters: [String: String]) throws -> URL {
        try vastURL(base, parameters: parameters, configuration: nil, privacy: Privacy())
    }

    static func vastURL(_ base: URL, parameters: [String: String], configuration: EngageConfiguration?, privacy: Privacy) throws -> URL {
        let ifa = privacy.limitAdTracking == true ? nil : privacy.advertisingID
        let cacheBuster = String(Int.random(in: 10_000_000...99_999_999))
        let os = configuration?.device.operatingSystem
        let osv = configuration?.device.operatingSystemVersion
        var values: [String: String] = [
            "CACHEBUSTING": cacheBuster, "CACHEBUSTER": cacheBuster,
            "TIMESTAMP": ISO8601DateFormatter().string(from: Date()),
        ]
        if let value = privacy.gdprApplies { values["GDPR"] = value ? "1" : "0" }
        if let value = privacy.consentString { values["GDPR_CONSENT"] = value }
        if let value = privacy.usPrivacyString { values["US_PRIVACY"] = value }
        if let value = privacy.gppString { values["GPP_STRING"] = value }
        if !privacy.gppSectionIDs.isEmpty { values["GPP_SID"] = privacy.gppSectionIDs.map(String.init).joined(separator: ",") }
        if let ifa { values["IFA"] = ifa }
        if let value = privacy.limitAdTracking { values["LIMITADTRACKING"] = value ? "1" : "0" }
        if let value = configuration?.app.bundle { values["APPBUNDLE"] = value }
        if let value = configuration?.app.name { values["APPNAME"] = value }
        if let value = configuration?.app.storeURL?.absoluteString { values["STOREURL"] = value }
        if let value = configuration?.device.userAgent { values["DEVICEUA"] = value }
        if let os { values["DEVICEOS"] = osv.map { "\(os) \($0)" } ?? os }
        if let value = configuration?.device.make { values["DEVICEMAKE"] = value }
        if let value = configuration?.device.model { values["DEVICEMODEL"] = value }
        if let configuration { values["DEVICETYPE"] = configuration.deviceCategory == .tv ? "3" : "4" }
        let supported = Set(["CACHEBUSTING", "CACHEBUSTER", "TIMESTAMP", "GDPR", "GDPR_CONSENT", "US_PRIVACY",
                             "GPP_STRING", "GPP_SID", "IFA", "LIMITADTRACKING", "APPBUNDLE", "APPNAME", "STOREURL",
                             "DEVICEUA", "DEVICEOS", "DEVICEMAKE", "DEVICEMODEL", "DEVICETYPE"])
        func expand(_ input: String) throws -> String? {
            for name in supported where input.contains("[\(name)]") && values[name] == nil { return nil }
            var value = input
            for (name, replacement) in values {
                value = value.replacingOccurrences(of: "[\(name)]", with: replacement)
            }
            if value.range(of: #"\[[A-Z][A-Z0-9_]*\]|\$\{[^}]+\}"#, options: .regularExpression) != nil {
                throw EngageError.unresolvedMacro
            }
            return value
        }
        guard var parts = URLComponents(url: base, resolvingAgainstBaseURL: false) else { throw EngageError.invalidRequest("Invalid VAST URL") }
        let identityKeys = Set(["ifa", "idfa", "adid", "advertising_id"])
        var items: [URLQueryItem] = []
        for item in parts.queryItems ?? [] {
            if privacy.limitAdTracking == true, identityKeys.contains(item.name.lowercased()) { continue }
            if let raw = item.value {
                if let value = try expand(raw) { items.append(URLQueryItem(name: item.name, value: value)) }
            } else { items.append(item) }
        }
        for (name, raw) in parameters.sorted(by: { $0.key < $1.key }) {
            if privacy.limitAdTracking == true, identityKeys.contains(name.lowercased()) { continue }
            if let value = try expand(raw) { items.append(URLQueryItem(name: name, value: value)) }
        }
        parts.queryItems = items.isEmpty ? nil : items
        guard let url = parts.url else { throw EngageError.invalidRequest("Invalid VAST parameters") }
        return url
    }

    private func fetchMarkup(_ url: URL) async throws -> String {
        let response = try await perform(HTTPRequest(url: url, method: .get, timeout: configuration.requestTimeout))
        if response.statusCode == 204 { throw EngageError.noFill }
        guard response.body.count <= Self.maximumCreativeResponseBytes else {
            throw EngageError.malformedResponse("Creative markup response exceeds the size limit")
        }
        guard (200..<300).contains(response.statusCode), !response.body.isEmpty,
              let markup = String(data: response.body, encoding: .utf8), !markup.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw EngageError.malformedResponse("Creative markup response is empty or invalid")
        }
        return markup
    }

    private func notify(_ url: URL, code: String) async {
        do {
            let response = try await perform(HTTPRequest(url: url, method: .get, timeout: configuration.requestTimeout))
            if !(200..<300).contains(response.statusCode) { configuration.diagnostics.emit(.warning, code, "A notification request returned HTTP \(response.statusCode)") }
        }
        catch { configuration.diagnostics.emit(.warning, code, "A notification request failed") }
    }

    private func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        let id = UUID()
        let task = Task { try await transport.execute(request) }
        let accepted = lock.withLock { () -> Bool in
            guard !destroyed else { return false }
            activeTasks[id] = { task.cancel() }; return true
        }
        guard accepted else { task.cancel(); throw EngageError.destroyed }
        defer { _ = lock.withLock { activeTasks.removeValue(forKey: id) } }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }

    private func elapsedMilliseconds(since started: UInt64) -> String {
        String((DispatchTime.now().uptimeNanoseconds - started) / 1_000_000)
    }
}

private final class WeakCreative: @unchecked Sendable {
    weak var value: LoadedCreative?
    init(_ value: LoadedCreative) { self.value = value }
}

private extension EngageError {
    var diagnosticCode: String {
        switch self {
        case .invalidRequest: "invalid_request"
        case .unsupported: "unsupported"
        case .noFill: "no_fill"
        case .transport: "transport"
        case .malformedResponse: "malformed_response"
        case .ambiguousBid: "ambiguous_bid"
        case .unmatchedBid: "unmatched_bid"
        case .expiredBid: "expired_bid"
        case .invalidPrice: "invalid_price"
        case .unresolvedMacro: "unresolved_macro"
        case .invalidState: "invalid_state"
        case .creativeTimeout: "creative_timeout"
        case .destroyed: "destroyed"
        case .rendering: "rendering"
        }
    }
}
