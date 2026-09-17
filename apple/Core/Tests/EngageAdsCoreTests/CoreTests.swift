import Foundation
import Testing
@testable import EngageAdsCore

final class HandlerTransport: HTTPTransport, @unchecked Sendable {
    let handler: @Sendable (HTTPRequest) async throws -> HTTPResponse
    init(_ handler: @escaping @Sendable (HTTPRequest) async throws -> HTTPResponse) { self.handler = handler }
    func execute(_ request: HTTPRequest) async throws -> HTTPResponse { try await handler(request) }
}

final class URLRecorder: @unchecked Sendable {
    private let lock = NSLock(); private var values: [URL] = []
    func add(_ url: URL) { lock.withLock { values.append(url) } }
    func count(_ path: String) -> Int { lock.withLock { values.filter { $0.path == path }.count } }
}

final class RequestRecorder: @unchecked Sendable {
    private let lock = NSLock(); private var values: [HTTPRequest] = []
    func add(_ request: HTTPRequest) { lock.withLock { values.append(request) } }
    var last: HTTPRequest? { lock.withLock { values.last } }
    var all: [HTTPRequest] { lock.withLock { values } }
}

final class DiagnosticRecorder: @unchecked Sendable {
    private let lock = NSLock(); private var values: [Diagnostic] = []
    func add(_ diagnostic: Diagnostic) { lock.withLock { values.append(diagnostic) } }
    func all() -> [Diagnostic] { lock.withLock { values } }
}

final class FakeMeasurementTarget: MeasurementTarget, @unchecked Sendable {}

@MainActor
final class FakeMeasurementSession: MeasurementBackendSession {
    var events: [String] = []
    var throwingStage: String?
    var reenter: ((String) -> Void)?

    func start() throws { try record("start") }
    func loaded() throws { try record("loaded") }
    func impression() throws { try record("impression") }
    func finish() throws { try record("finish") }

    private func record(_ event: String) throws {
        events.append(event)
        reenter?(event)
        if throwingStage == event { throw EngageError.rendering("private backend detail") }
    }
}

final class FakeMeasurementBackend: MeasurementBackend, @unchecked Sendable {
    let capabilities: MeasurementCapabilities
    @MainActor var session: FakeMeasurementSession?
    @MainActor var prepared = 0
    @MainActor var preparationError = false
    var capabilityError = false

    init(types: Set<MeasurementCreativeType>, name: String = "partner", version: String = "1") {
        capabilities = MeasurementCapabilities(ready: true, partner: MeasurementPartner(name: name, version: version),
                                               supportedTypes: types)
    }

    func capabilitySnapshot() throws -> MeasurementCapabilities {
        if capabilityError { throw EngageError.unsupported("private capability detail") }
        return capabilities
    }

    @MainActor func prepare(metadata: MeasurementSessionMetadata, target: any MeasurementTarget) throws {
        prepared += 1
        if preparationError { throw EngageError.rendering("private preparation detail") }
    }

    @MainActor func makeSession(metadata: MeasurementSessionMetadata,
                                target: any MeasurementTarget) throws -> any MeasurementBackendSession {
        let created = FakeMeasurementSession(); session = created; return created
    }
}

final class AsyncLatch<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value?
    private var waiters: [CheckedContinuation<Value, Never>] = []

    func signal(_ value: Value) {
        let pending = lock.withLock { () -> [CheckedContinuation<Value, Never>] in
            guard self.value == nil else { return [] }
            self.value = value
            let pending = waiters
            waiters.removeAll()
            return pending
        }
        pending.forEach { $0.resume(returning: value) }
    }

    func wait() async -> Value {
        await withCheckedContinuation { continuation in
            let immediate = lock.withLock { () -> Value? in
                if let value { return value }
                waiters.append(continuation)
                return nil
            }
            if let immediate { continuation.resume(returning: immediate) }
        }
    }
}

final class CancellationProbeTransport: HTTPTransport, @unchecked Sendable {
    private enum State {
        case idle
        case suspended(CheckedContinuation<HTTPResponse, any Error>)
        case cancelled
    }

    let started = AsyncLatch<HTTPRequest>()
    let cancellationObserved = AsyncLatch<Void>()
    private let lock = NSLock()
    private var state: State = .idle

    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        started.signal(request)
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                let cancelImmediately = lock.withLock { () -> Bool in
                    if case .cancelled = state { return true }
                    state = .suspended(continuation)
                    return false
                }
                if cancelImmediately { continuation.resume(throwing: CancellationError()) }
            }
        } onCancel: {
            let continuation = self.lock.withLock { () -> CheckedContinuation<HTTPResponse, any Error>? in
                let continuation: CheckedContinuation<HTTPResponse, any Error>?
                if case .suspended(let pending) = self.state { continuation = pending }
                else { continuation = nil }
                self.state = .cancelled
                return continuation
            }
            self.cancellationObserved.signal(())
            continuation?.resume(throwing: CancellationError())
        }
    }
}

final class ManualResponseTransport: HTTPTransport, @unchecked Sendable {
    let requests: AsyncStream<HTTPRequest>
    private let requestContinuation: AsyncStream<HTTPRequest>.Continuation
    private let lock = NSLock()
    private var pending: [CheckedContinuation<HTTPResponse, Never>] = []

    init() {
        let pair = AsyncStream.makeStream(of: HTTPRequest.self)
        requests = pair.stream
        requestContinuation = pair.continuation
    }

    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        await withCheckedContinuation { continuation in
            lock.withLock { pending.append(continuation) }
            requestContinuation.yield(request)
        }
    }

    func completeNext(with response: HTTPResponse) {
        let continuation = lock.withLock { pending.removeFirst() }
        continuation.resume(returning: response)
    }
}

final class HangingURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { }
    override func stopLoading() { }
}

final class StreamingMetrics: @unchecked Sendable {
    private let lock = NSLock()
    private var _chunks = 0
    private var _stops = 0
    func sentChunk() { lock.withLock { _chunks += 1 } }
    func stopped() { lock.withLock { _stops += 1 } }
    var chunks: Int { lock.withLock { _chunks } }
    var stops: Int { lock.withLock { _stops } }
}

final class UnknownLengthStreamingURLProtocol: URLProtocol, @unchecked Sendable {
    static let metrics = StreamingMetrics()
    static let totalChunks = 4_096
    private let stateLock = NSLock()
    private var stopped = false
    private var sent = 0

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        sendNextChunk()
    }
    override func stopLoading() {
        let firstStop = stateLock.withLock { () -> Bool in
            guard !stopped else { return false }
            stopped = true
            return true
        }
        if firstStop { Self.metrics.stopped() }
    }

    private func sendNextChunk() {
        DispatchQueue.global().async { [weak self] in
            guard let self else { return }
            let shouldSend = stateLock.withLock { () -> Bool in
                guard !stopped, sent < Self.totalChunks else { return false }
                sent += 1
                return true
            }
            guard shouldSend else { return }
            Self.metrics.sentChunk()
            client?.urlProtocol(self, didLoad: Data(repeating: 0x61, count: 256))
            let finished = stateLock.withLock { sent == Self.totalChunks && !stopped }
            if finished { client?.urlProtocolDidFinishLoading(self) }
            else { sendNextChunk() }
        }
    }
}

