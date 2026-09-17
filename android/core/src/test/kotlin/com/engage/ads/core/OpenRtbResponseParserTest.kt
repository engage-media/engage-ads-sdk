package com.engage.ads.core

import com.engage.ads.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class OpenRtbResponseParserTest {
    @Test fun deeplyNestedOuterAndNativeJsonAreRejectedBeforeParsing() {
        val nested = "{\"value\":".repeat(ResourceLimits.MAX_JSON_DEPTH + 1) + "0" + "}".repeat(ResourceLimits.MAX_JSON_DEPTH + 1)
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse(nested, "request", "imp", banner) }

        val nativeRequest = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())
        val parsed = ParsedBid("request", "bid", "imp", null, null, null, null)
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.normalizeFetchedMarkup(parsed, nested, nativeRequest) }
    }

    @Test fun nativeAssetCountIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeRequest(assets = (0..ResourceLimits.MAX_NATIVE_ASSETS).map { NativeAssetRequest(it, NativeAssetType.DATA) })
        }
    }

    private val diagnostics = mutableListOf<DiagnosticEvent>()
    private val parser = OpenRtbResponseParser(DiagnosticListener(diagnostics::add), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
    private val banner = AdRequest("slot", AdFormat.BANNER, AdSize(320, 50))

    @Test fun emptySeatBidIsNoFill() {
        assertEquals(AuctionResult.NoFill, parser.parse("""{"id":"request","seatbid":[]}""", "request", "imp", banner))
    }

    @Test fun emptyBidArrayIsNoFill() {
        assertEquals(AuctionResult.NoFill, parser.parse("""{"id":"request","seatbid":[{"bid":[]}]}""", "request", "imp", banner))
    }

    @Test fun malformedSeatBidTypeIsError() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse("""{"id":"request","seatbid":{}}""", "request", "imp", banner) }
    }

    @Test fun exactlyOneMatchingBidIsAccepted() {
        val result = parser.parse(response(adm = "<div>ad</div>"), "request", "imp", banner) as AuctionResult.Ready
        assertEquals(CreativeKind.HTML, result.creative.kind)
        assertEquals("bid", result.creative.bidId)
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"), result.creative.expiresAt)
    }

    @Test fun unmatchedBidIsRejected() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse("""{"id":"request","seatbid":[{"bid":[{"id":"bid","impid":"other","price":1,"adm":"x"}]}]}""", "request", "imp", banner)
        }
    }

    @Test fun extraUnknownBidIsRejected() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse("""{"id":"request","seatbid":[{"bid":[{"id":"bid","impid":"imp","price":1,"adm":"x"},{"id":"other","impid":"other","price":1,"adm":"x"}]}]}""", "request", "imp", banner)
        }
    }

    @Test fun numericStringPriceIsRejected() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse(response(adm = "x", price = "\"1.0\""), "request", "imp", banner) }
    }

    @Test fun nonFiniteAndNegativePricesAreRejected() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse(response(adm = "x", price = "-0.1"), "request", "imp", banner) }
    }

    @Test fun missingAdmUsesNurlForMarkup() {
        val result = parser.parse(response(adm = null, nurl = "https://ads.example/markup"), "request", "imp", banner) as AuctionResult.MarkupRequired
        assertEquals("https://ads.example/markup", result.url)
    }

    @Test fun unresolvedNoticeMacroIsRejectedAndDiagnosed() {
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse(response(adm = "x", nurl = "https://ads.example/${'$'}{AUCTION_PRICE}"), "request", "imp", banner) }
        assertEquals("unresolved_notice_macro", diagnostics.single().code)
    }

    @Test fun nativeRequiredAssetsAndTypesAreValidated() {
        val request = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())
        val adm = """{"native":{"assets":[{"id":1,"title":{"text":"Title"}},{"id":2,"img":{"url":"https://cdn.example/image.png"}}],"link":{"url":"https://click.example"},"imptrackers":["https://track.example/imp"]}}"""
        val result = parser.parse(response(adm = adm.replace("\"", "\\\""), rawAdm = true), "request", "imp", request) as AuctionResult.Ready
        assertEquals(2, result.creative.native?.assets?.size)
    }

    @Test fun nativeTrackingAndImageUrlsMustBeHttp() {
        val request = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())
        val adm = """{"native":{"assets":[{"id":1,"title":{"text":"Title"}},{"id":2,"img":{"url":"file:///private/image.png"}}],"link":{"url":"https://click.example"},"imptrackers":["https://track.example/imp"]}}"""
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response(adm = adm.replace("\"", "\\\""), rawAdm = true), "request", "imp", request)
        }
    }

    @Test fun nativeClickRejectsExecutableSchemesButAllowsAppLinks() {
        val request = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())
        fun adm(click: String) = """{"native":{"assets":[{"id":1,"title":{"text":"Title"}},{"id":2,"img":{"url":"https://cdn.example/image.png"}}],"link":{"url":"$click"}}}"""
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response(adm = adm("javascript:alert(1)").replace("\"", "\\\""), rawAdm = true), "request", "imp", request)
        }
        val accepted = parser.parse(response(adm = adm("engage-app://creative/1").replace("\"", "\\\""), rawAdm = true), "request", "imp", request)
        assertTrue(accepted is AuctionResult.Ready)
    }

    @Test fun nativeAssetIdsAndStringsHaveStrictJsonTypes() {
        val request = AdRequest("native", AdFormat.NATIVE, native = NativeRequest())
        val adm = """{"native":{"assets":[{"id":"1","title":{"text":"Title"}},{"id":2,"img":{"url":"https://cdn.example/image.png"}}],"link":{"url":"https://click.example"}}}"""
        assertCode(EngageError.Code.MALFORMED_RESPONSE) {
            parser.parse(response(adm = adm.replace("\"", "\\\""), rawAdm = true), "request", "imp", request)
        }
    }

    @Test fun rewardedRejectsTwoMatchingBids() {
        val response = """{"id":"request","seatbid":[{"bid":[{"id":"a","impid":"imp","price":1,"adm":"<VAST></VAST>"},{"id":"b","impid":"imp","price":2,"adm":"<VAST></VAST>"}]}]}"""
        assertCode(EngageError.Code.MALFORMED_RESPONSE) { parser.parse(response, "request", "imp", AdRequest("reward", AdFormat.REWARDED)) }
    }

    private fun response(adm: String?, nurl: String? = null, price: String = "1.0", rawAdm: Boolean = false): String {
        val admPart = adm?.let { if (rawAdm) ",\"adm\":\"$it\"" else ",\"adm\":${org.json.JSONObject.quote(it)}" }.orEmpty()
        val nurlPart = nurl?.let { ",\"nurl\":${org.json.JSONObject.quote(it)}" }.orEmpty()
        return """{"id":"request","seatbid":[{"bid":[{"id":"bid","impid":"imp","price":$price,"exp":60$admPart$nurlPart}]}]}"""
    }

    private fun assertCode(code: EngageError.Code, block: () -> Unit) {
        val error = assertThrows(EngageError::class.java, block)
        assertEquals(code, error.code)
    }
}
