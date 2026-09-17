#if os(iOS)
import AVFoundation
import ImageIO
import UIKit

private let maximumNativeImageBytes = 8 * 1_024 * 1_024
private let maximumNativeImagePixels = 4_194_304

private final class DecodedNativeImage: @unchecked Sendable {
    let value: UIImage
    init(_ value: UIImage) { self.value = value }
}

private func decodeNativeImage(_ data: Data) throws -> DecodedNativeImage {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          CGImageSourceGetCount(source) > 0 else {
        throw EngageError.rendering("Native image could not be decoded")
    }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: Int(Double(maximumNativeImagePixels).squareRoot()),
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
          image.width > 0, image.height > 0,
          image.width <= maximumNativeImagePixels / image.height else {
        throw EngageError.rendering("Native image exceeds the decoded pixel limit")
    }
    return DecodedNativeImage(UIImage(cgImage: image))
}

@MainActor
public final class EngageNativeAd {
    public let assets: [NativeAsset]
    private let creative: LoadedCreative
    private let handler: EngageEventHandler?
    private var imageTasks: [Task<Void, Never>] = []
    private var videoSession: EngageVideoAdSession?
    private var displayLink: CADisplayLink?
    private weak var registeredView: UIView?
    private var gestures: [UITapGestureRecognizer] = []
    private var contentReady = false
    private var imagesReady = false
    private var videoReady = false
    private var impressionSent = false
    private var destroyed = false
    private var terminal = false
    private var measurementLifecycle: MeasurementSessionLifecycle?
    private var friendlyObstructions: [EngageFriendlyObstruction] = []
    private let imageTransport = URLSessionTransport(maximumResponseBytes: maximumNativeImageBytes)

    public init(creative: LoadedCreative, eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .native, creative.kind == .native, let payload = creative.native else {
            throw EngageError.unsupported("Native ad requires a native-format Native 1.2 creative")
        }
        self.creative = creative; assets = payload.assets; handler = eventHandler
    }

    public func makeDefaultView(viewController: UIViewController? = nil, contentPlayer: AVPlayer? = nil) throws -> EngageNativeAdView {
        let view = EngageNativeAdView()
        let hasVideo = assets.contains(where: { if case .video = $0.value { return true }; return false })
        if hasVideo, viewController == nil { throw EngageError.rendering("A view controller is required to render the native video asset") }
        var mapping: [Int: UIView] = [:]
        for asset in assets {
            switch asset.value {
            case .title: mapping[asset.id] = view.titleLabel
            case .image: mapping[asset.id] = view.mainImageView
            case .data: mapping[asset.id] = view.bodyLabel
            case .video: mapping[asset.id] = view.videoContainer
            }
        }
        try register(view: view, assetViews: mapping, clickableViews: [view, view.callToActionButton])
        if hasVideo, let viewController {
            view.videoContainer.isHidden = false; view.mainImageView.isHidden = true
            let session = try makeVideoSession(in: view.videoContainer, viewController: viewController, contentPlayer: contentPlayer)
            view.onAttached = { session.load() }
        }
        return view
    }

