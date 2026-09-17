package com.engage.ads.network

import android.net.Uri
import com.engage.ads.Endpoint
import com.engage.ads.PrivacySettings
import com.engage.ads.EngageConfiguration
import com.engage.ads.DeviceCategory
import android.os.Build
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.engage.ads.EngageError

internal class VastUrlBuilder(private val clock: Clock = Clock.systemUTC()) {
    fun build(endpoint: Endpoint.VastTag, privacy: PrivacySettings, configuration: EngageConfiguration? = null, userAgent: String? = null): String {
        val cacheBuster = UUID.randomUUID().toString().filter(Char::isDigit).take(8).padEnd(8, '0')
        val replacements = mapOf(
            "[CACHEBUSTING]" to cacheBuster,
            "[CACHEBUSTER]" to cacheBuster,
            "[TIMESTAMP]" to DateTimeFormatter.ISO_INSTANT.format(clock.instant()),
            "[GDPR]" to privacy.gdprApplies?.let { if (it) "1" else "0" },
            "[GDPR_CONSENT]" to privacy.consentString,
            "[US_PRIVACY]" to privacy.usPrivacy,
            "[GPP_STRING]" to privacy.gpp,
            "[GPP_SID]" to privacy.gppSid.takeIf { it.isNotEmpty() }?.joinToString(","),
            "[IFA]" to privacy.advertisingId?.takeUnless { privacy.limitAdTracking == true },
            "[LIMITADTRACKING]" to privacy.limitAdTracking?.let { if (it) "1" else "0" },
            "[APPBUNDLE]" to configuration?.app?.bundle,
            "[APPNAME]" to configuration?.app?.name,
            "[STOREURL]" to configuration?.app?.storeUrl,
            "[DEVICEUA]" to userAgent?.takeIf { it.isNotBlank() },
            "[DEVICEOS]" to configuration?.let { "Android ${Build.VERSION.RELEASE}" },
            "[DEVICEMAKE]" to configuration?.let { Build.MANUFACTURER },
            "[DEVICEMODEL]" to configuration?.let { Build.MODEL },
            "[DEVICETYPE]" to configuration?.let { if (it.deviceCategory == DeviceCategory.TV) "3" else "4" },
        )
        fun expand(value: String): String? {
            val macros = MACRO.findAll(value).map { it.value }.toList()
            val unknown = macros.firstOrNull { it !in replacements }
            if (unknown != null) throw EngageError(EngageError.Code.INVALID_REQUEST, "unsupported VAST URL macro $unknown")
            if (macros.any { replacements[it] == null }) return null
            var expanded = value
            macros.forEach { expanded = expanded.replace(it, replacements.getValue(it)!!) }
            return expanded
        }
        val source = Uri.parse(endpoint.url)
        if (MACRO.containsMatchIn(source.buildUpon().clearQuery().fragment(null).build().toString())) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "VAST macros are supported in query values only")
        }
        val builder = source.buildUpon().clearQuery()
        source.queryParameterNames.forEach { key ->
            source.getQueryParameters(key).forEach { value -> expand(value)?.let { builder.appendQueryParameter(key, it) } }
        }
        endpoint.parameters.forEach { (key, value) ->
            if (privacy.limitAdTracking == true && key.lowercase() in IDENTITY_KEYS) return@forEach
            expand(value)?.let { builder.appendQueryParameter(key, it) }
        }
        return builder.build().toString()
    }

    private companion object {
        val MACRO = Regex("\\[[A-Z][A-Z0-9_]*\\]")
        val IDENTITY_KEYS = setOf("ifa", "idfa", "adid", "advertising_id")
    }
}
