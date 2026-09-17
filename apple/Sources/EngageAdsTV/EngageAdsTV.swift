@_exported import EngageAdsCore

#if os(tvOS)
import AVFoundation
@preconcurrency import GoogleInteractiveMediaAds
import UIKit

public typealias EngageTVEventHandler = @MainActor @Sendable (EngageEvent) -> Void

public enum EngageTVFriendlyObstructionPurpose: Sendable {
    case mediaControls, closeAd, notVisible, other
}

@MainActor
public struct EngageTVFriendlyObstruction {
    public let view: UIView
    public let purpose: EngageTVFriendlyObstructionPurpose
    public let detailedReason: String?

    public init(view: UIView, purpose: EngageTVFriendlyObstructionPurpose,
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
        self.view = view; self.purpose = purpose; self.detailedReason = detailedReason
    }
}

@MainActor public protocol EngageTVContentPlaybackController: AnyObject {
    var isPlaying: Bool { get }
    func pauseContent()
    func resumeContent()
}

@MainActor public final class EngageTVAVPlayerContentController: EngageTVContentPlaybackController {
    public let player: AVPlayer
    public init(player: AVPlayer) { self.player = player }
    public var isPlaying: Bool { player.timeControlStatus == .playing }
    public func pauseContent() { player.pause() }
    public func resumeContent() { player.play() }
}

public extension EngageConfiguration {
    @MainActor
    static func television(endpoint: Endpoint, app: AppMetadata, device: DeviceMetadata? = nil,
                           requestTimeout: TimeInterval = 5, creativeTimeout: TimeInterval = 15,
                           diagnostics: Diagnostics = .disabled) -> EngageConfiguration {
        let detected = DeviceMetadata(operatingSystem: "tvOS", operatingSystemVersion: UIDevice.current.systemVersion,
                                      make: "Apple", model: UIDevice.current.model)
        return EngageConfiguration(endpoint: endpoint, app: app, deviceCategory: .tv, device: device ?? detected,
                                   requestTimeout: requestTimeout, creativeTimeout: creativeTimeout, diagnostics: diagnostics)
    }
}

@MainActor
public final class EngageTVAdContainerView: UIView {
    public override var canBecomeFocused: Bool { true }
    public override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        super.didUpdateFocus(in: context, with: coordinator)
        coordinator.addCoordinatedAnimations { self.transform = self.isFocused ? CGAffineTransform(scaleX: 1.02, y: 1.02) : .identity }
    }
}

@MainActor
public final class EngageTVInstreamAd: NSObject, @preconcurrency IMAAdsLoaderDelegate, @preconcurrency IMAAdsManagerDelegate {
    private let creative: LoadedCreative
    private weak var contentPlayer: AVPlayer?
    private weak var contentController: (any EngageTVContentPlaybackController)?
    private weak var container: UIView?
    private weak var controller: UIViewController?
    private weak var contentFocusView: UIView?
    private let handler: EngageTVEventHandler?
    private var friendlyObstructions: [EngageTVFriendlyObstruction]
    private let loader = IMAAdsLoader(settings: nil)
    private var manager: IMAAdsManager?
    private var displayContainer: IMAAdDisplayContainer?
    private var playhead: IMAAVPlayerContentPlayhead?
    private var paused = false
    private var contentWasPlaying = false
    private var destroyed = false
    private var displayStarted = false
    private var readinessTask: Task<Void, Never>?
    private var terminal = false
    private var loadRequested = false

