#if os(iOS)
import AVKit
import UIKit
import WebKit

@MainActor
final class MRAIDBridge: NSObject, @preconcurrency WKScriptMessageHandler, @preconcurrency WKNavigationDelegate,
                         @preconcurrency UIAdaptivePresentationControllerDelegate {
    private weak var webView: WKWebView?
    private weak var hostView: UIView?
    private let placement: MRAIDPlacement
    private var state = "loading"
    private weak var originalSuperview: UIView?
    private var originalIndex: Int?
    private var originalFrame: CGRect = .zero
    private var overlay: UIView?
    private var closeButton: UIButton?
    private var expandedWebView: WKWebView?
    private var expandedLoaded = false
    private var originalTranslatesAutoresizingMask = true
    private var originalConstraints: [NSLayoutConstraint] = []
    private var playerController: AVPlayerViewController?
    private var orientationLocked = false
    private var priorOrientationMask: UIInterfaceOrientationMask?
    private var lastUserInteraction = Date.distantPast
    private var commandWindowStarted = ProcessInfo.processInfo.systemUptime
    private var commandsInWindow = 0
    private var rateErrorSent = false
    var onClose: (() -> Void)?
    var onClick: (() -> Void)?
    var allowsTwoPartExpand = true

    init(placement: MRAIDPlacement) { self.placement = placement }

    func attach(webView: WKWebView, hostView: UIView) {
        self.webView = webView; self.hostView = hostView
        let observer = TouchObserverGestureRecognizer { [weak self] in self?.lastUserInteraction = Date() }
        observer.cancelsTouchesInView = false; webView.addGestureRecognizer(observer)
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if now - commandWindowStarted >= 1 {
            commandWindowStarted = now; commandsInWindow = 0; rateErrorSent = false
        }
        guard commandsInWindow < 64 else {
            if !rateErrorSent { rateErrorSent = true; error("Bridge command rate limit exceeded", action: "bridge") }
            return
        }
        commandsInWindow += 1
        guard let serialized = message.body as? String else {
            emitBridgeLimitErrorOnce("Bridge message must be a string"); return
        }
        guard serialized.utf16.count <= 65_536 else {
            emitBridgeLimitErrorOnce("Bridge message exceeds the size limit"); return
        }
        guard !Self.exceedsJSONNestingLimit(serialized, limit: 8) else {
            emitBridgeLimitErrorOnce("Bridge message exceeds the nesting limit"); return
        }
        receive(serialized)
    }

    func ready() {
        state = "default"
        send(readyMessage())
        updateVisibility()
        send(["type": "audio", "volume": NSNull()])
    }

    private func readyMessage() -> [String: Any] {
        let geometry = geometryValues()
        return ["type": "ready", "state": state, "placementType": placement.rawValue,
              "screenSize": geometry.screen, "maxSize": geometry.maximum,
              "currentPosition": geometry.current, "defaultPosition": geometry.defaultPosition,
              "currentAppOrientation": orientationObject(), "location": NSNull(),
              "supports": ["sms": false, "tel": false, "calendar": false, "storePicture": false, "inlineVideo": true, "location": false]]
    }

    func updateGeometry() {
        guard state != "loading" else { return }
        let g = geometryValues()
        send(["type": "geometry", "screenSize": g.screen, "maxSize": g.maximum,
              "currentPosition": g.current, "defaultPosition": g.defaultPosition,
              "currentAppOrientation": orientationObject()])
    }

    func updateVisibility() {
        guard state != "loading" else { return }
        guard let view = hostView, let window = view.window else {
            send(["type": "visibility", "viewable": false, "exposedPercentage": 0,
                  "visibleRectangle": rectObject(.zero), "occlusionRectangles": []]); return
        }
        let rect = view.convert(view.bounds, to: window)
        let visible = rect.intersection(window.bounds)
        let area = max(rect.width * rect.height, 0)
        let exposed = area > 0 ? max(0, min(100, visible.width * visible.height / area * 100)) : 0
        var hierarchyVisible = true; var ancestor: UIView? = view
        while let candidate = ancestor { if candidate.isHidden || candidate.alpha <= 0.01 { hierarchyVisible = false; break }; ancestor = candidate.superview }
        let active = UIApplication.shared.applicationState == .active && hierarchyVisible && !visible.isEmpty
        send(["type": "visibility", "viewable": active, "exposedPercentage": active ? exposed : 0,
              "visibleRectangle": rectObject(active ? visible : .zero), "occlusionRectangles": []])
    }

    func unload() {
        restoreIfExpanded()
        playerController?.player?.pause(); playerController?.dismiss(animated: false); playerController = nil
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "engageMraid")
        state = "hidden"; send(["type": "state", "state": state])
    }

    private func receive(_ body: String) {
        let object: [String: Any]?
        if let data = body.data(using: .utf8) { object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }
        else { object = nil }
        guard let object, object["id"] is NSNumber, let command = object["command"] as? String,
              let args = object["args"] as? [String: Any] else { error("Malformed bridge message", action: "bridge"); return }
        switch command {
        case "open": open(args)
        case "close": close()
        case "expand": expand(args)
        case "resize": resize(args)
        case "setOrientationProperties": setOrientation(args)
        case "playVideo": playVideo(args)
        case "storePicture": error("storePicture is not supported", action: command)
        case "createCalendarEvent": error("createCalendarEvent is not supported", action: command)
        case "unload": unload(); onClose?()
        default: error("Unknown command", action: command)
        }
    }

    private func open(_ args: [String: Any]) {
        guard Date().timeIntervalSince(lastUserInteraction) < 2 else { error("open requires a recent user interaction", action: "open"); return }
        guard let string = args["url"] as? String, let url = URL(string: string), let scheme = url.scheme?.lowercased(),
              !["javascript", "data", "file", "about"].contains(scheme) else { error("Invalid open URL", action: "open"); return }
        onClick?(); UIApplication.shared.open(url)
    }

    private func close() {
        if state == "expanded" || state == "resized" {
            restoreIfExpanded(); state = "default"; send(["type": "state", "state": state]); updateGeometry(); updateVisibility()
        } else { state = "hidden"; send(["type": "state", "state": state]); onClose?() }
    }

    private func expand(_ args: [String: Any]) {
        guard state == "default", let view = hostView, let window = view.window, let parent = view.superview else {
            error("Creative cannot expand from its current state", action: "expand"); return
        }
        if args["url"] is String, !allowsTwoPartExpand {
            error("Two-part expansion is unavailable while measurement is active", action: "expand"); return
        }
        originalSuperview = parent; originalIndex = parent.subviews.firstIndex(of: view); originalFrame = view.frame
        originalTranslatesAutoresizingMask = view.translatesAutoresizingMaskIntoConstraints
        originalConstraints = parent.constraints.filter { ($0.firstItem as? UIView) === view || ($0.secondItem as? UIView) === view }
        NSLayoutConstraint.deactivate(originalConstraints)
        let cover = UIView(frame: window.bounds); cover.backgroundColor = .clear; cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        window.addSubview(cover); overlay = cover
        view.removeFromSuperview(); view.translatesAutoresizingMaskIntoConstraints = true; view.frame = cover.bounds; view.autoresizingMask = [.flexibleWidth, .flexibleHeight]; cover.addSubview(view)
        if let string = args["url"] as? String, let url = URL(string: string), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), let source = webView {
            let expanded = WKWebView(frame: view.bounds, configuration: source.configuration); expanded.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            expanded.navigationDelegate = self; expandedLoaded = false
            let observer = TouchObserverGestureRecognizer { [weak self] in self?.lastUserInteraction = Date() }; observer.cancelsTouchesInView = false; expanded.addGestureRecognizer(observer)
            view.addSubview(expanded); expandedWebView = expanded; expanded.load(URLRequest(url: url))
        }
        installCloseButton(in: cover, position: ((args["properties"] as? [String: Any])?["customClosePosition"] as? String))
        state = "expanded"; send(["type": "state", "state": state]); updateGeometry(); updateVisibility()
    }

    private func resize(_ args: [String: Any]) {
        guard let properties = args["properties"] as? [String: Any], let view = hostView, let window = view.window else {
            error("Missing resize properties", action: "resize"); return
        }
        guard let width = number(properties["width"]), let height = number(properties["height"]), width > 0, height > 0 else {
            error("Invalid resize dimensions", action: "resize"); return
        }
        if state == "default" { expand([:]) }
        guard state == "expanded" || state == "resized" else { return }
        let x = number(properties["offsetX"]) ?? 0, y = number(properties["offsetY"]) ?? 0
        let defaultInWindow = originalSuperview?.convert(originalFrame, to: window) ?? originalFrame
        var frame = CGRect(x: defaultInWindow.minX + x, y: defaultInWindow.minY + y, width: width, height: height)
        if (properties["allowOffscreen"] as? Bool) != true {
            frame.origin.x = min(max(0, frame.minX), max(0, window.bounds.width - frame.width))
            frame.origin.y = min(max(0, frame.minY), max(0, window.bounds.height - frame.height))
        }
        view.frame = frame; view.autoresizingMask = []
        installCloseButton(in: view, position: properties["customClosePosition"] as? String)
        state = "resized"; send(["type": "state", "state": state]); updateGeometry(); updateVisibility()
    }

    private func setOrientation(_ args: [String: Any]) {
        guard args["allowOrientationChange"] is Bool, args["forceOrientation"] is String else {
            error("Invalid orientation properties", action: "setOrientationProperties"); return
        }
        guard #available(iOS 16.0, *), let scene = hostView?.window?.windowScene else {
            error("Orientation forcing requires iOS 16 or later", action: "setOrientationProperties"); return
        }
        let force = args["forceOrientation"] as? String
        let allow = args["allowOrientationChange"] as? Bool ?? true
        guard force == "portrait" || force == "landscape" || force == "none" else { error("Invalid forced orientation", action: "setOrientationProperties"); return }
        if priorOrientationMask == nil { priorOrientationMask = hostView?.nearestViewController?.supportedInterfaceOrientations }
        let currentMask: UIInterfaceOrientationMask = scene.interfaceOrientation.isLandscape ? .landscape : .portrait
        let mask: UIInterfaceOrientationMask = force == "portrait" ? .portrait : (force == "landscape" ? .landscape : (allow ? .all : currentMask))
        orientationLocked = !allow
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.orientationLocked = false
                self?.error("Orientation request was denied", action: "setOrientationProperties")
                self?.updateGeometry()
            }
        }
    }

    private func playVideo(_ args: [String: Any]) {
        guard let string = args["url"] as? String, let url = URL(string: string), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let presenter = hostView?.nearestViewController else { error("Invalid video URL", action: "playVideo"); return }
        playerController?.player?.pause(); playerController?.dismiss(animated: false)
        let player = AVPlayer(url: url); let controller = AVPlayerViewController(); controller.player = player
        controller.presentationController?.delegate = self
        playerController = controller; presenter.present(controller, animated: true) { player.play() }
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        guard presentationController.presentedViewController === playerController else { return }
        playerController?.player?.pause(); playerController = nil
    }

    private func restoreIfExpanded() {
        guard let view = hostView, let parent = originalSuperview else {
            expandedWebView?.navigationDelegate = nil; expandedWebView?.stopLoading(); expandedWebView?.removeFromSuperview(); expandedWebView = nil
            expandedLoaded = false
            closeButton?.removeFromSuperview(); closeButton = nil
            overlay?.removeFromSuperview(); overlay = nil
            restoreOrientation()
            return
        }
        expandedWebView?.navigationDelegate = nil; expandedWebView?.stopLoading(); expandedWebView?.removeFromSuperview(); expandedWebView = nil
        expandedLoaded = false; restoreOrientation()
        closeButton?.removeFromSuperview(); closeButton = nil
        view.removeFromSuperview(); view.translatesAutoresizingMaskIntoConstraints = originalTranslatesAutoresizingMask; view.frame = originalFrame; view.autoresizingMask = []
        parent.insertSubview(view, at: min(originalIndex ?? parent.subviews.count, parent.subviews.count))
        NSLayoutConstraint.activate(originalConstraints); originalConstraints.removeAll()
        overlay?.removeFromSuperview(); overlay = nil; originalSuperview = nil
    }

    private func geometryValues() -> (screen: [String: Any], maximum: [String: Any], current: [String: Any], defaultPosition: [String: Any]) {
        guard let view = hostView, let window = view.window else {
            let z = rectObject(.zero); return (sizeObject(.zero), sizeObject(.zero), z, z)
        }
        let screen = window.screen.bounds.size, rect = view.convert(view.bounds, to: window)
        let defaultRect = (state == "expanded" || state == "resized") ? (originalSuperview?.convert(originalFrame, to: window) ?? originalFrame) : rect
        return (sizeObject(screen), sizeObject(window.bounds.size), rectObject(rect), rectObject(defaultRect))
    }

    private func send(_ message: [String: Any]) {
        send(message, to: webView)
        send(message, to: expandedWebView)
    }
    private func send(_ message: [String: Any], to target: WKWebView?) {
        guard JSONSerialization.isValidJSONObject(message), let data = try? JSONSerialization.data(withJSONObject: message),
              let json = String(data: data, encoding: .utf8) else { return }
        target?.evaluateJavaScript("window.__engageMraid&&window.__engageMraid.receive(\(json));")
    }
    private func error(_ message: String, action: String) { send(["type": "error", "message": message, "action": action]) }
    private func emitBridgeLimitErrorOnce(_ message: String) {
        guard !rateErrorSent else { return }
        rateErrorSent = true
        error(message, action: "bridge")
    }
    private func installCloseButton(in container: UIView, position: String?) {
        closeButton?.removeFromSuperview()
        let button = UIButton(type: .system); button.setTitle("Close", for: .normal); button.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        button.tintColor = .white; button.accessibilityLabel = "Close ad"; button.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        button.frame = CGRect(x: container.bounds.width - 72, y: 16, width: 56, height: 44); button.autoresizingMask = [.flexibleLeftMargin, .flexibleBottomMargin]
        if position?.contains("left") == true { button.frame.origin.x = 16; button.autoresizingMask = [.flexibleRightMargin, .flexibleBottomMargin] }
        if position?.contains("bottom") == true { button.frame.origin.y = container.bounds.height - 60; button.autoresizingMask.insert(.flexibleTopMargin) }
        container.addSubview(button); closeButton = button
    }
    @objc private func closeTapped() { close() }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard webView === expandedWebView, let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if !expandedLoaded, ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            decisionHandler(.allow)
            return
        }
        let scheme = url.scheme?.lowercased() ?? ""
        if navigationAction.navigationType == .linkActivated, !["javascript", "data", "file", "about"].contains(scheme),
           Date().timeIntervalSince(lastUserInteraction) < 2 { UIApplication.shared.open(url) }
        decisionHandler(.cancel)
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView === expandedWebView else { return }; expandedLoaded = true
        send(readyMessage(), to: webView)
        send(["type": "state", "state": state], to: webView)
        updateGeometry(); updateVisibility()
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard webView === expandedWebView else { return }
        error("Expanded web content process terminated", action: "expand")
        close()
    }
    private func restoreOrientation() {
        guard #available(iOS 16.0, *), let mask = priorOrientationMask, let scene = hostView?.window?.windowScene else { priorOrientationMask = nil; return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)); priorOrientationMask = nil; orientationLocked = false
    }
    private func sizeObject(_ s: CGSize) -> [String: Any] { ["width": s.width, "height": s.height] }
    private func rectObject(_ r: CGRect) -> [String: Any] { ["x": r.minX, "y": r.minY, "width": r.width, "height": r.height] }
    private func orientationObject() -> [String: Any] {
        let landscape = hostView?.window?.windowScene?.interfaceOrientation.isLandscape
            ?? hostView?.window.map { $0.bounds.width > $0.bounds.height } ?? false
        return ["orientation": landscape ? "landscape" : "portrait", "locked": orientationLocked]
    }
    private func number(_ value: Any?) -> CGFloat? { (value as? NSNumber).map { CGFloat(truncating: $0) } }
    private static func exceedsJSONNestingLimit(_ value: String, limit: Int) -> Bool {
        var depth = 0, inString = false, escaped = false
        for unit in value.utf16 {
            if inString {
                if escaped { escaped = false }
                else if unit == 0x5c { escaped = true }
                else if unit == 0x22 { inString = false }
                continue
            }
            if unit == 0x22 { inString = true }
            else if unit == 0x7b || unit == 0x5b { depth += 1; if depth > limit { return true } }
            else if unit == 0x7d || unit == 0x5d { depth = max(0, depth - 1) }
        }
        return false
    }
}

private final class TouchObserverGestureRecognizer: UIGestureRecognizer {
    private let callback: @MainActor () -> Void
    init(callback: @escaping @MainActor () -> Void) { self.callback = callback; super.init(target: nil, action: nil) }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) { callback(); state = .failed }
}

private extension UIView {
    var nearestViewController: UIViewController? {
        var responder: UIResponder? = self
        while let current = responder { if let controller = current as? UIViewController { return controller }; responder = current.next }
        return window?.rootViewController
    }
}
#endif
