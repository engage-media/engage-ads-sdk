package com.engage.ads

import android.view.View
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/** Custom-renderer types that a licensed, partner-namespaced OM SDK backend can measure. */
enum class OpenMeasurementCreativeType { HTML, NATIVE }

data class OpenMeasurementPartner(
    val name: String,
    val version: String,
) {
    init {
        require(name.isNotBlank() && !name.exceedsUtf8Bytes(MAX_IDENTITY_BYTES.toLong())) { "measurement partner name must be 1..$MAX_IDENTITY_BYTES UTF-8 bytes" }
        require(version.isNotBlank() && !version.exceedsUtf8Bytes(MAX_IDENTITY_BYTES.toLong())) { "measurement partner version must be 1..$MAX_IDENTITY_BYTES UTF-8 bytes" }
    }

    private companion object { const val MAX_IDENTITY_BYTES = 256 }
}

data class OpenMeasurementCapability(
    val partner: OpenMeasurementPartner,
    val creativeTypes: Set<OpenMeasurementCreativeType>,
) {
    init { require(creativeTypes.isNotEmpty()) { "measurement capability must support at least one creative type" } }
}

data class OpenMeasurementVerificationResource(
    val url: String,
    val vendorKey: String? = null,
    val parameters: String? = null,
)

enum class FriendlyObstructionPurpose { VIDEO_CONTROLS, CLOSE_AD, NOT_VISIBLE, OTHER }

data class FriendlyObstruction(
    val view: View,
    val purpose: FriendlyObstructionPurpose,
    val detailedReason: String,
) {
    init {
        require(detailedReason.matches(Regex("[A-Za-z0-9 ]{1,50}"))) {
            "friendly obstruction reason must contain only ASCII letters, digits, and spaces and be at most 50 characters"
        }
    }
}

data class OpenMeasurementSessionContext(
    val creativeType: OpenMeasurementCreativeType,
    val format: AdFormat,
    val adView: View,
    val webView: WebView? = null,
    val verificationResources: List<OpenMeasurementVerificationResource> = emptyList(),
)

/**
 * Adapter implemented against Engage's licensed, partner-namespaced OM SDK binary.
 * Returning null from [capability] means the backend is not initialized and no OM
 * capability is advertised for custom HTML/native rendering. [prepareHtml] must use
 * the licensed SDK's script injector to add the OM service before creative JavaScript
 * loads. After that document finishes loading, [createSession] must create/start the
 * OM session and bind [OpenMeasurementSessionContext.adView].
 */
interface OpenMeasurementBackend {
    fun capability(): OpenMeasurementCapability?
    fun prepareHtml(document: String): String
    fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession
}

interface OpenMeasurementSession {
    fun registerFriendlyObstruction(obstruction: FriendlyObstruction)
    fun unregisterFriendlyObstruction(view: View)
    fun unregisterAllFriendlyObstructions()
    fun loaded()
    fun impression()
    fun finish()
}