    public init(creative: LoadedCreative, contentPlayer: AVPlayer?, adContainer: UIView,
                viewController: UIViewController, contentFocusView: UIView? = nil,
                contentController: (any EngageTVContentPlaybackController)? = nil,
                friendlyObstructions: [EngageTVFriendlyObstruction] = [],
                eventHandler: EngageTVEventHandler? = nil) throws {
        guard creative.format == .instream, creative.kind == .vast else {
            throw EngageError.unsupported("tvOS supports instream-format VAST creatives only")
        }
        self.creative = creative; self.contentPlayer = contentPlayer; container = adContainer; controller = viewController; handler = eventHandler
        self.contentFocusView = contentFocusView
        self.contentController = contentController
        self.friendlyObstructions = friendlyObstructions
        if let contentPlayer { playhead = IMAAVPlayerContentPlayhead(avPlayer: contentPlayer) }
        super.init(); loader.delegate = self
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    public func load() {
        guard !destroyed, !terminal, let container, let controller else { handler?(.error(.destroyed)); return }
        guard !loadRequested else { handler?(.error(.invalidState(expected: AdState.ready.rawValue, actual: "loading"))); return }
        guard let window = container.window, !container.bounds.isEmpty,
              !container.convert(container.bounds, to: window).intersection(window.bounds).isEmpty,
              UIApplication.shared.applicationState == .active, isVisible(container) else { handler?(.error(.rendering("tvOS ad container must be visible before loading"))); return }
        loadRequested = true
        let display = IMAAdDisplayContainer(adContainer: container, viewController: controller)
        displayContainer = display
        for obstruction in friendlyObstructions {
            guard obstruction.view.window === window else {
                fail(.rendering("Friendly obstruction must share the ad container window")); return
            }
            let purpose: IMAFriendlyObstructionPurpose = switch obstruction.purpose {
            case .mediaControls: .mediaControls
            case .closeAd: .closeAd
            case .notVisible: .notVisible
            case .other: .other
            }
            display.register(IMAFriendlyObstruction(view: obstruction.view, purpose: purpose,
                                                    detailedReason: obstruction.detailedReason))
        }
        friendlyObstructions.removeAll()
        let request = IMAAdsRequest(adsResponse: creative.markup, adDisplayContainer: display, contentPlayhead: playhead,
                                    userContext: creative.requestID)
        loader.requestAds(with: request)
        readinessTask = Task { [weak self] in
            guard let self else { return }; try? await Task.sleep(nanoseconds: UInt64(creative.creativeTimeout * 1_000_000_000))
            guard !Task.isCancelled, !displayStarted, !destroyed else { return }; fail(.creativeTimeout)
        }
    }

    public func destroy() {
        guard !destroyed else { return }; destroyed = true; terminal = true; readinessTask?.cancel(); readinessTask = nil; removeBackgroundObserver()
        releaseIMAResources(); creative.destroy(); resumeContent(); restoreContentFocus()
    }

    public func adsLoader(_ loader: IMAAdsLoader, adsLoadedWith adsLoadedData: IMAAdsLoadedData) {
        guard !destroyed, !terminal, let adsManager = adsLoadedData.adsManager else { return }
        manager = adsManager; adsManager.delegate = self; adsManager.initialize(with: nil)
    }
    public func adsLoader(_ loader: IMAAdsLoader, failedWith adErrorData: IMAAdLoadingErrorData) { fail(.rendering("IMA load error")) }

    public func adsManager(_ adsManager: IMAAdsManager, didReceive event: IMAAdEvent) {
        guard !destroyed, !terminal else { return }
        switch event.type {
        case .LOADED:
            handler?(.loaded)
            guard !destroyed, !terminal, manager === adsManager else { return }
            adsManager.start()
        case .STARTED:
            if displayStarted { return }; displayStarted = true; readinessTask?.cancel(); readinessTask = nil
            guard let container, let window = container.window, UIApplication.shared.applicationState == .active,
                  !container.convert(container.bounds, to: window).intersection(window.bounds).isEmpty, isVisible(container) else {
                fail(.rendering("tvOS ad started while hidden")); return
            }
            do { try creative.beginDisplayImmediately(); handler?(.displayed) }
            catch let error as EngageError { fail(error) }
            catch { fail(.rendering("Video display transition failed")) }
        case .CLICKED, .TAPPED, .ICON_TAPPED: handler?(.clicked)
        case .COMPLETE: if displayStarted { handler?(.adCompleted) }
        case .ALL_ADS_COMPLETED:
            guard !terminal else { return }; terminal = true; removeBackgroundObserver(); readinessTask?.cancel(); readinessTask = nil
            if displayStarted { creative.finish() }
            else { creative.reportRenderFailure(.rendering("IMA completed without starting playback")) }
            handler?(.breakCompleted)
            resumeContent(); releaseIMAResources(); restoreContentFocus()
        case .SKIPPED: handler?(.dismissed)
        default: break
        }
    }
    public func adsManager(_ adsManager: IMAAdsManager, didReceive error: IMAAdError) { fail(.rendering("IMA playback error")) }
    public func adsManagerDidRequestContentPause(_ adsManager: IMAAdsManager) {
        paused = true
        if let contentController { contentWasPlaying = contentController.isPlaying; contentController.pauseContent() }
        else if let player = contentPlayer { contentWasPlaying = player.timeControlStatus == .playing; player.pause() }
    }
    public func adsManagerDidRequestContentResume(_ adsManager: IMAAdsManager) { resumeContent() }

    private func fail(_ error: EngageError) {
        guard !destroyed, !terminal else { return }; terminal = true; removeBackgroundObserver()
        readinessTask?.cancel(); readinessTask = nil
        creative.reportRenderFailure(error); releaseIMAResources(); resumeContent(); handler?(.error(error))
        restoreContentFocus()
    }
    private func resumeContent() {
        guard paused else { return }; paused = false
        if contentWasPlaying { if let contentController { contentController.resumeContent() } else { contentPlayer?.play() } }
    }
    private func isVisible(_ view: UIView) -> Bool {
        var current: UIView? = view
        while let candidate = current { if candidate.isHidden || candidate.alpha <= 0.01 { return false }; current = candidate.superview }
        return true
    }
    private func removeBackgroundObserver() {
        NotificationCenter.default.removeObserver(self, name: UIApplication.didEnterBackgroundNotification, object: nil)
    }
    private func releaseIMAResources() {
        manager?.delegate = nil
        manager?.destroy(); manager = nil
        displayContainer?.unregisterAllFriendlyObstructions(); displayContainer = nil
        friendlyObstructions.removeAll()
        loader.delegate = nil
        playhead = nil
    }
    private func restoreContentFocus() {
        if let contentFocusView { contentFocusView.setNeedsFocusUpdate(); contentFocusView.updateFocusIfNeeded() }
        else { controller?.setNeedsFocusUpdate(); controller?.updateFocusIfNeeded() }
    }
    @objc private func backgrounded() { fail(.rendering("App entered the background during ad playback")) }
}
#endif
