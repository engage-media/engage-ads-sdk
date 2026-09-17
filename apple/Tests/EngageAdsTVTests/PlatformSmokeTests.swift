#if os(tvOS)
import XCTest
@testable import EngageAdsTV

final class EngageAdsTVSmokeTests: XCTestCase {
    @MainActor func testConfigurationUsesTVCategory() {
        let endpoint = Endpoint.openRTB26(url: URL(string: "https://example.invalid/openrtb")!)
        let value = EngageConfiguration.television(endpoint: endpoint, app: AppMetadata(bundle: "test"))
        XCTAssertEqual(value.deviceCategory, .tv)
    }
    @MainActor func testTVContainerParticipatesInFocusEngine() { XCTAssertTrue(EngageTVAdContainerView().canBecomeFocused) }
}
#endif