final class CancellableStreamingURLProtocol: URLProtocol, @unchecked Sendable {
    static let started = AsyncLatch<Void>()
    static let metrics = StreamingMetrics()
    static let totalChunks = 100_000
    private let stateLock = NSLock()
    private var stopped = false
    private var sent = 0

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        sendNextChunk()
    }
    override func stopLoading() {
        let firstStop = stateLock.withLock { () -> Bool in
            guard !stopped else { return false }
            stopped = true
            return true
        }
        if firstStop { Self.metrics.stopped() }
    }

    private func sendNextChunk() {
        DispatchQueue.global().async { [weak self] in
            guard let self else { return }
            let shouldSend = stateLock.withLock { () -> Bool in
                guard !stopped, sent < Self.totalChunks else { return false }
                sent += 1
                return true
            }
            guard shouldSend else { return }
            Self.metrics.sentChunk(); Self.started.signal(())
            client?.urlProtocol(self, didLoad: Data(repeating: 0x61, count: 256))
            let finished = stateLock.withLock { sent == Self.totalChunks && !stopped }
            if finished { client?.urlProtocolDidFinishLoading(self) }
            else { sendNextChunk() }
        }
    }
}

private func decodeRequestBody(_ request: HTTPRequest) throws -> [String: Any] {
    guard let body = request.body,
          let root = try JSONSerialization.jsonObject(with: body) as? [String: Any] else {
        throw EngageError.invalidRequest("Test request body is missing or malformed")
    }
    return root
}

private func makeSuccessResponse(for request: HTTPRequest, billingURL: String? = nil) throws -> HTTPResponse {
    let root = try decodeRequestBody(request)
    guard let requestID = root["id"] as? String,
          let impressionID = (root["imp"] as? [[String: Any]])?.first?["id"] as? String else {
        throw EngageError.invalidRequest("Test request identifiers are missing")
    }
    let billing = billingURL.map { ",\"burl\":\"\($0)\"" } ?? ""
    return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(requestID)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(impressionID)\",\"price\":0,\"adm\":\"<p>ad</p>\"\(billing)}]}]}".utf8))
}

@Suite struct EngageAdsCoreTests {
    let endpoint = URL(string: "https://ads.example/openrtb")!

