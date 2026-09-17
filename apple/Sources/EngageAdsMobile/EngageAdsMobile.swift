@_exported import EngageAdsCore

#if os(iOS)
import UIKit
import WebKit

public typealias EngageEventHandler = @MainActor @Sendable (EngageEvent) -> Void

@MainActor
final class EngageDisplayLinkTarget: NSObject {
    private let callback: @MainActor () -> Void
    init(_ callback: @escaping @MainActor () -> Void) { self.callback = callback }
    @objc func invoke() { callback() }
}

public extension EngageConfiguration {
    @MainActor
    static func mobile(endpoint: Endpoint, app: AppMetadata, privacyDevice: DeviceMetadata? = nil,
                       requestTimeout: TimeInterval = 5, creativeTimeout: TimeInterval = 15,
                       diagnostics: Diagnostics = .disabled,
                       measurementBackend: (any EngageAppleMeasurementBackend)? = nil) -> EngageConfiguration {
        let detected = DeviceMetadata(operatingSystem: "iOS", operatingSystemVersion: UIDevice.current.systemVersion,
                                      make: "Apple", model: UIDevice.current.model)
        return EngageConfiguration(endpoint: endpoint, app: app, deviceCategory: .mobile,
                                   device: privacyDevice ?? detected, requestTimeout: requestTimeout,
                                   creativeTimeout: creativeTimeout, diagnostics: diagnostics,
                                   measurementBackend: measurementBackend)
    }
}

@MainActor
public final class EngageBannerView: UIView {
    private let creativeView: EngageHTMLCreativeView

    public init(creative: LoadedCreative, friendlyObstructions: [EngageFriendlyObstruction] = [],
                eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .banner, creative.kind == .html else {
            throw EngageError.unsupported("Banner requires a banner HTML creative")
        }
        creativeView = EngageHTMLCreativeView(creative: creative, placementType: .inline,
                                              friendlyObstructions: friendlyObstructions,
                                              eventHandler: eventHandler)
        super.init(frame: .zero)
        clipsToBounds = true
        addSubview(creativeView)
        creativeView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            creativeView.leadingAnchor.constraint(equalTo: leadingAnchor), creativeView.trailingAnchor.constraint(equalTo: trailingAnchor),
            creativeView.topAnchor.constraint(equalTo: topAnchor), creativeView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    public func destroy() { creativeView.destroy() }
}

@MainActor
public final class EngageInterstitialAd {
    private let creative: LoadedCreative
    private let eventHandler: EngageEventHandler?
    private let friendlyObstructions: [EngageFriendlyObstruction]
    private weak var presentedController: UIViewController?
    private var presentedAdView: EngageHTMLCreativeView?
    private var used = false

    public init(creative: LoadedCreative, friendlyObstructions: [EngageFriendlyObstruction] = [],
                eventHandler: EngageEventHandler? = nil) throws {
        guard creative.format == .interstitial, creative.kind == .html else {
            throw EngageError.unsupported("HTML interstitial requires an interstitial HTML creative")
        }
        self.creative = creative; self.eventHandler = eventHandler
        self.friendlyObstructions = friendlyObstructions
    }

