package com.engage.ads.network

import com.engage.ads.Endpoint
import com.engage.ads.PrivacySettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.engage.ads.EngageError

@RunWith(RobolectricTestRunner::class)
class VastUrlBuilderTest {
    private val builder = VastUrlBuilder(Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC))

    @Test fun preservesExistingQueryAndEncodesExplicitParameters() {
        val result = builder.build(Endpoint.VastTag("https://ads.example/vast?existing=one", mapOf("channel" to "a b&c")), PrivacySettings())
        assertTrue(result.contains("existing=one")); assertTrue(result.contains("channel=a%20b%26c"))
    }

    @Test fun expandsOnlyAvailablePrivacyMacros() {
        val result = builder.build(Endpoint.VastTag("https://ads.example/vast?gdpr=[GDPR]&consent=[GDPR_CONSENT]&ifa=[IFA]"), PrivacySettings(gdprApplies = true, consentString = "a+b"))
        assertTrue(result.contains("gdpr=1")); assertTrue(result.contains("consent=a%2Bb")); assertFalse(result.contains("ifa="))
    }

    @Test fun omitsIdentityMacroAndExplicitIdentityWhenLimited() {
        val result = builder.build(Endpoint.VastTag("https://ads.example/vast?ifa=[IFA]&keep=yes", mapOf("adid" to "must-not-leak")), PrivacySettings(limitAdTracking = true, advertisingId = "must-not-leak"))
        assertFalse(result.contains("ifa=")); assertFalse(result.contains("adid=")); assertFalse(result.contains("must-not-leak")); assertTrue(result.contains("keep=yes"))
    }

    @Test fun expandsMacrosInExplicitParameterValuesOnce() {
        val result = builder.build(Endpoint.VastTag("https://ads.example/vast", mapOf("consent" to "[GDPR_CONSENT]")), PrivacySettings(consentString = "a+b"))
        assertTrue(result.contains("consent=a%2Bb")); assertFalse(result.contains("%252B"))
    }

    @Test fun rejectsUnknownMacros() {
        val error = assertThrows(EngageError::class.java) { builder.build(Endpoint.VastTag("https://ads.example/vast?x=[UNKNOWN]"), PrivacySettings()) }
        assertEquals(EngageError.Code.INVALID_REQUEST, error.code)
    }
}
