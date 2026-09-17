import Foundation

public enum MeasurementCreativeType: String, Sendable, Hashable {
    case html
    case native
}

public struct MeasurementPartner: Sendable, Equatable {
    public let name: String
    public let version: String

    public init(name: String, version: String) {
        self.name = name
        self.version = version
    }

    var isValid: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        name.utf8.count <= 256 && version.utf8.count <= 256
    }
}

public struct MeasurementCapabilities: Sendable, Equatable {
    public let ready: Bool
    public let partner: MeasurementPartner
    public let supportedTypes: Set<MeasurementCreativeType>

    public init(ready: Bool, partner: MeasurementPartner, supportedTypes: Set<MeasurementCreativeType>) {
        self.ready = ready
        self.partner = partner
        self.supportedTypes = supportedTypes
    }

    var isUsable: Bool { ready && partner.isValid && !supportedTypes.isEmpty }
}

public struct MeasurementVerificationResource: Sendable, Equatable {
    public let javascriptURL: URL
    public let vendorKey: String?
    public let verificationParameters: String?

    public init(javascriptURL: URL, vendorKey: String? = nil, verificationParameters: String? = nil) throws {
        guard Self.isAllowedURL(javascriptURL), javascriptURL.absoluteString.utf8.count <= 2_048 else {
            throw EngageError.invalidRequest("Measurement verification URL is invalid")
        }
        guard vendorKey.map({ $0.utf8.count <= 256 }) ?? true,
              verificationParameters.map({ $0.utf8.count <= 4_096 }) ?? true else {
            throw EngageError.invalidRequest("Measurement verification metadata exceeds its limit")
        }
        self.javascriptURL = javascriptURL
        self.vendorKey = vendorKey
        self.verificationParameters = verificationParameters
    }

    private static func isAllowedURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased(), !host.isEmpty else { return false }
        if scheme == "https" { return true }
        return scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
    }
}

public struct MeasurementSessionMetadata: Sendable, Equatable {
    public let creativeType: MeasurementCreativeType
    public let verificationResources: [MeasurementVerificationResource]

    public init(creativeType: MeasurementCreativeType,
                verificationResources: [MeasurementVerificationResource] = []) {
        self.creativeType = creativeType
        self.verificationResources = Array(verificationResources.prefix(32))
    }
}

/// A platform renderer supplies a short-lived strongly typed wrapper around its actual ad view.
/// Configuration objects never retain a measurement target.
public protocol MeasurementTarget: AnyObject, Sendable {}

@MainActor
public protocol MeasurementBackendSession: AnyObject {
    func start() throws
    func loaded() throws
    func impression() throws
    func finish() throws
}

public protocol MeasurementBackend: AnyObject, Sendable {
    /// Called once while configuration is created. The returned value is snapshotted for requests.
    func capabilitySnapshot() throws -> MeasurementCapabilities

    /// Allows a platform adapter to install its service script before an HTML creative executes.
    /// The default implementation is intentionally empty for native-view measurement backends.
    @MainActor
    func prepare(metadata: MeasurementSessionMetadata, target: any MeasurementTarget) throws

    @MainActor
    func makeSession(metadata: MeasurementSessionMetadata,
                     target: any MeasurementTarget) throws -> any MeasurementBackendSession
}

public extension MeasurementBackend {
    @MainActor
    func prepare(metadata: MeasurementSessionMetadata, target: any MeasurementTarget) throws {}
}

@MainActor
public final class MeasurementSessionLifecycle {
    private enum State { case initialized, started, loaded, impressed, failed, finished }
    private var session: (any MeasurementBackendSession)?
    private let creativeType: MeasurementCreativeType
    private let diagnostics: Diagnostics
    private var state: State = .initialized
    private var backendFinished = false

    public init(session: any MeasurementBackendSession, creativeType: MeasurementCreativeType,
                diagnostics: Diagnostics = .disabled) {
        self.session = session
        self.creativeType = creativeType
        self.diagnostics = diagnostics
    }

    public func start() {
        guard state == .initialized, let session else { return }
        invoke(stage: "start", successState: .started) { try session.start() }
    }

    public func loaded() {
        guard state == .started, let session else { return }
        invoke(stage: "loaded", successState: .loaded) { try session.loaded() }
    }

    public func impression() {
        guard state == .loaded, let session else { return }
        invoke(stage: "impression", successState: .impressed) { try session.impression() }
    }

    public func finish() {
        guard state != .finished else { return }
        state = .finished
        finishBackend(reportFailure: true)
    }

    private func invoke(stage: String, successState: State, _ operation: () throws -> Void) {
        state = successState
        do {
            try operation()
        } catch {
            guard state != .finished else { return }
            state = .failed
            diagnostics.emit(.warning, "measurement_session_failed", "Measurement session operation failed",
                             metadata: ["stage": stage, "type": creativeType.rawValue])
            finishBackend(reportFailure: false)
        }
    }

    private func finishBackend(reportFailure: Bool) {
        guard !backendFinished else { return }
        backendFinished = true
        guard let session else { return }
        self.session = nil
        do { try session.finish() }
        catch where reportFailure {
            diagnostics.emit(.warning, "measurement_session_failed", "Measurement session operation failed",
                             metadata: ["stage": "finish", "type": creativeType.rawValue])
        }
        catch { }
    }
}