    public func present(from presenter: UIViewController) throws {
        guard !used else { throw EngageError.invalidState(expected: AdState.ready.rawValue, actual: creative.state.rawValue) }
        used = true
        let controller = UIViewController()
        controller.modalPresentationStyle = .fullScreen
        controller.view.backgroundColor = .black
        let close = UIButton(type: .system)
        close.setTitle("Close", for: .normal); close.backgroundColor = UIColor.black.withAlphaComponent(0.65); close.tintColor = .white
        close.accessibilityLabel = "Close ad"
        let closeObstruction = try EngageFriendlyObstruction(view: close, purpose: .closeAd, detailedReason: "Close ad")
        let adView = EngageHTMLCreativeView(creative: creative, placementType: .interstitial,
                                            friendlyObstructions: friendlyObstructions + [closeObstruction],
                                            eventHandler: eventHandler)
        adView.onClose = { [weak self, weak controller] in
            self?.presentedAdView = nil
            self?.presentedController = nil
            controller?.dismiss(animated: true) { self?.eventHandler?(.dismissed) }
        }
        close.addAction(UIAction { [weak adView] _ in adView?.requestClose() }, for: .touchUpInside)
        controller.view.addSubview(adView)
        controller.view.addSubview(close); close.translatesAutoresizingMaskIntoConstraints = false
        adView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            adView.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor), adView.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor),
            adView.topAnchor.constraint(equalTo: controller.view.topAnchor), adView.bottomAnchor.constraint(equalTo: controller.view.bottomAnchor),
            close.topAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.topAnchor, constant: 8),
            close.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -8),
            close.widthAnchor.constraint(greaterThanOrEqualToConstant: 56), close.heightAnchor.constraint(equalToConstant: 44),
        ])
        presentedController = controller
        presentedAdView = adView
        presenter.present(controller, animated: true)
    }

    public func destroy() {
        if presentedAdView == nil { creative.destroy() }
        presentedAdView?.destroy(); presentedAdView = nil
        presentedController?.dismiss(animated: false)
        presentedController = nil
    }
}

enum MRAIDPlacement: String { case inline, interstitial }

@MainActor
final class EngageHTMLCreativeView: UIView, @preconcurrency WKNavigationDelegate {
    let creative: LoadedCreative
    private var webView: WKWebView?
    var onClose: (() -> Void)?
    private let handler: EngageEventHandler?
    private let bridge: MRAIDBridge
    private var contentReady = false
    private var displayStarted = false
    private var destroyed = false
    private var readinessTask: Task<Void, Never>?
    private var visibilityLink: CADisplayLink?
    private var measurementLifecycle: MeasurementSessionLifecycle?
    private let friendlyObstructions: [EngageFriendlyObstruction]
    private let measurementPrepared: Bool

    init(creative: LoadedCreative, placementType: MRAIDPlacement,
         friendlyObstructions: [EngageFriendlyObstruction], eventHandler: EngageEventHandler?) {
        self.creative = creative; self.handler = eventHandler
        self.friendlyObstructions = friendlyObstructions
        let controller = WKUserContentController()
        let bootstrap = "window.EngageMraidNative={postMessage:function(m){window.webkit.messageHandlers.engageMraid.postMessage(m);}};"
        controller.addUserScript(WKUserScript(source: bootstrap, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        if let url = Bundle.module.url(forResource: "mraid", withExtension: "js"), let js = try? String(contentsOf: url, encoding: .utf8) {
            controller.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        let configuration = WKWebViewConfiguration(); configuration.userContentController = controller
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let preparationTarget = EngageAppleHTMLPreparationTarget(
            webViewConfiguration: configuration,
            creativeMarkupUTF8Count: creative.markup.utf8.count
        )
        measurementPrepared = creative.prepareMeasurement(creativeType: .html, target: preparationTarget)
        let webView = WKWebView(frame: .zero, configuration: configuration)
        self.webView = webView
        bridge = MRAIDBridge(placement: placementType)
        super.init(frame: .zero)
        bridge.attach(webView: webView, hostView: self)
        bridge.onClose = { [weak self] in self?.close() }
        bridge.onClick = { [weak self] in self?.handler?(.clicked) }
        controller.add(bridge, name: "engageMraid")
        webView.navigationDelegate = self; webView.isOpaque = false; webView.backgroundColor = .clear
        addSubview(webView); webView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: leadingAnchor), webView.trailingAnchor.constraint(equalTo: trailingAnchor),
            webView.topAnchor.constraint(equalTo: topAnchor), webView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        guard creative.state == .ready else {
            destroyed = true
            bridge.unload(); webView.navigationDelegate = nil; webView.removeFromSuperview(); self.webView = nil
            handler?(.error(.destroyed))
            return
        }
        let html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body style=\"margin:0;overflow:hidden\">\(creative.markup)</body></html>"
        webView.loadHTMLString(html, baseURL: nil)
        readinessTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(creative.creativeTimeout * 1_000_000_000))
            guard !Task.isCancelled, !self.contentReady, !self.destroyed else { return }
            self.fail(EngageError.creativeTimeout)
        }
        let target = EngageDisplayLinkTarget { [weak self] in self?.updateVisibility() }
        let link = CADisplayLink(target: target, selector: #selector(EngageDisplayLinkTarget.invoke))
        link.add(to: .main, forMode: .common); visibilityLink = link
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() { super.didMoveToWindow(); attemptDisplay() }
    override func layoutSubviews() { super.layoutSubviews(); bridge.updateGeometry(); attemptDisplay() }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !destroyed, !contentReady else { return }; readinessTask?.cancel(); readinessTask = nil
        if measurementPrepared {
            let target = EngageAppleMeasurementTarget(creativeType: .html, adView: webView, webView: webView,
                                                       friendlyObstructions: friendlyObstructions)
            measurementLifecycle = creative.makeMeasurementSession(creativeType: .html, target: target)
        }
        bridge.allowsTwoPartExpand = measurementLifecycle == nil
        guard !destroyed, creative.state == .ready else {
            handler?(.error(.destroyed)); cleanup(); return
        }
        contentReady = true; measurementLifecycle?.loaded(); handler?(.loaded)
        guard !destroyed else { return }
        bridge.ready(); attemptDisplay()
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        let scheme = url.scheme?.lowercased() ?? ""
        if navigationAction.targetFrame == nil {
            if navigationAction.navigationType == .linkActivated,
               !["javascript", "data", "file", "about"].contains(scheme) {
                handler?(.clicked); UIApplication.shared.open(url)
            }
            decisionHandler(.cancel); return
        }
        guard navigationAction.targetFrame?.isMainFrame == true else { decisionHandler(.allow); return }
        if ["javascript", "data", "file", "about"].contains(scheme) && contentReady { decisionHandler(.cancel); return }
        if navigationAction.navigationType == .linkActivated {
            guard !["javascript", "data", "file", "about"].contains(scheme) else { decisionHandler(.cancel); return }
            handler?(.clicked); UIApplication.shared.open(url); decisionHandler(.cancel)
        } else if contentReady { decisionHandler(.cancel) }
        else { decisionHandler(.allow) }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail(error) }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        fail(EngageError.rendering("HTML web content process terminated"))
    }

