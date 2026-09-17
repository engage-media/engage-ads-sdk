import EngageAdsTV
import UIKit

@main final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds); window.rootViewController = TVDemoViewController(); window.makeKeyAndVisible(); self.window = window; return true
    }
}

@MainActor final class TVDemoViewController: UIViewController {
    private let adHost = EngageTVAdContainerView(), status = UILabel()
    private var session: EngageTVInstreamAd?
    private lazy var client: EngageClient = {
        let value = ProcessInfo.processInfo.environment["ENGAGE_AD_ENDPOINT"] ?? Bundle.main.object(forInfoDictionaryKey: "EngageAdsEndpoint") as? String ?? "http://127.0.0.1:8787/openrtb/2.6/pod"
        return EngageClient(configuration: .television(endpoint: .openRTB26(url: URL(string: value)!), app: AppMetadata(bundle: Bundle.main.bundleIdentifier ?? "com.engage.example.tv")))
    }()
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black; status.textColor = .white; status.numberOfLines = 0; status.text = "Set ENGAGE_AD_ENDPOINT, then load an instream pod."
        let button = UIButton(type: .system, primaryAction: UIAction(title: "Load instream pod") { [weak self] _ in self?.loadAd() }); button.titleLabel?.font = .preferredFont(forTextStyle: .title2); adHost.backgroundColor = .darkGray
        let stack = UIStackView(arrangedSubviews: [status, button, adHost]); stack.axis = .vertical; stack.spacing = 30; view.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor), stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), adHost.heightAnchor.constraint(equalTo: adHost.widthAnchor, multiplier: 9.0 / 16.0)])
    }
    private func loadAd() { Task {
        do {
            let loaded = try await client.load(AdRequest(placementID: "sample-tv-pod", format: .instream, size: AdSize(width: 1920, height: 1080), video: VideoConstraints(podDuration: 180, maximumAds: 6)))
            session?.destroy(); let value = try EngageTVInstreamAd(creative: loaded, contentPlayer: nil, adContainer: adHost, viewController: self) { [weak self] in self?.status.text = "Event: \($0)" }
            session = value; value.load()
        } catch { status.text = "Load failed: \(error)" }
    } }
}
