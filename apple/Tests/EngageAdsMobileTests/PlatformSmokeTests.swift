#if os(iOS)
import XCTest
@testable import EngageAdsMobile

final class EngageAdsMobileSmokeTests: XCTestCase {
    @MainActor func testConfigurationUsesMobileCategory() {
        let endpoint = Endpoint.openRTB26(url: URL(string: "https://example.invalid/openrtb")!)
        let value = EngageConfiguration.mobile(endpoint: endpoint, app: AppMetadata(bundle: "test"))
        XCTAssertEqual(value.deviceCategory, .mobile)
    }
    @MainActor func testCustomContentControllerContract() {
        let controller = TestContentController(); controller.resumeContent(); XCTAssertTrue(controller.isPlaying)
        controller.pauseContent(); XCTAssertFalse(controller.isPlaying)
    }
}

@MainActor private final class TestContentController: EngageContentPlaybackController {
    var isPlaying = false
    func pauseContent() { isPlaying = false }
    func resumeContent() { isPlaying = true }
}
#endif
