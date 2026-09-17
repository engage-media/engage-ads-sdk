package com.engage.ads

import android.net.Uri

fun interface DiagnosticListener {
    fun onDiagnostic(event: DiagnosticEvent)

    companion object { val NONE = DiagnosticListener { } }
}

/** A host diagnostics callback must never be able to break SDK control flow. */
internal fun DiagnosticListener.emitSafely(event: DiagnosticEvent) {
    try {
        onDiagnostic(event)
    } catch (_: Exception) {
        // Host callbacks are observational. Errors and VM failures intentionally propagate.
    }
}

data class DiagnosticEvent(
    val level: Level,
    val code: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    enum class Level { DEBUG, INFO, WARNING, ERROR }
}

internal object Redactor {
    private val sensitiveKeys = setOf(
        "ifa", "idfa", "adid", "advertising_id", "consent", "consent_string",
        "gdpr_consent", "us_privacy", "gpp", "lat", "lon", "ip", "user_id",
    )

    fun url(raw: String): String = try {
        val uri = Uri.parse(raw)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) "[INVALID_URL]"
        else "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}/[REDACTED]"
    } catch (_: Exception) {
        "[INVALID_URL]"
    }

    fun text(raw: String): String {
        var result = raw
        sensitiveKeys.forEach { key ->
            result = result.replace(Regex("(?i)([\\\"']?$key[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"'&,}\\s]+)"), "$1[REDACTED]")
        }
        return result.take(1_024)
    }
}
