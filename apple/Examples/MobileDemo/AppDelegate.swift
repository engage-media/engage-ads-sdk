import EngageAdsMobile
import UIKit

@main final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds); window.rootViewController = DemoViewController(); window.makeKeyAndVisible(); self.window = window; return true
    }
}

@MainActor final class DemoViewController: UIViewController {
    private let bannerHost = UIView(), videoHost = UIView(), status = UILabel()
    private var banner: EngageBannerView?
    private var video: EngageInstreamAd?
    private lazy var client: EngageClient = {
        let value = ProcessInfo.processInfo.environment["ENGAGE_AD_ENDPOINT"] ?? Bundle.main.object(forInfoDictionaryKey: "EngageAdsEndpoint") as? String ?? "http://127.0.0.1:8787/openrtb/2.6/auto"
        return EngageClient(configuration: .mobile(endpoint: .openRTB26(url: URL(string: value)!), app: AppMetadata(bundle: Bundle.main.bundleIdentifier ?? "com.engage.example.mobile")))
    }()

    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground; videoHost.backgroundColor = .black; status.numberOfLines = 0
        let bannerButton = UIButton(type: .system, primaryAction: UIAction(title: "Load banner") { [weak self] _ in self?.loadBanner() })
        let videoButton = UIButton(type: .system, primaryAction: UIAction(title: "Load instream VAST") { [weak self] _ in self?.loadVideo() })
        let stack = UIStackView(arrangedSubviews: [status, bannerButton, bannerHost, videoButton, videoHost]); stack.axis = .vertical; stack.spacing = 16
        view.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20), stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20), stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20), bannerHost.heightAnchor.constraint(equalToConstant: 50), videoHost.heightAnchor.constraint(equalTo: videoHost.widthAnchor, multiplier: 9.0 / 16.0)])
        status.text = "Set ENGAGE_AD_ENDPOINT or EngageAdsEndpoint to your OpenRTB 2.6 endpoint."
    }

    private func loadBanner() { Task {
        do {
            let loaded = try await client.load(AdRequest(placementID: "sample-banner", format: .banner, size: AdSize(width: 320, height: 50)))
            banner?.destroy(); let ad = try EngageBannerView(creative: loaded) { [weak self] in self?.show($0) }; banner = ad
            bannerHost.addSubview(ad); ad.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([ad.leadingAnchor.constraint(equalTo: bannerHost.leadingAnchor), ad.trailingAnchor.constraint(equalTo: bannerHost.trailingAnchor), ad.topAnchor.constraint(equalTo: bannerHost.topAnchor), ad.bottomAnchor.constraint(equalTo: bannerHost.bottomAnchor)])
        } catch { status.text = "Banner: \(error)" }
    } }

    private func loadVideo() { Task {
        do {
            let loaded = try await client.load(AdRequest(placementID: "sample-video", format: .instream, size: AdSize(width: 640, height: 360), video: VideoConstraints(podDuration: 120, maximumAds: 4)))
            video?.destroy(); let session = try EngageInstreamAd(creative: loaded, contentPlayer: nil, adContainer: videoHost, viewController: self) { [weak self] in self?.show($0) }
            video = session; session.load()
        } catch { status.text = "Video: \(error)" }
    } }
    private func show(_ event: EngageEvent) { status.text = "Event: \(event)" }
}