    public func register(view: UIView, assetViews: [Int: UIView], clickableViews: [UIView],
                         friendlyObstructions: [EngageFriendlyObstruction] = []) throws {
        guard registeredView == nil else { throw EngageError.invalidState(expected: AdState.ready.rawValue, actual: creative.state.rawValue) }
        guard !destroyed, !terminal else { throw EngageError.destroyed }
        for asset in assets {
            guard let target = assetViews[asset.id] else {
                if creative.native?.requiredAssetIDs.contains(asset.id) == true { throw EngageError.rendering("Missing view for required native asset \(asset.id)") }
                continue
            }
            guard target === view || target.isDescendant(of: view) else { throw EngageError.rendering("Native asset views must be descendants of the registered view") }
            switch (asset.value, target) {
            case (.title(let text), let label as UILabel), (.data(let text), let label as UILabel): label.text = text
            case (.image, is UIImageView), (.video, _): break
            default: throw EngageError.rendering("Custom native view has the wrong class for asset \(asset.id)")
            }
        }
        registeredView = view
        for clickable in clickableViews {
            guard clickable === view || clickable.isDescendant(of: view) else { throw EngageError.rendering("Clickable views must be descendants of the registered view") }
            clickable.isUserInteractionEnabled = true
            let tap = UITapGestureRecognizer(target: self, action: #selector(clicked)); clickable.addGestureRecognizer(tap); gestures.append(tap)
        }
        let containsVideo = assets.contains(where: { if case .video = $0.value { return true }; return false })
        self.friendlyObstructions = containsVideo ? friendlyObstructions : []
        if !containsVideo {
            let target = EngageAppleMeasurementTarget(creativeType: .native, adView: view,
                                                       friendlyObstructions: friendlyObstructions)
            measurementLifecycle = creative.makeMeasurementSession(creativeType: .native, target: target)
            guard !destroyed, !terminal, creative.state == .ready else {
                terminal = true; measurementLifecycle?.finish(); measurementLifecycle = nil
                removeGestures(); registeredView = nil
                throw EngageError.destroyed
            }
        }
        let images = assets.compactMap { asset -> (Int, NativeImage)? in
            guard assetViews[asset.id] is UIImageView, case .image(let image) = asset.value else { return nil }
            return (asset.id, image)
        }
        imagesReady = images.isEmpty
        videoReady = !assets.contains(where: { if case .video = $0.value { return true }; return false })
        updateReadiness()
        if !images.isEmpty {
            let task = Task { [weak self, weak view] in
                guard let self, let view else { return }
                do {
                    for (id, image) in images {
                        let response = try await imageTransport.execute(HTTPRequest(url: image.url, method: .get,
                                                                                   timeout: creative.creativeTimeout))
                        guard !destroyed, !terminal else { return }
                        guard (200..<300).contains(response.statusCode) else { throw EngageError.rendering("Native image request failed") }
                        let rendered = try await Task.detached(priority: .utility) { try decodeNativeImage(response.body) }.value
                        guard !destroyed, !terminal else { return }
                        (assetViews[id] as? UIImageView)?.image = rendered.value
                    }
                    imageTasks.removeAll(); imagesReady = true; updateReadiness()
                } catch is CancellationError { }
                catch { imageTasks.removeAll(); if !destroyed && !terminal { fail(error) } }
            }
            imageTasks.append(task)
        }
    }

    public func makeVideoSession(in container: UIView, viewController: UIViewController,
                                 contentPlayer: AVPlayer? = nil) throws -> EngageVideoAdSession {
        guard let markup = assets.compactMap({ if case .video(let value) = $0.value { return value }; return nil }).first else {
            throw EngageError.unsupported("Native response has no video asset")
        }
        let externalHandler = handler
        let videoFriendlyObstructions = friendlyObstructions
        let session = try EngageVideoAdSession(creative: creative, contentPlayer: contentPlayer, adContainer: container,
                                               viewController: viewController, vastMarkup: markup,
                                               friendlyObstructions: videoFriendlyObstructions) { [weak self] event in
            guard let self, !self.destroyed, !self.terminal else { return }
            if event == .displayed { self.videoReady = true; self.updateReadiness() }
            else if event == .breakCompleted {
                self.terminal = true; self.displayLink?.invalidate(); self.displayLink = nil
                self.imageTasks.forEach { $0.cancel() }; self.imageTasks.removeAll()
                self.creative.finish(success: self.impressionSent); externalHandler?(event)
            } else if case .error(let error) = event { self.fail(error) }
            else if event != .loaded { externalHandler?(event) }
        }
        friendlyObstructions.removeAll()
        videoSession = session; return session
    }

    public func destroy() {
        guard !destroyed else { return }; destroyed = true; terminal = true
        imageTasks.forEach { $0.cancel() }; imageTasks.removeAll(); displayLink?.invalidate(); displayLink = nil
        measurementLifecycle?.finish(); measurementLifecycle = nil
        videoSession?.destroy(); videoSession = nil; creative.destroy()
        removeGestures()
    }

    @objc private func clicked() {
        guard !destroyed, !terminal, impressionSent, let url = creative.native?.clickURL else { return }
        Task { await creative.recordNativeClick() }
        handler?(.clicked); UIApplication.shared.open(url)
    }

    private func startVisibilityMonitoring() {
        displayLink?.invalidate()
        let target = EngageDisplayLinkTarget { [weak self] in self?.checkVisibility() }
        let link = CADisplayLink(target: target, selector: #selector(EngageDisplayLinkTarget.invoke))
        link.add(to: .main, forMode: .common); displayLink = link
    }

    private func checkVisibility() {
        guard !destroyed, !terminal, !impressionSent, contentReady, let view = registeredView, let window = view.window,
              UIApplication.shared.applicationState == .active, view.isActuallyVisible,
              !view.convert(view.bounds, to: window).intersection(window.bounds).isEmpty else { return }
        impressionSent = true; displayLink?.invalidate(); displayLink = nil
        do {
            try creative.beginDisplayImmediately(); measurementLifecycle?.impression(); handler?(.displayed)
            Task { [weak self] in guard let self, !destroyed, !terminal else { return }; await creative.recordNativeImpression() }
        } catch { fail(error) }
    }

    private func fail(_ error: Error) {
        guard !destroyed, !terminal else { return }; terminal = true
        let engageError = error as? EngageError ?? .rendering("Native creative rendering failed")
        measurementLifecycle?.finish(); measurementLifecycle = nil
        creative.reportRenderFailure(engageError)
        imageTasks.forEach { $0.cancel() }; imageTasks.removeAll()
        displayLink?.invalidate(); displayLink = nil; videoSession?.destroy(); videoSession = nil
        removeGestures()
        handler?(.error(engageError))
    }

    private func updateReadiness() {
        guard !destroyed, !terminal, !contentReady, imagesReady, videoReady else { return }
        contentReady = true; measurementLifecycle?.loaded(); handler?(.loaded)
        if !destroyed && !terminal { startVisibilityMonitoring() }
    }

    private func removeGestures() {
        (registeredView as? EngageNativeAdView)?.onAttached = nil
        for gesture in gestures { gesture.view?.removeGestureRecognizer(gesture) }
        gestures.removeAll()
    }
}

private extension UIView {
    var isActuallyVisible: Bool {
        var current: UIView? = self
        while let view = current { if view.isHidden || view.alpha <= 0.01 { return false }; current = view.superview }
        return true
    }
}

@MainActor
public final class EngageNativeAdView: UIView {
    var onAttached: (() -> Void)?
    public let titleLabel = UILabel()
    public let mainImageView = UIImageView()
    public let bodyLabel = UILabel()
    public let videoContainer = UIView()
    public let callToActionButton = UIButton(type: .system)

    public override init(frame: CGRect) {
        super.init(frame: frame)
        titleLabel.font = .preferredFont(forTextStyle: .headline); titleLabel.numberOfLines = 2
        bodyLabel.font = .preferredFont(forTextStyle: .body); bodyLabel.numberOfLines = 3
        mainImageView.contentMode = .scaleAspectFill; mainImageView.clipsToBounds = true
        videoContainer.backgroundColor = .black; videoContainer.isHidden = true
        callToActionButton.setTitle("Learn More", for: .normal)
        let media = UIView(); media.addSubview(mainImageView); media.addSubview(videoContainer)
        [mainImageView, videoContainer].forEach { $0.translatesAutoresizingMaskIntoConstraints = false }
        NSLayoutConstraint.activate([
            mainImageView.leadingAnchor.constraint(equalTo: media.leadingAnchor), mainImageView.trailingAnchor.constraint(equalTo: media.trailingAnchor),
            mainImageView.topAnchor.constraint(equalTo: media.topAnchor), mainImageView.bottomAnchor.constraint(equalTo: media.bottomAnchor),
            videoContainer.leadingAnchor.constraint(equalTo: media.leadingAnchor), videoContainer.trailingAnchor.constraint(equalTo: media.trailingAnchor),
            videoContainer.topAnchor.constraint(equalTo: media.topAnchor), videoContainer.bottomAnchor.constraint(equalTo: media.bottomAnchor),
            media.heightAnchor.constraint(greaterThanOrEqualToConstant: 180),
        ])
        let stack = UIStackView(arrangedSubviews: [media, titleLabel, bodyLabel, callToActionButton]); stack.axis = .vertical; stack.spacing = 8
        addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        isAccessibilityElement = false
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    public override func didMoveToWindow() {
        super.didMoveToWindow()
        attemptAttachment()
    }
    public override func layoutSubviews() { super.layoutSubviews(); attemptAttachment() }
    private func attemptAttachment() {
        guard window != nil, !bounds.isEmpty else { return }
        let action = onAttached; onAttached = nil; action?()
    }
}
#endif
