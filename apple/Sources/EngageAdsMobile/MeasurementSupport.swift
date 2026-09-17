#if os(iOS)
import UIKit
import WebKit

public enum EngageFriendlyObstructionPurpose: Sendable {
    case mediaControls
    case closeAd
    case notVisible
    case other
}

@MainActor
public struct EngageFriendlyObstruction {
    public let view: UIView
    public let purpose: EngageFriendlyObstructionPurpose
    public let detailedReason: String?

    public init(view: UIView, purpose: EngageFriendlyObstructionPurpose,
                detailedReason: String? = nil) throws {
        if let detailedReason {
            guard detailedReason.count <= 50,
                  detailedReason.unicodeScalars.allSatisfy({ scalar in
                      scalar.value == 0x20 || (0x30...0x39).contains(scalar.value) ||
                      (0x41...0x5A).contains(scalar.value) || (0x61...0x7A).contains(scalar.value)
                  }) else {
                throw EngageError.invalidRequest("Friendly obstruction reason must contain at most 50 letters, numbers, or spaces")
            }
        }
        self.view = view
        self.purpose = purpose
        self.detailedReason = detailedReason
    }
}

/// The short-lived, view-bound target passed to a licensed Open Measurement adapter.
/// Applications should retain the backend, not targets or sessions created for individual ads.
@MainActor
public final class EngageAppleMeasurementTarget: MeasurementTarget {
    public let creativeType: MeasurementCreativeType
    public let adView: UIView
    public let webView: WKWebView?
    public let friendlyObstructions: [EngageFriendlyObstruction]

    init(creativeType: MeasurementCreativeType, adView: UIView, webView: WKWebView? = nil,
         friendlyObstructions: [EngageFriendlyObstruction] = []) {
        self.creativeType = creativeType
        self.adView = adView
        self.webView = webView
        self.friendlyObstructions = friendlyObstructions
    }
}

@MainActor
public final class EngageAppleHTMLPreparationTarget: MeasurementTarget {
    public let webViewConfiguration: WKWebViewConfiguration
    public let creativeMarkupUTF8Count: Int

    init(webViewConfiguration: WKWebViewConfiguration, creativeMarkupUTF8Count: Int) {
        self.webViewConfiguration = webViewConfiguration
        self.creativeMarkupUTF8Count = creativeMarkupUTF8Count
    }
}

@MainActor
public struct EngageHTMLMeasurementPreparation {
    public let serviceScripts: [WKUserScript]
    public let serviceAlreadyPresentInCreative: Bool

    public init(serviceScripts: [WKUserScript] = [], serviceAlreadyPresentInCreative: Bool = false) {
        self.serviceScripts = serviceScripts
        self.serviceAlreadyPresentInCreative = serviceAlreadyPresentInCreative
    }
}

/// Implement this protocol in an adapter backed by the licensed, partner-namespaced IAB OM SDK.
/// Conforming alone does not activate a capability: `capabilitySnapshot()` must report a ready,
/// valid partner and the creative types the adapter can actually measure.
public protocol EngageAppleMeasurementBackend: MeasurementBackend {
    @MainActor
    func prepareHTML() throws -> EngageHTMLMeasurementPreparation

    @MainActor
    func makeAppleSession(metadata: MeasurementSessionMetadata,
                          target: EngageAppleMeasurementTarget) throws -> any MeasurementBackendSession
}

public extension EngageAppleMeasurementBackend {
    @MainActor
    func prepare(metadata: MeasurementSessionMetadata,
                 target: any MeasurementTarget) throws {
        guard metadata.creativeType == .html,
              let target = target as? EngageAppleHTMLPreparationTarget else { return }
        let preparation = try prepareHTML()
        let scripts = preparation.serviceScripts
        guard (1...32).contains(scripts.count) || preparation.serviceAlreadyPresentInCreative,
              scripts.count <= 32,
              scripts.allSatisfy({ $0.injectionTime == .atDocumentStart }) else {
            throw EngageError.invalidRequest("HTML measurement preparation is invalid")
        }
        let scriptBytes = scripts.reduce(0) { partial, script in
            let (next, overflow) = partial.addingReportingOverflow(script.source.utf8.count)
            return overflow ? Int.max : next
        }
        guard scriptBytes <= 4 * 1_024 * 1_024,
              target.creativeMarkupUTF8Count <= 4 * 1_024 * 1_024 - scriptBytes else {
            throw EngageError.invalidRequest("HTML measurement service and creative exceed the size limit")
        }
        scripts.forEach { target.webViewConfiguration.userContentController.addUserScript($0) }
    }

    @MainActor
    func makeSession(metadata: MeasurementSessionMetadata,
                     target: any MeasurementTarget) throws -> any MeasurementBackendSession {
        guard let target = target as? EngageAppleMeasurementTarget,
              target.creativeType == metadata.creativeType else {
            throw EngageError.unsupported("Measurement backend received an incompatible Apple target")
        }
        return try makeAppleSession(metadata: metadata, target: target)
    }
}
#endif
