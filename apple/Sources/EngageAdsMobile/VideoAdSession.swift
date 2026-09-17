#if os(iOS)
import AVFoundation
@preconcurrency import GoogleInteractiveMediaAds
import UIKit

@MainActor public protocol EngageContentPlaybackController: AnyObject {
    var isPlaying: Bool { get }
    func pauseContent()
    func resumeContent()
}

@MainActor public final class EngageAVPlayerContentController: EngageContentPlaybackController {
    public let player: AVPlayer
    public init(player: AVPlayer) { self.player = player }
    public var isPlaying: Bool { player.timeControlStatus == .playing }
    public func pauseContent() { player.pause() }
    public func resumeContent() { player.play() }
}

@MainActor
public final class EngageVideoAdSession: NSObject, @preconcurrency IMAAdsLoaderDelegate, @preconcurrency IMAAdsManagerDelegate {
    private let creative: LoadedCreative
    private weak var contentPlayer: AVPlayer?
    private weak var contentController: (any EngageContentPlaybackController)?
    private weak var container: UIView?
    private weak var controller: UIViewController?
    private let rewarded: Bool
    private let vastMarkup: String
    private let ownsDisplayTransition: Bool
    private let handler: EngageEventHandler?
    private var friendlyObstructions: [EngageFriendlyObstruction]
    private let adsLoader = IMAAdsLoader(settings: nil)
    private var adsManager: IMAAdsManager?
    private var displayContainer: IMAAdDisplayContainer?
    private var contentPlayhead: IMAAVPlayerContentPlayhead?
    private var contentWasPlaying = false
    private var contentPausedForAd = false
    private var rewardSent = false
    private var rewardEligible = false
    private var displayStarted = false
    private var readinessTask: Task<Void, Never>?
    private var destroyed = false
    private var terminal = false
    private var loadRequested = false

    init(creative: LoadedCreative, contentPlayer: AVPlayer?, adContainer: UIView,
         viewController: UIViewController, rewarded: Bool = false, vastMarkup: String? = nil,
         contentController: (any EngageContentPlaybackController)? = nil,
         friendlyObstructions: [EngageFriendlyObstruction] = [],
         eventHandler: EngageEventHandler? = nil) throws {
        guard vastMarkup != nil || creative.kind == .vast || (creative.kind == .native && creative.native?.assets.contains(where: {
            if case .video = $0.value { return true }; return false
        }) == true) else { throw EngageError.unsupported("Video session requires VAST markup") }
        self.creative = creative; self.contentPlayer = contentPlayer; self.container = adContainer
        self.contentController = contentController
        self.controller = viewController; self.rewarded = rewarded; self.handler = eventHandler
        self.friendlyObstructions = friendlyObstructions
        self.vastMarkup = vastMarkup ?? creative.markup; self.ownsDisplayTransition = vastMarkup == nil
        if let contentPlayer { contentPlayhead = IMAAVPlayerContentPlayhead(avPlayer: contentPlayer) }
        super.init(); adsLoader.delegate = self
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    public func load() {
        guard !destroyed, !terminal, let container, let controller else { handler?(.error(.destroyed)); return }
        guard !loadRequested else { handler?(.error(.invalidState(expected: AdState.ready.rawValue, actual: "loading"))); return }
        guard let window = container.window, !container.bounds.isEmpty,
              !container.convert(container.bounds, to: window).intersection(window.bounds).isEmpty,
              UIApplication.shared.applicationState == .active, isVisible(container) else {
            handler?(.error(.rendering("Video ad container must be visible in the foreground before loading"))); return
        }
        loadRequested = true
        let display = IMAAdDisplayContainer(adContainer: container, viewController: controller, companionSlots: nil)
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
        let request = IMAAdsRequest(adsResponse: vastMarkup, adDisplayContainer: display,
                                    contentPlayhead: contentPlayhead, userContext: creative.requestID)
        adsLoader.requestAds(with: request)
        readinessTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(creative.creativeTimeout * 1_000_000_000))
            guard !Task.isCancelled, !self.displayStarted, !self.destroyed else { return }
            self.fail(.creativeTimeout)
        }
    }

    public func destroy() {
        guard !destroyed else { return }; destroyed = true; terminal = true
        removeBackgroundObserver()
        readinessTask?.cancel(); readinessTask = nil; rewardEligible = false
        releaseIMAResources()
        if ownsDisplayTransition { creative.destroy() }
        resumeContentIfNeeded()
    }

    public func adsLoader(_ loader: IMAAdsLoader, adsLoadedWith adsLoadedData: IMAAdsLoadedData) {
        guard !destroyed, !terminal, let manager = adsLoadedData.adsManager else { return }
        adsManager = manager; manager.delegate = self; manager.initialize(with: nil)
    }

    public func adsLoader(_ loader: IMAAdsLoader, failedWith adErrorData: IMAAdLoadingErrorData) {
        fail(.rendering("IMA failed to load the ad"))
    }

    public func adsManager(_ adsManager: IMAAdsManager, didReceive event: IMAAdEvent) {
        guard !destroyed, !terminal else { return }
        switch event.type {
        case .LOADED:
            if rewarded, event.ad?.adPodInfo.totalAds != 1 { fail(.rendering("Rewarded placements require exactly one creative")); return }
            rewardEligible = rewarded
            handler?(.loaded)
            guard !destroyed, !terminal, self.adsManager === adsManager else { return }
            adsManager.start()
        case .STARTED:
            if displayStarted { return }
            displayStarted = true
            readinessTask?.cancel(); readinessTask = nil
            guard let container, let window = container.window, UIApplication.shared.applicationState == .active,
                  !container.convert(container.bounds, to: window).intersection(window.bounds).isEmpty, isVisible(container) else {
                fail(.rendering("Video ad started without a visible foreground container")); return
            }
            guard ownsDisplayTransition else { handler?(.displayed); return }
            do { try creative.beginDisplayImmediately(); handler?(.displayed) }
            catch let error as EngageError { fail(error) }
            catch { fail(.rendering("Video display transition failed")) }
        case .CLICKED, .TAPPED: handler?(.clicked)
        case .COMPLETE:
            guard displayStarted else { return }
            handler?(.adCompleted)
            guard !destroyed, !terminal else { return }
            if rewarded, rewardEligible, !rewardSent { rewardSent = true; rewardEligible = false; handler?(.rewardEarned) }
        case .ALL_ADS_COMPLETED:
            guard !terminal else { return }; terminal = true
            removeBackgroundObserver(); readinessTask?.cancel(); readinessTask = nil
            if ownsDisplayTransition {
                if displayStarted { creative.finish() }
                else { creative.reportRenderFailure(.rendering("IMA completed without starting playback")) }
            }
            handler?(.breakCompleted); resumeContentIfNeeded(); releaseIMAResources()
        case .SKIPPED:
            rewardEligible = false
            handler?(.dismissed)
            if rewarded {
                terminal = true; removeBackgroundObserver(); readinessTask?.cancel(); readinessTask = nil
                creative.finish(success: false); releaseIMAResources(); resumeContentIfNeeded()
            }
        default: break
        }
    }

    public func adsManager(_ adsManager: IMAAdsManager, didReceive error: IMAAdError) { fail(.rendering("IMA playback error")) }

    public func adsManagerDidRequestContentPause(_ adsManager: IMAAdsManager) {
        contentPausedForAd = true
        if let contentController { contentWasPlaying = contentController.isPlaying; contentController.pauseContent() }
        else if let player = contentPlayer { contentWasPlaying = player.timeControlStatus == .playing; player.pause() }
    }

    public func adsManagerDidRequestContentResume(_ adsManager: IMAAdsManager) { resumeContentIfNeeded() }

    private func fail(_ error: EngageError) {
        guard !destroyed, !terminal else { return }; terminal = true
        removeBackgroundObserver()
        rewardEligible = false; readinessTask?.cancel(); readinessTask = nil
        creative.reportRenderFailure(error); releaseIMAResources(); resumeContentIfNeeded()
        handler?(.error(error))
    }

    private func resumeContentIfNeeded() {
        guard contentPausedForAd else { return }; contentPausedForAd = false
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
        adsManager?.delegate = nil
        adsManager?.destroy(); adsManager = nil
        displayContainer?.unregisterAllFriendlyObstructions(); displayContainer = nil
        friendlyObstructions.removeAll()
        adsLoader.delegate = nil
        contentPlayhead = nil
    }
    @objc private func backgrounded() { fail(.rendering("App entered the background during ad playback")) }
}