internal class OpenMeasurementCoordinator private constructor(
    private val backend: OpenMeasurementBackend?,
    private val capabilitySnapshot: OpenMeasurementCapability?,
    private val diagnostics: DiagnosticListener,
) {
    fun supports(type: OpenMeasurementCreativeType): Boolean = type in capabilitySnapshot?.creativeTypes.orEmpty()

    fun partnerFor(type: OpenMeasurementCreativeType): OpenMeasurementPartner? =
        capabilitySnapshot?.partner?.takeIf { supports(type) }

    fun prepareHtml(document: String): String? {
        val provider = backend?.takeIf { supports(OpenMeasurementCreativeType.HTML) } ?: return null
        return try {
            provider.prepareHtml(document).takeIf { prepared ->
                prepared.isNotBlank() && !prepared.exceedsUtf8Bytes(ResourceLimits.MAX_MEASUREMENT_HTML_BYTES)
            } ?: run {
                diagnose("prepare_html_invalid", OpenMeasurementCreativeType.HTML)
                null
            }
        } catch (_: Exception) {
            diagnose("prepare_html", OpenMeasurementCreativeType.HTML)
            null
        }
    }

    fun createSession(
        context: OpenMeasurementSessionContext,
        obstructions: List<FriendlyObstruction>,
    ): SafeOpenMeasurementSession? {
        require(obstructions.size <= ResourceLimits.MAX_FRIENDLY_OBSTRUCTIONS) { "at most ${ResourceLimits.MAX_FRIENDLY_OBSTRUCTIONS} friendly obstructions are supported" }
        val provider = backend?.takeIf { supports(context.creativeType) } ?: return null
        val session = try {
            provider.createSession(context)
        } catch (_: Exception) {
            diagnose("create", context.creativeType)
            return null
        }
        return SafeOpenMeasurementSession(session, context.creativeType, diagnostics).also { safe ->
            obstructions.forEach(safe::registerFriendlyObstruction)
        }
    }

    private fun diagnose(operation: String, type: OpenMeasurementCreativeType) {
        diagnostics.emitSafely(DiagnosticEvent(
            DiagnosticEvent.Level.WARNING,
            "measurement_backend_failed",
            "Open Measurement backend operation failed",
            mapOf("operation" to operation, "creative_type" to type.name.lowercase()),
        ))
    }

    companion object {
        fun snapshot(backend: OpenMeasurementBackend?, diagnostics: DiagnosticListener): OpenMeasurementCoordinator {
            if (backend == null) return OpenMeasurementCoordinator(null, null, diagnostics)
            val capability = try {
                backend.capability()?.let { value ->
                    OpenMeasurementCapability(value.partner.copy(), value.creativeTypes.toSet())
                }
            } catch (_: Exception) {
                diagnostics.emitSafely(DiagnosticEvent(
                    DiagnosticEvent.Level.WARNING,
                    "measurement_backend_unavailable",
                    "Open Measurement backend capability check failed",
                ))
                null
            }
            return OpenMeasurementCoordinator(backend.takeIf { capability != null }, capability, diagnostics)
        }

        fun disabled(diagnostics: DiagnosticListener = DiagnosticListener.NONE) =
            OpenMeasurementCoordinator(null, null, diagnostics)
    }
}

internal class SafeOpenMeasurementSession(
    private var delegate: OpenMeasurementSession?,
    private val creativeType: OpenMeasurementCreativeType,
    private val diagnostics: DiagnosticListener,
) {
    private val loadedSent = AtomicBoolean(false)
    private val impressionSent = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    fun registerFriendlyObstruction(obstruction: FriendlyObstruction) {
        if (finished.get()) return
        if (!call("register_obstruction") { registerFriendlyObstruction(obstruction) }) finish()
    }

    fun loaded() {
        if (finished.get() || !loadedSent.compareAndSet(false, true)) return
        if (!call("loaded") { loaded() }) finish()
    }

    fun unregisterFriendlyObstruction(view: View) {
        if (finished.get()) return
        if (!call("unregister_obstruction") { unregisterFriendlyObstruction(view) }) finish()
    }

    fun impression() {
        if (finished.get() || !loadedSent.get() || !impressionSent.compareAndSet(false, true)) return
        if (!call("impression") { impression() }) finish()
    }

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        val value = delegate
        delegate = null
        if (value != null) {
            call(value, "unregister_obstructions") { unregisterAllFriendlyObstructions() }
            call(value, "finish") { finish() }
        }
    }

    private inline fun call(operation: String, action: OpenMeasurementSession.() -> Unit): Boolean {
        val value = delegate ?: return false
        return call(value, operation, action)
    }

    private inline fun call(value: OpenMeasurementSession, operation: String, action: OpenMeasurementSession.() -> Unit): Boolean {
        return try {
            value.action()
            true
        } catch (_: Exception) {
            diagnostics.emitSafely(DiagnosticEvent(
                DiagnosticEvent.Level.WARNING,
                "measurement_session_failed",
                "Open Measurement session operation failed",
                mapOf("operation" to operation, "creative_type" to creativeType.name.lowercase()),
            ))
            false
        }
    }
}