    func destroy() {
        guard !destroyed else { return }
        creative.destroy(); cleanup()
    }

    private func attemptDisplay() {
        guard !destroyed, contentReady, !displayStarted, let window, !bounds.isEmpty, isActuallyVisible,
              !convert(bounds, to: window).intersection(window.bounds).isEmpty,
              UIApplication.shared.applicationState == .active else { bridge.updateVisibility(); return }
        displayStarted = true
        do {
            try creative.beginDisplayImmediately(); measurementLifecycle?.impression(); handler?(.displayed)
            if !destroyed { bridge.updateVisibility() }
        }
        catch let error as EngageError { fail(error) }
        catch { fail(EngageError.rendering("HTML display transition failed")) }
    }

    private func close() {
        guard !destroyed else { return }
        if creative.state == .displaying { creative.finish() } else { creative.destroy() }
        let action = onClose; cleanup(); action?()
    }
    func requestClose() { close() }
    private func updateVisibility() { bridge.updateVisibility(); attemptDisplay() }
    private func fail(_ error: Error) {
        guard !destroyed else { return }
        let engageError = error as? EngageError ?? .rendering("HTML creative failed to load")
        creative.reportRenderFailure(engageError)
        handler?(.error(engageError)); cleanup()
    }
    private func cleanup() {
        destroyed = true; readinessTask?.cancel(); readinessTask = nil; visibilityLink?.invalidate(); visibilityLink = nil
        measurementLifecycle?.finish(); measurementLifecycle = nil
        bridge.unload(); bridge.onClose = nil; bridge.onClick = nil
        onClose = nil
        webView?.navigationDelegate = nil; webView?.stopLoading(); webView?.removeFromSuperview(); webView = nil
        removeFromSuperview()
    }
    private var isActuallyVisible: Bool {
        var current: UIView? = self
        while let view = current { if view.isHidden || view.alpha <= 0.01 { return false }; current = view.superview }
        return true
    }
}
#endif