@MainActor
public final class EngageInstreamAd {
    private let session: EngageVideoAdSession

    public init(creative: LoadedCreative, contentPlayer: AVPlayer?, adContainer: UIView,
                viewController: UIViewController,
                contentController: (any EngageContentPlaybackController)? = nil,
                friendlyObstructions: [EngageFriendlyObstruction] = [],
                eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .instream, creative.kind == .vast else {
            throw EngageError.unsupported("Instream ad requires an instream-format VAST creative")
        }
        session = try EngageVideoAdSession(creative: creative, contentPlayer: contentPlayer,
                                           adContainer: adContainer, viewController: viewController,
                                           contentController: contentController,
                                           friendlyObstructions: friendlyObstructions, eventHandler: eventHandler)
    }

    public func load() { session.load() }
    public func destroy() { session.destroy() }
}

@MainActor
public final class EngageRewardedAd {
    private let session: EngageVideoAdSession

    public init(creative: LoadedCreative, contentPlayer: AVPlayer?, adContainer: UIView,
                viewController: UIViewController,
                contentController: (any EngageContentPlaybackController)? = nil,
                friendlyObstructions: [EngageFriendlyObstruction] = [],
                eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .rewarded, creative.kind == .vast else {
            throw EngageError.unsupported("Rewarded ad requires a rewarded-format VAST creative")
        }
        session = try EngageVideoAdSession(creative: creative, contentPlayer: contentPlayer,
                                           adContainer: adContainer, viewController: viewController,
                                           rewarded: true, contentController: contentController,
                                           friendlyObstructions: friendlyObstructions,
                                           eventHandler: eventHandler)
    }

    public func load() { session.load() }
    public func destroy() { session.destroy() }
}

@MainActor
public final class EngageVideoInterstitialAd {
    private let session: EngageVideoAdSession

    public init(creative: LoadedCreative, contentPlayer: AVPlayer?, adContainer: UIView,
                viewController: UIViewController,
                contentController: (any EngageContentPlaybackController)? = nil,
                friendlyObstructions: [EngageFriendlyObstruction] = [],
                eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .interstitial, creative.kind == .vast else {
            throw EngageError.unsupported("Video interstitial requires an interstitial-format VAST creative")
        }
        session = try EngageVideoAdSession(creative: creative, contentPlayer: contentPlayer,
                                           adContainer: adContainer, viewController: viewController,
                                           contentController: contentController,
                                           friendlyObstructions: friendlyObstructions, eventHandler: eventHandler)
    }

    public func load() { session.load() }
    public func destroy() { session.destroy() }
}
#endif