    @Test func requestShape() throws {
        let config = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.example.app"), deviceCategory: .mobile,
            device: DeviceMetadata(operatingSystem: "iOS", operatingSystemVersion: "18", userAgent: "ua", make: "Apple", model: "Phone", advertisingID: "host-ifa"))
        let privacy = Privacy(gdprApplies: true, consentString: "consent", gppString: "gpp", gppSectionIDs: [2, 7], isChildDirected: false,
                              limitAdTracking: true, usPrivacyString: "1YNN")
        let built = try OpenRTBRequestBuilder.build(configuration: config, privacy: privacy,
            request: AdRequest(placementID: "p", format: .instream, video: VideoConstraints(podDuration: 90, maximumAds: 3)), requestID: "req", impressionID: "imp")
        let root = try #require(JSONSerialization.jsonObject(with: built.body) as? [String: Any])
        let regs = try #require(root["regs"] as? [String: Any]); #expect(regs["gdpr"] as? Int == 1); #expect(regs["gpp_sid"] as? [Int] == [2, 7]); #expect(regs["us_privacy"] as? String == "1YNN"); #expect(regs["ext"] == nil)
        let device = try #require(root["device"] as? [String: Any]); #expect(device["lmt"] as? Int == 1); #expect(device["ifa"] == nil); #expect(device["js"] as? Int == 1)
        #expect((root["user"] as? [String: Any])?["consent"] as? String == "consent")
        let imp = try #require((root["imp"] as? [[String: Any]])?.first); let video = try #require(imp["video"] as? [String: Any])
        #expect(video["poddur"] as? Int == 90); #expect(video["maxseq"] as? Int == 3); #expect(video["protocols"] as? [Int] == [2,3,5,6,7,8,11,12,13,14]); #expect(video["linearity"] as? Int == 1)
    }

    @Test func privacyUpdateWithdrawsIFAForOpenRTBAndVAST() async throws {
        let configuredDevice = DeviceMetadata(advertisingID: "ignored-device-ifa")
        let openRTBRecorder = RequestRecorder()
        let openRTBTransport = HandlerTransport { request in
            openRTBRecorder.add(request)
            let body = try #require(request.body)
            let root = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            let requestID = try #require(root["id"] as? String)
            let impressionID = try #require((root["imp"] as? [[String: Any]])?.first?["id"] as? String)
            return HTTPResponse(statusCode: 200, body: Data(#"{"id":"\#(requestID)","seatbid":[{"bid":[{"id":"b","impid":"\#(impressionID)","price":0,"adm":"<p>ad</p>"}]}]}"#.utf8))
        }
        let openRTBConfig = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                                deviceCategory: .mobile, device: configuredDevice)
        let openRTBClient = EngageClient(configuration: openRTBConfig, privacy: PrivacyContext(advertisingID: "allowed-ifa"),
                                         transport: openRTBTransport)
        _ = try await openRTBClient.load(bannerRequest())
        try openRTBClient.updatePrivacy(PrivacyContext())
        _ = try await openRTBClient.load(bannerRequest())
        let openRTBBodies = try openRTBRecorder.all.map { request -> [String: Any] in
            let body = try #require(request.body)
            return try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
        }
        #expect((openRTBBodies[0]["device"] as? [String: Any])?["ifa"] as? String == "allowed-ifa")
        #expect((openRTBBodies[1]["device"] as? [String: Any])?["ifa"] == nil)

        let vastRecorder = RequestRecorder()
        let vast = "<VAST version=\"4.2\"><Ad id=\"1\"><InLine><AdSystem>x</AdSystem><AdTitle>x</AdTitle><Impression>https://t.test/i</Impression><Creatives/></InLine></Ad></VAST>"
        let vastTransport = HandlerTransport { request in
            vastRecorder.add(request)
            return HTTPResponse(statusCode: 200, body: Data(vast.utf8))
        }
        let vastConfig = EngageConfiguration(endpoint: .vastTag(url: URL(string: "https://ads.test/tag?ifa=[IFA]")!),
                                             app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile,
                                             device: configuredDevice)
        let vastClient = EngageClient(configuration: vastConfig, privacy: PrivacyContext(advertisingID: "allowed-ifa"), transport: vastTransport)
        _ = try await vastClient.load(AdRequest(placementID: "p", format: .instream))
        try vastClient.updatePrivacy(PrivacyContext())
        _ = try await vastClient.load(AdRequest(placementID: "p", format: .instream))
        #expect(URLComponents(url: vastRecorder.all[0].url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value == "allowed-ifa")
        #expect(URLComponents(url: vastRecorder.all[1].url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "ifa" }) == nil)
    }

    @Test func privacyIsSnapshottedBeforeAnInflightRequestCompletes() async throws {
        let transport = ManualResponseTransport()
        var requests = transport.requests.makeAsyncIterator()
        let client = EngageClient(configuration: config(),
                                  privacy: PrivacyContext(consentString: "old-consent", gppString: "old-gpp", advertisingID: "old-ifa"),
                                  transport: transport)

        let firstLoad = Task { try await client.load(bannerRequest()) }
        let firstRequest = try #require(await requests.next())
        let firstBody = try decodeRequestBody(firstRequest)
        #expect((firstBody["user"] as? [String: Any])?["consent"] as? String == "old-consent")
        #expect((firstBody["regs"] as? [String: Any])?["gpp"] as? String == "old-gpp")
        #expect((firstBody["device"] as? [String: Any])?["ifa"] as? String == "old-ifa")

        try client.updatePrivacy(PrivacyContext(consentString: "new-consent", gppString: "new-gpp", advertisingID: "new-ifa"))
        transport.completeNext(with: try makeSuccessResponse(for: firstRequest))
        _ = try await firstLoad.value

        let secondLoad = Task { try await client.load(bannerRequest()) }
        let secondRequest = try #require(await requests.next())
        let secondBody = try decodeRequestBody(secondRequest)
        #expect((secondBody["user"] as? [String: Any])?["consent"] as? String == "new-consent")
        #expect((secondBody["regs"] as? [String: Any])?["gpp"] as? String == "new-gpp")
        #expect((secondBody["device"] as? [String: Any])?["ifa"] as? String == "new-ifa")
        transport.completeNext(with: try makeSuccessResponse(for: secondRequest))
        _ = try await secondLoad.value
    }

    @Test func destroyCancelsInflightLoadAndRejectsNoncooperativeStaleCompletion() async throws {
        let cancellationTransport = CancellationProbeTransport()
        let cancellingClient = EngageClient(configuration: config(), transport: cancellationTransport)
        let cancelledLoad = Task { try await cancellingClient.load(bannerRequest()) }
        _ = await cancellationTransport.started.wait()
        cancellingClient.destroy()
        await cancellationTransport.cancellationObserved.wait()
        do {
            _ = try await cancelledLoad.value
            Issue.record("Destroyed client unexpectedly completed its inflight load")
        } catch is CancellationError { }
        catch { Issue.record("Inflight cancellation returned unexpected error: \(error)") }

        let staleTransport = ManualResponseTransport()
        var staleRequests = staleTransport.requests.makeAsyncIterator()
        let staleClient = EngageClient(configuration: config(), transport: staleTransport)
        let staleLoad = Task { try await staleClient.load(bannerRequest()) }
        let staleRequest = try #require(await staleRequests.next())
        staleClient.destroy()
        staleTransport.completeNext(with: try makeSuccessResponse(for: staleRequest))
        await #expect(throws: EngageError.destroyed) { try await staleLoad.value }
    }

    @Test func failedBillingNoticeIsAttemptedOnceAndDiagnosed() async throws {
        let recorder = URLRecorder()
        let diagnosticRecorder = DiagnosticRecorder()
        let billingDiagnostic = AsyncLatch<Diagnostic>()
        let configuration = EngageConfiguration(
            endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile,
            diagnostics: Diagnostics { diagnostic in
                diagnosticRecorder.add(diagnostic)
                if diagnostic.code == "billing_notice_failed" { billingDiagnostic.signal(diagnostic) }
            })
        let transport = HandlerTransport { request in
            recorder.add(request.url)
            if request.method == .post {
                return try makeSuccessResponse(for: request, billingURL: "https://billing.test/uncertain")
            }
            throw EngageError.transport("Simulated uncertain delivery")
        }
        let creative = try await EngageClient(configuration: configuration, transport: transport).load(bannerRequest())
        #expect(recorder.count("/uncertain") == 0)
        try creative.beginDisplayImmediately()
        let diagnostic = await billingDiagnostic.wait()
        #expect(diagnostic.level == .warning)
        #expect(diagnostic.message == "A notification request failed")
        #expect(diagnostic.metadata.isEmpty)
        #expect(recorder.count("/uncertain") == 1)
        #expect(diagnosticRecorder.all().filter { $0.code == "billing_notice_failed" }.count == 1)
        #expect(throws: (any Error).self) { try creative.beginDisplayImmediately() }
        #expect(recorder.count("/uncertain") == 1)
    }

    @Test func configuredEndpointsAreUsedWithoutSubstitution() async throws {
        let openRTBEndpoint = URL(string: "https://publisher.invalid:9443/custom/openrtb?account=abc")!
        let openRTBRecorder = RequestRecorder()
        let openRTBTransport = HandlerTransport { request in
            openRTBRecorder.add(request)
            return try makeSuccessResponse(for: request)
        }
        let openRTBClient = EngageClient(configuration: EngageConfiguration(
            endpoint: .openRTB26(url: openRTBEndpoint, headers: ["x-publisher": "explicit"]),
            app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile), transport: openRTBTransport)
        _ = try await openRTBClient.load(bannerRequest())
        #expect(openRTBRecorder.all.count == 1)
        #expect(openRTBRecorder.last?.url == openRTBEndpoint)
        #expect(openRTBRecorder.last?.headers["x-publisher"] == "explicit")

        let vastEndpoint = URL(string: "https://publisher.invalid:9443/custom/vast?account=abc")!
        let vastRecorder = RequestRecorder()
        let vast = "<VAST version=\"4.2\"><Ad id=\"1\"><InLine><AdSystem>x</AdSystem><AdTitle>x</AdTitle><Impression>https://track.invalid/i</Impression><Creatives/></InLine></Ad></VAST>"
        let vastClient = EngageClient(configuration: EngageConfiguration(
            endpoint: .vastTag(url: vastEndpoint, parameters: ["slot": "explicit"]),
            app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile),
            transport: HandlerTransport { request in vastRecorder.add(request); return HTTPResponse(statusCode: 200, body: Data(vast.utf8)) })
        _ = try await vastClient.load(AdRequest(placementID: "p", format: .instream))
        let requestedVAST = try #require(vastRecorder.last?.url)
        #expect(requestedVAST.host == vastEndpoint.host)
        #expect(requestedVAST.port == vastEndpoint.port)
        #expect(requestedVAST.path == vastEndpoint.path)
        let query = try #require(URLComponents(url: requestedVAST, resolvingAgainstBaseURL: false)?.queryItems)
        #expect(query.first(where: { $0.name == "account" })?.value == "abc")
        #expect(query.first(where: { $0.name == "slot" })?.value == "explicit")
    }

    @Test func urlSessionTransportEnforcesTheRequestTimeout() async {
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [HangingURLProtocol.self]
        let transport = URLSessionTransport(session: URLSession(configuration: sessionConfiguration))
        await #expect(throws: EngageError.transport("Network request failed")) {
            try await transport.execute(HTTPRequest(url: URL(string: "https://timeout.invalid/request")!,
                                                    method: .get, timeout: 0.05))
        }
    }

    @Test func streamingTransportCancelsAnUnknownLengthOverLimitResponse() async {
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [UnknownLengthStreamingURLProtocol.self]
        let transport = URLSessionTransport(session: URLSession(configuration: sessionConfiguration), maximumResponseBytes: 1_024)
        await #expect(throws: EngageError.transport("HTTP response exceeds the configured size limit")) {
            try await transport.execute(HTTPRequest(url: URL(string: "https://stream.test/unknown")!, method: .get, timeout: 2))
        }
        #expect(UnknownLengthStreamingURLProtocol.metrics.chunks < UnknownLengthStreamingURLProtocol.totalChunks)
    }

    @Test func callerCancellationStopsAnActiveURLSessionStream() async {
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [CancellableStreamingURLProtocol.self]
        let transport = URLSessionTransport(session: URLSession(configuration: sessionConfiguration))
        let task = Task {
            try await transport.execute(HTTPRequest(url: URL(string: "https://stream.test/cancel")!, method: .get, timeout: 2))
        }
        await CancellableStreamingURLProtocol.started.wait()
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(CancellableStreamingURLProtocol.metrics.chunks < CancellableStreamingURLProtocol.totalChunks)
    }

    @Test func boundedRemoteInputsAndInvalidTimeoutsAreHandledWithoutTraps() async throws {
        let sanitized = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                            deviceCategory: .mobile, requestTimeout: .nan,
                                            creativeTimeout: .infinity)
        #expect(sanitized.requestTimeout == 5)
        #expect(sanitized.creativeTimeout == 15)

        let oversizedTransport = HandlerTransport { request in
            if request.method == .post { return HTTPResponse(statusCode: 200, body: Data(repeating: 0x20, count: 2 * 1_024 * 1_024 + 1)) }
            return HTTPResponse(statusCode: 200)
        }
        let client = EngageClient(configuration: config(), transport: oversizedTransport)
        await #expect(throws: EngageError.malformedResponse("OpenRTB response exceeds the size limit")) {
            try await client.load(bannerRequest())
        }
        await #expect(throws: EngageError.invalidRequest("OpenRTB request exceeds the size limit")) {
            try await client.load(AdRequest(placementID: "p", format: .banner, size: AdSize(width: 320, height: 50),
                                            ext: ["oversized": .string(String(repeating: "x", count: 1_024 * 1_024))]))
        }

        let deepJSON = Data((String(repeating: "[", count: 65) + String(repeating: "]", count: 65)).utf8)
        #expect(throws: EngageError.malformedResponse("OpenRTB response exceeds the nesting limit")) {
            try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: deepJSON), requestID: "r", impressionID: "i", request: bannerRequest())
        }
        let deepVAST = "<VAST>" + String(repeating: "<x>", count: 64) + "<Ad/>" + String(repeating: "</x>", count: 64) + "</VAST>"
        #expect(throws: (any Error).self) { try VASTValidator.validate(deepVAST) }
        let oversizedVAST = "<VAST><Ad>" + String(repeating: "x", count: 1_024 * 1_024) + "</Ad></VAST>"
        #expect(throws: EngageError.malformedResponse("VAST markup exceeds the size limit")) { try VASTValidator.validate(oversizedVAST) }
        let entityVAST = "<!DOCTYPE VAST [<!ENTITY x 'expanded'>]><VAST><Ad>&x;</Ad></VAST>"
        #expect(throws: EngageError.malformedResponse("VAST document type and entity declarations are not supported")) {
            try VASTValidator.validate(entityVAST)
        }

        let tooManyAssets = (0...64).map { NativeAssetRequest(id: $0, kind: .title(maxLength: 10)) }
        #expect(throws: EngageError.invalidRequest("Native request exceeds the asset limit")) {
            try OpenRTBRequestBuilder.build(configuration: config(), privacy: Privacy(),
                                            request: AdRequest(placementID: "p", format: .native, nativeAssets: tooManyAssets))
        }
        let trackers = (0...128).map { "\"https://track.test/\($0)\"" }.joined(separator: ",")
        let tooManyTrackers = "{\"assets\":[{\"id\":0,\"title\":{\"text\":\"Title\"}}],\"link\":{\"url\":\"https://click.test\",\"clicktrackers\":[\(trackers)]}}"
        #expect(throws: EngageError.malformedResponse("Native click tracker URL list exceeds the limit")) {
            try OpenRTBResponseParser.parseNative(tooManyTrackers,
                                                  requested: [NativeAssetRequest(id: 0, required: true, kind: .title(maxLength: 20))])
        }
    }

    @Test func formatSignalsAndMRAIDCapability() throws {
        func imp(_ request: AdRequest) throws -> [String: Any] {
            let built = try OpenRTBRequestBuilder.build(configuration: config(), privacy: Privacy(), request: request,
                                                        requestID: "r", impressionID: "i")
            let root = try #require(JSONSerialization.jsonObject(with: built.body) as? [String: Any])
            return try #require((root["imp"] as? [[String: Any]])?.first)
        }
        let banner = try imp(bannerRequest())
        let bannerObject = try #require(banner["banner"] as? [String: Any])
        #expect(bannerObject["api"] as? [Int] == [6])
        #expect(bannerObject["mimes"] as? [String] == ["text/html", "application/xhtml+xml"])

        let videoInterstitial = try imp(AdRequest(placementID: "p", format: .interstitial, video: VideoConstraints()))
        #expect(videoInterstitial["instl"] as? Int == 1)
        #expect((videoInterstitial["video"] as? [String: Any])?["plcmt"] as? Int == 3)
        let rewarded = try imp(AdRequest(placementID: "p", format: .rewarded))
        #expect(rewarded["instl"] as? Int == 1); #expect(rewarded["rwdd"] as? Int == 1)
    }

    @Test func validatesVideoAndNativeRequestSemantics() throws {
        #expect(NativeAssetRequest(id: 7, kind: .data(type: 2, maxLength: 10)).required == false)
        let defaults = try OpenRTBRequestBuilder.build(configuration: config(), privacy: Privacy(),
            request: AdRequest(placementID: "p", format: .native), requestID: "r", impressionID: "i")
        let root = try #require(JSONSerialization.jsonObject(with: defaults.body) as? [String: Any])
        let imp = try #require((root["imp"] as? [[String: Any]])?.first)
        let native = try #require(imp["native"] as? [String: Any]); let request = try #require(native["request"] as? String)
        let nativeRoot = try #require(JSONSerialization.jsonObject(with: Data(request.utf8)) as? [String: Any])
        let assets = try #require(nativeRoot["assets"] as? [[String: Any]])
        #expect(assets.map { $0["required"] as? Int } == [1, 1, 0])

        let duplicate = AdRequest(placementID: "p", format: .native,
            nativeAssets: [NativeAssetRequest(id: 0, kind: .title(maxLength: 10)), NativeAssetRequest(id: 0, kind: .data(type: 2, maxLength: nil))])
        #expect(throws: (any Error).self) { try OpenRTBRequestBuilder.build(configuration: config(), privacy: Privacy(), request: duplicate) }
        let invalidVideos = [
            VideoConstraints(mimes: []), VideoConstraints(protocols: []),
            VideoConstraints(minimumDuration: -1), VideoConstraints(minimumDuration: 10, maximumDuration: 9),
            VideoConstraints(podDuration: 0), VideoConstraints(maximumAds: 0),
        ]
        for video in invalidVideos {
            #expect(throws: (any Error).self) {
                try OpenRTBRequestBuilder.build(configuration: config(), privacy: Privacy(),
                                                request: AdRequest(placementID: "p", format: .instream, video: video))
            }
        }
    }

    @Test func noFillAndMalformed() throws {
        let request = bannerRequest()
        #expect(throws: EngageError.noFill) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 204), requestID: "r", impressionID: "i", request: request) }
        #expect(throws: EngageError.noFill) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: Data(#"{"id":"r","seatbid":[]}"#.utf8)), requestID: "r", impressionID: "i", request: request) }
        #expect(throws: (any Error).self) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: Data(#"{"id":"r","seatbid":[{"bid":{}}]}"#.utf8)), requestID: "r", impressionID: "i", request: request) }
    }

    @Test func strictWinner() throws {
        let request = bannerRequest()
        func data(_ id: String = "r", _ bids: String) -> Data { Data("{\"id\":\"\(id)\",\"seatbid\":[{\"bid\":[\(bids)]}]}".utf8) }
        let good = "{\"id\":\"b\",\"impid\":\"i\",\"price\":0,\"adm\":\"<p>x</p>\",\"exp\":10}"
        _ = try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: data("r", good)), requestID: "r", impressionID: "i", request: request)
        #expect(throws: (any Error).self) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: data("other", good)), requestID: "r", impressionID: "i", request: request) }
        #expect(throws: EngageError.ambiguousBid) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: data("r", good + "," + good)), requestID: "r", impressionID: "i", request: request) }
        for invalid in ["true", "-1", "1e999"] {
            let bid = "{\"id\":\"b\",\"impid\":\"i\",\"price\":\(invalid),\"adm\":\"x\"}"
            #expect(throws: (any Error).self) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: data("r", bid)), requestID: "r", impressionID: "i", request: request) }
        }
        let exp = "{\"id\":\"b\",\"impid\":\"i\",\"price\":1,\"adm\":\"x\",\"exp\":1.5}"
        #expect(throws: EngageError.expiredBid) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: data("r", exp)), requestID: "r", impressionID: "i", request: request) }
    }

    @Test func noticesAndBilling() async throws {
        let recorder = URLRecorder()
        let transport = HandlerTransport { request in
            recorder.add(request.url)
            if request.method == .post {
                let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
                let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
                return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":1,\"adm\":\"<p>ok</p>\",\"nurl\":\"https://n.test/win\",\"burl\":\"https://n.test/bill\"}]}]}".utf8))
            }
            if request.url.path == "/win" { try await Task.sleep(for: .seconds(2)) }; return HTTPResponse(statusCode: 204)
        }
        let started = ContinuousClock.now
        let creative = try await EngageClient(configuration: config(), transport: transport).load(bannerRequest())
        #expect(started.duration(to: .now) < .seconds(1)); #expect(recorder.count("/bill") == 0)
        try await creative.beginDisplay()
        for _ in 0..<100 where recorder.count("/bill") == 0 { await Task.yield() }
        #expect(recorder.count("/bill") == 1)
        await #expect(throws: (any Error).self) { try await creative.beginDisplay() }; #expect(recorder.count("/bill") == 1)
    }

    @Test func nurlVASTAndParameters() async throws {
        let vast = "<VAST version=\"4.2\"><Ad id=\"1\"><InLine><AdSystem>x</AdSystem><AdTitle>x</AdTitle><Impression>https://t.test/i</Impression><Creatives/></InLine></Ad></VAST>"
        let transport = HandlerTransport { request in
            if request.method == .post {
                let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
                let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
                return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":0,\"nurl\":\"https://creative.test/vast\"}]}]}".utf8))
            }; return HTTPResponse(statusCode: 200, body: Data(vast.utf8))
        }
        let creative = try await EngageClient(configuration: config(), transport: transport).load(AdRequest(placementID: "p", format: .instream))
        #expect(creative.kind == .vast); #expect(creative.markup == vast)
        let url = try EngageClient.vastURL(URL(string: "https://ads.test/tag?existing=a%20b")!, parameters: ["cust params": "x&y"])
        let items = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        #expect(items.first(where: { $0.name == "existing" })?.value == "a b"); #expect(items.first(where: { $0.name == "cust params" })?.value == "x&y")
    }

    @Test func vastMacrosRespectPrivacyAndDirectFetchUsesRequestTimeout() async throws {
        let config = EngageConfiguration(endpoint: .vastTag(url: URL(string: "https://ads.test/tag")!),
            app: AppMetadata(bundle: "com.test", name: "Test App"), deviceCategory: .mobile,
            device: DeviceMetadata(operatingSystem: "iOS", operatingSystemVersion: "18", userAgent: "ua", make: "Apple", model: "Phone", advertisingID: "secret"),
            requestTimeout: 2, creativeTimeout: 9)
        let privacy = Privacy(limitAdTracking: true, advertisingID: "secret")
        let url = try EngageClient.vastURL(
            URL(string: "https://ads.test/tag?ifa=[IFA]&gdpr=[GDPR]&os=[DEVICEOS]&make=[DEVICEMAKE]&cb=[CACHEBUSTER]&keep=yes")!,
            parameters: ["idfa": "secret", "model": "[DEVICEMODEL]", "consent": "[GDPR_CONSENT]"],
            configuration: config, privacy: privacy)
        let items = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        #expect(items.first(where: { $0.name == "ifa" }) == nil)
        #expect(items.first(where: { $0.name == "gdpr" }) == nil)
        #expect(items.first(where: { $0.name == "idfa" }) == nil)
        #expect(items.first(where: { $0.name == "consent" }) == nil)
        #expect(items.first(where: { $0.name == "os" })?.value == "iOS 18")
        #expect(items.first(where: { $0.name == "make" })?.value == "Apple")
        #expect(items.first(where: { $0.name == "model" })?.value == "Phone")
        #expect(items.first(where: { $0.name == "cb" })?.value?.count == 8)
        #expect(!url.absoluteString.contains("secret"))

        let recorder = RequestRecorder()
        let vast = "<VAST version=\"4.2\"><Ad id=\"1\"><InLine><AdSystem>x</AdSystem><AdTitle>x</AdTitle><Impression>https://t.test/i</Impression><Creatives/></InLine></Ad></VAST>"
        let transport = HandlerTransport { request in recorder.add(request); return HTTPResponse(statusCode: 200, body: Data(vast.utf8)) }
        let creative = try await EngageClient(configuration: config, privacy: privacy, transport: transport)
            .load(AdRequest(placementID: "p", format: .instream))
        #expect(recorder.last?.timeout == 2)
        #expect(creative.format == .instream)
        creative.destroy()
    }

    @Test func nativeResponseRequiresRequestedTypedIDsAndSafeURLs() throws {
        let requested = [NativeAssetRequest(id: 0, required: true, kind: .title(maxLength: 40))]
        let valid = #"{"assets":[{"id":0,"title":{"text":"Title"}}],"link":{"url":"myapp://open/item","clicktrackers":["https://track.test/c"]},"imptrackers":["https://track.test/i"]}"#
        let payload = try OpenRTBResponseParser.parseNative(valid, requested: requested)
        #expect(payload.assets.first?.id == 0); #expect(payload.clickURL?.scheme == "myapp")
        let defaults = #"{"assets":[{"id":1,"title":{"text":"Title"}},{"id":2,"img":{"url":"https://cdn.test/image.png"}},{"id":3,"data":{"value":"Description"}},{"id":4,"video":{"vasttag":"vast"}}],"link":{"url":"https://click.test"}}"#
        let defaultPayload = try OpenRTBResponseParser.parseNative(defaults, requested: [], defaultVideo: VideoConstraints())
        #expect(defaultPayload.assets.map(\.id) == [1, 2, 3, 4])

        let invalid = [
            #"{"assets":[{"id":-1,"title":{"text":"x"}}],"link":{"url":"https://click.test"}}"#,
            #"{"assets":[{"id":0.5,"title":{"text":"x"}}],"link":{"url":"https://click.test"}}"#,
            #"{"assets":[{"id":0,"title":{"text":"x"}},{"id":0,"title":{"text":"y"}}],"link":{"url":"https://click.test"}}"#,
            #"{"assets":[{"id":1,"title":{"text":"x"}}],"link":{"url":"https://click.test"}}"#,
            #"{"assets":[{"id":0,"data":{"value":"wrong"}}],"link":{"url":"https://click.test"}}"#,
            #"{"assets":[{"id":0,"title":{"text":"x"}}],"link":{"url":"javascript:alert(1)"}}"#,
            #"{"assets":[{"id":0,"title":{"text":"x"}}],"link":{"url":"https://click.test"},"imptrackers":["file:///tmp/tracker"]}"#,
        ]
        for markup in invalid {
            #expect(throws: (any Error).self) { try OpenRTBResponseParser.parseNative(markup, requested: requested) }
        }
        let imageRequest = [NativeAssetRequest(id: 2, kind: .image(type: 3, minimumWidth: 1, minimumHeight: 1))]
        let relativeImage = #"{"assets":[{"id":2,"img":{"url":"relative.png"}}],"link":{"url":"https://click.test"}}"#
        #expect(throws: (any Error).self) { try OpenRTBResponseParser.parseNative(relativeImage, requested: imageRequest) }
    }

    @Test func structuredLoadAndRenderDiagnosticsAreSanitized() async throws {
        func successTransport() -> HandlerTransport {
            HandlerTransport { request in
                let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
                let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
                return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":0,\"adm\":\"<p>ok</p>\"}]}]}".utf8))
            }
        }
        func configured(_ recorder: DiagnosticRecorder) -> EngageConfiguration {
            EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile,
                                diagnostics: Diagnostics(recorder.add))
        }

        let successDiagnostics = DiagnosticRecorder()
        let client = EngageClient(configuration: configured(successDiagnostics), transport: successTransport())
        let creative = try await client.load(AdRequest(placementID: "private-slot", format: .banner, size: AdSize(width: 320, height: 50)))
        #expect(creative.format == .banner)
        try creative.beginDisplayImmediately()
        let success = successDiagnostics.all()
        #expect(success.map(\.code).contains("load_start")); #expect(success.map(\.code).contains("load_success")); #expect(success.map(\.code).contains("displayed"))
        let successMetadata = try #require(success.first(where: { $0.code == "load_success" })?.metadata)
        #expect(Int(successMetadata["duration_ms"] ?? "") != nil); #expect(successMetadata["format"] == "banner")
        #expect(!successMetadata.values.contains(where: { $0.contains("private-slot") || $0.contains("ads.example") }))

        let readyCreative = try await client.load(bannerRequest())
        readyCreative.finish(success: false)
        #expect(readyCreative.state == .failed)
        let helperCreative = try await client.load(bannerRequest())
        helperCreative.reportRenderFailure(.creativeTimeout)
        #expect(helperCreative.state == .failed)
        #expect(successDiagnostics.all().contains { $0.code == "render_failed" && $0.metadata["error"] == "creative_timeout" })

        let noFillDiagnostics = DiagnosticRecorder()
        let noFillClient = EngageClient(configuration: configured(noFillDiagnostics),
            transport: HandlerTransport { _ in HTTPResponse(statusCode: 204) })
        await #expect(throws: EngageError.noFill) { try await noFillClient.load(bannerRequest()) }
        let noFill = try #require(noFillDiagnostics.all().first(where: { $0.code == "no_fill" }))
        #expect(Int(noFill.metadata["duration_ms"] ?? "") != nil)
        #expect(!noFillDiagnostics.all().contains { $0.code == "load_failed" })

        let failedDiagnostics = DiagnosticRecorder()
        let failedClient = EngageClient(configuration: configured(failedDiagnostics),
            transport: HandlerTransport { _ in HTTPResponse(statusCode: 200, body: Data("{".utf8)) })
        await #expect(throws: (any Error).self) { try await failedClient.load(bannerRequest()) }
        let failed = try #require(failedDiagnostics.all().first(where: { $0.code == "load_failed" }))
        #expect(Int(failed.metadata["duration_ms"] ?? "") != nil); #expect(failed.metadata["error"] == "malformed_response")
    }

    @Test func clientDestroyInvalidatesLoadedCreative() async throws {
        let transport = HandlerTransport { request in
            let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
            let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
            return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":0,\"adm\":\"x\",\"burl\":\"https://n.test/b\"}]}]}".utf8))
        }
        let client = EngageClient(configuration: config(), transport: transport); let creative = try await client.load(bannerRequest())
        client.destroy(); #expect(creative.state == .destroyed)
        #expect(throws: EngageError.destroyed) { try creative.beginDisplayImmediately() }
    }

    @Test func malformedJSONAndEmptyVASTAreTyped() {
        #expect(throws: (any Error).self) { try OpenRTBResponseParser.parse(HTTPResponse(statusCode: 200, body: Data("{".utf8)), requestID: "r", impressionID: "i", request: bannerRequest()) }
        #expect(throws: EngageError.noFill) { try VASTValidator.validate("<VAST version=\"4.2\"></VAST>") }
    }

    @Test func measurementCapabilitiesAreAdvertisedOnlyForRealPaths() throws {
        func root(_ configuration: EngageConfiguration, _ request: AdRequest) throws -> [String: Any] {
            let built = try OpenRTBRequestBuilder.build(configuration: configuration, privacy: Privacy(),
                                                        request: request, requestID: "r", impressionID: "i")
            return try #require(JSONSerialization.jsonObject(with: built.body) as? [String: Any])
        }
        func firstImp(_ root: [String: Any]) throws -> [String: Any] {
            try #require((root["imp"] as? [[String: Any]])?.first)
        }

        let plain = try root(config(), bannerRequest())
        #expect((try firstImp(plain)["banner"] as? [String: Any])?["api"] as? [Int] == [6])
        #expect(plain["source"] == nil)

        let backend = FakeMeasurementBackend(types: [.html, .native], name: "acme", version: "2")
        let measured = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                           deviceCategory: .mobile, measurementBackend: backend)
        let banner = try root(measured, bannerRequest())
        #expect((try firstImp(banner)["banner"] as? [String: Any])?["api"] as? [Int] == [6, 7])
        let source = try #require((banner["source"] as? [String: Any])?["ext"] as? [String: Any])
        #expect(source["omidpn"] as? String == "acme"); #expect(source["omidpv"] as? String == "2")

        let native = try firstImp(root(measured, AdRequest(placementID: "p", format: .native)))
        let nativeEnvelope = try #require(native["native"] as? [String: Any])
        #expect(nativeEnvelope["api"] as? [Int] == [7])
        let nativeString = try #require(nativeEnvelope["request"] as? String)
        let nativeRoot = try #require(JSONSerialization.jsonObject(with: Data(nativeString.utf8)) as? [String: Any])
        let trackers = try #require(nativeRoot["eventtrackers"] as? [[String: Any]])
        #expect(trackers.contains { $0["event"] as? Int == 555 && $0["methods"] as? [Int] == [2] })

        let nativeVideoRequest = AdRequest(placementID: "p", format: .native, video: VideoConstraints())
        let nativeVideoRoot = try root(measured, nativeVideoRequest)
        let nativeVideoImp = try firstImp(nativeVideoRoot)
        let nativeVideoEnvelope = try #require(nativeVideoImp["native"] as? [String: Any])
        #expect(nativeVideoEnvelope["api"] == nil)
        #expect(nativeVideoRoot["source"] == nil)
        let nativeVideoString = try #require(nativeVideoEnvelope["request"] as? String)
        let nativeVideoPayload = try #require(JSONSerialization.jsonObject(with: Data(nativeVideoString.utf8)) as? [String: Any])
        let nativeVideoTrackers = try #require(nativeVideoPayload["eventtrackers"] as? [[String: Any]])
        #expect(!nativeVideoTrackers.contains { $0["event"] as? Int == 555 })
        let videoAsset = try #require((nativeVideoPayload["assets"] as? [[String: Any]])?.last?["video"] as? [String: Any])
        #expect(videoAsset["api"] as? [Int] == [7])

        let iosVideo = try firstImp(root(config(), AdRequest(placementID: "p", format: .instream)))
        #expect((iosVideo["video"] as? [String: Any])?["api"] as? [Int] == [7])
        let tv = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"), deviceCategory: .tv)
        let tvVideo = try firstImp(root(tv, AdRequest(placementID: "p", format: .instream)))
        #expect((tvVideo["video"] as? [String: Any])?["api"] == nil)

        let invalid = FakeMeasurementBackend(types: [.html], name: "   ", version: "1")
        let invalidConfiguration = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                                       deviceCategory: .mobile, measurementBackend: invalid)
        let invalidRoot = try root(invalidConfiguration, bannerRequest())
        #expect((try firstImp(invalidRoot)["banner"] as? [String: Any])?["api"] as? [Int] == [6])
        #expect(invalidRoot["source"] == nil)
    }

    @Test func measurementVerificationMetadataIsBoundedAndSanitized() throws {
        let recorder = DiagnosticRecorder()
        let requested = [NativeAssetRequest(id: 1, required: true, kind: .title(maxLength: 20))]
        let events = (0..<33).map { index in
            #"{"event":555,"method":2,"url":"https://verify.test/\#(index).js","ext":{"vendorKey":"vendor","verification_parameters":"params"}}"#
        } + [#"{"event":555,"method":2,"url":"http://secret.example/bad.js","ext":{"verification_parameters":"private-token"}}"#,
             #"{"event":1,"method":2,"url":"https://generic.test/not-executed.js"}"#]
        let markup = #"{"assets":[{"id":1,"title":{"text":"Title"}}],"link":{"url":"https://click.test"},"eventtrackers":["# + events.joined(separator: ",") + "]}"
        let payload = try OpenRTBResponseParser.parseNative(markup, requested: requested,
                                                            diagnostics: Diagnostics(recorder.add))
        #expect(payload.verificationResources.count == 32)
        #expect(payload.eventTrackers.isEmpty)
        let diagnostic = try #require(recorder.all().first { $0.code == "measurement_verification_skipped" })
        #expect(diagnostic.metadata["count"] == "2")
        #expect(!String(describing: diagnostic).contains("private-token"))

        let malformedOptional = #"{"assets":[{"id":1,"title":{"text":"Title"}}],"link":{"url":"https://click.test"},"eventtrackers":{"secret":"value"}}"#
        _ = try OpenRTBResponseParser.parseNative(malformedOptional, requested: requested,
                                                  diagnostics: Diagnostics(recorder.add))
        #expect(recorder.all().filter { $0.code == "measurement_verification_skipped" }.count == 2)
        #expect(!recorder.all().contains { String(describing: $0).contains("secret") })

        #expect(throws: (any Error).self) {
            try MeasurementVerificationResource(javascriptURL: URL(string: "http://example.com/a.js")!)
        }
        _ = try MeasurementVerificationResource(javascriptURL: URL(string: "http://127.0.0.1/a.js")!)
    }

    @Test func requiredOMIDAPIsUseTheSelectedRenderingPath() throws {
        func response(_ apiField: String, adm: String) -> HTTPResponse {
            let escaped = adm.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
            return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"r\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"i\",\"price\":1,\(apiField),\"adm\":\"\(escaped)\"}]}]}".utf8))
        }
        #expect(throws: EngageError.unsupported("Selected bid requires an unavailable rendering API")) {
            try OpenRTBResponseParser.parse(response("\"api\":7", adm: "<p>x</p>"), requestID: "r", impressionID: "i", request: bannerRequest())
        }
        for field in ["\"api\":true", "\"api\":7.5", "\"apis\":[\"7\"]"] {
            #expect(throws: (any Error).self) {
                try OpenRTBResponseParser.parse(response(field, adm: "<p>x</p>"), requestID: "r", impressionID: "i", request: bannerRequest())
            }
        }
        #expect(throws: EngageError.unsupported("Selected bid requires an unavailable rendering API")) {
            try OpenRTBResponseParser.parse(response("\"apis\":[99]", adm: "<p>x</p>"), requestID: "r", impressionID: "i", request: bannerRequest())
        }
        let backend = FakeMeasurementBackend(types: [.html])
        _ = try OpenRTBResponseParser.parse(response("\"apis\":[7]", adm: "<p>x</p>"), requestID: "r", impressionID: "i",
                                            request: bannerRequest(), measurementCapabilities: backend.capabilities)

        let nativeVideo = #"{"assets":[{"id":4,"video":{"vasttag":"<VAST/>"}}],"link":{"url":"https://click.test"}}"#
        let nativeRequest = AdRequest(placementID: "p", format: .native,
                                      nativeAssets: [NativeAssetRequest(id: 4, required: true, kind: .video(VideoConstraints()))])
        _ = try OpenRTBResponseParser.parse(response("\"api\":7", adm: nativeVideo), requestID: "r", impressionID: "i",
                                            request: nativeRequest, supportsIMAVideoOMID: true)
        #expect(throws: EngageError.unsupported("Selected bid requires an unavailable rendering API")) {
            try OpenRTBResponseParser.parse(response("\"api\":7", adm: nativeVideo), requestID: "r", impressionID: "i",
                                            request: nativeRequest, supportsIMAVideoOMID: false)
        }
    }

    @Test @MainActor func measurementLifecycleIsDeduplicatedReentrantAndReleasesSession() throws {
        let session = FakeMeasurementSession()
        let lifecycle = MeasurementSessionLifecycle(session: session, creativeType: .native)
        lifecycle.start(); lifecycle.start(); lifecycle.loaded(); lifecycle.loaded(); lifecycle.impression(); lifecycle.impression(); lifecycle.finish(); lifecycle.finish()
        #expect(session.events == ["start", "loaded", "impression", "finish"])

        for reentrantStage in ["start", "loaded", "impression"] {
            let candidate = FakeMeasurementSession()
            var candidateLifecycle: MeasurementSessionLifecycle!
            candidate.reenter = { stage in if stage == reentrantStage { candidateLifecycle.finish() } }
            candidateLifecycle = MeasurementSessionLifecycle(session: candidate, creativeType: .html)
            candidateLifecycle.start(); candidateLifecycle.loaded(); candidateLifecycle.impression(); candidateLifecycle.finish()
            let finishCount = candidate.events.filter { $0 == "finish" }.count
            #expect(finishCount == 1)
            #expect(candidate.events.first == "start")
        }

        let recorder = DiagnosticRecorder()
        var throwing: FakeMeasurementSession? = FakeMeasurementSession()
        throwing?.throwingStage = "loaded"
        weak var released = throwing
        let failingLifecycle = MeasurementSessionLifecycle(session: throwing!, creativeType: .html,
                                                           diagnostics: Diagnostics(recorder.add))
        failingLifecycle.start(); failingLifecycle.loaded(); throwing = nil
        #expect(released == nil)
        let diagnostic = try #require(recorder.all().first { $0.code == "measurement_session_failed" })
        #expect(diagnostic.metadata["stage"] == "loaded")
        #expect(!String(describing: diagnostic).contains("private backend detail"))
    }

    @Test @MainActor func loadedCreativeOwnsOneMeasurementSessionAndFinishesIt() async throws {
        let backend = FakeMeasurementBackend(types: [.html])
        let configured = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                             deviceCategory: .mobile, measurementBackend: backend)
        let transport = HandlerTransport { request in
            let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
            let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
            return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":0,\"adm\":\"<p>ok</p>\"}]}]}".utf8))
        }
        let creative = try await EngageClient(configuration: configured, transport: transport).load(bannerRequest())
        let target = FakeMeasurementTarget()
        #expect(creative.prepareMeasurement(creativeType: .html, target: target))
        #expect(backend.prepared == 1)
        let lifecycle = try #require(creative.makeMeasurementSession(creativeType: .html, target: target))
        #expect(creative.makeMeasurementSession(creativeType: .html, target: target) == nil)
        lifecycle.loaded()
        try creative.beginDisplayImmediately(); lifecycle.impression(); creative.finish()
        for _ in 0..<100 where backend.session?.events.last != "finish" { await Task.yield() }
        #expect(backend.session?.events == ["start", "loaded", "impression", "finish"])
    }

    @Test @MainActor func failedHTMLMeasurementPreparationDoesNotAffectRendering() async throws {
        let recorder = DiagnosticRecorder()
        let backend = FakeMeasurementBackend(types: [.html]); backend.preparationError = true
        let configured = EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"),
                                             deviceCategory: .mobile, diagnostics: Diagnostics(recorder.add),
                                             measurementBackend: backend)
        let transport = HandlerTransport { request in
            let root = try JSONSerialization.jsonObject(with: request.body!) as! [String: Any]
            let rid = root["id"] as! String, iid = (root["imp"] as! [[String: Any]])[0]["id"] as! String
            return HTTPResponse(statusCode: 200, body: Data("{\"id\":\"\(rid)\",\"seatbid\":[{\"bid\":[{\"id\":\"b\",\"impid\":\"\(iid)\",\"price\":0,\"adm\":\"<p>ok</p>\"}]}]}".utf8))
        }
        let creative = try await EngageClient(configuration: configured, transport: transport).load(bannerRequest())
        let prepared = creative.prepareMeasurement(creativeType: .html, target: FakeMeasurementTarget())
        #expect(!prepared)
        if prepared { _ = creative.makeMeasurementSession(creativeType: .html, target: FakeMeasurementTarget()) }
        #expect(backend.session == nil)
        try creative.beginDisplayImmediately(); creative.finish()
        #expect(creative.state == .finished)
        let failure = try #require(recorder.all().first { $0.code == "measurement_session_failed" })
        #expect(failure.metadata["stage"] == "prepare")
        #expect(!String(describing: failure).contains("private preparation detail"))
    }

    private func config() -> EngageConfiguration { EngageConfiguration(endpoint: .openRTB26(url: endpoint), app: AppMetadata(bundle: "com.test"), deviceCategory: .mobile) }
    private func bannerRequest() -> AdRequest { AdRequest(placementID: "p", format: .banner, size: AdSize(width: 320, height: 50)) }
}
