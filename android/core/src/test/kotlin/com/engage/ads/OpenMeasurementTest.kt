package com.engage.ads

import android.app.Activity
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenMeasurementTest {
    @Test fun capabilityIsSnapshottedOnceAndUnavailableBackendIsNotAdvertised() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        var calls = 0
        val backend = object : OpenMeasurementBackend {
            override fun capability(): OpenMeasurementCapability? {
                calls += 1
                if (calls > 1) throw IllegalStateException("must not be queried again")
                return OpenMeasurementCapability(OpenMeasurementPartner("partner", "1.0"), setOf(OpenMeasurementCreativeType.HTML))
            }
            override fun prepareHtml(document: String) = document
            override fun createSession(context: OpenMeasurementSessionContext) = RecordingSession()
        }
        val coordinator = OpenMeasurementCoordinator.snapshot(backend, DiagnosticListener(diagnostics::add))

        assertTrue(coordinator.supports(OpenMeasurementCreativeType.HTML))
        assertFalse(coordinator.supports(OpenMeasurementCreativeType.NATIVE))
        assertEquals("partner", coordinator.partnerFor(OpenMeasurementCreativeType.HTML)?.name)
        assertEquals(1, calls)

        val unavailable = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
            override fun capability(): OpenMeasurementCapability? = throw IllegalStateException("not initialized")
            override fun prepareHtml(document: String) = document
            override fun createSession(context: OpenMeasurementSessionContext) = RecordingSession()
        }, DiagnosticListener(diagnostics::add))
        assertFalse(unavailable.supports(OpenMeasurementCreativeType.HTML))
        assertNull(unavailable.partnerFor(OpenMeasurementCreativeType.HTML))
        assertTrue(diagnostics.any { it.code == "measurement_backend_unavailable" && it.metadata.isEmpty() })
    }

    @Test fun sessionEventsAreOrderedDeduplicatedAndCleanedUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val provider = RecordingSession()
        val coordinator = coordinator(provider)
        val session = coordinator.createSession(
            OpenMeasurementSessionContext(OpenMeasurementCreativeType.NATIVE, AdFormat.NATIVE, FrameLayout(activity)),
            listOf(FriendlyObstruction(FrameLayout(activity), FriendlyObstructionPurpose.OTHER, "Ad disclosure")),
        )!!

        session.impression() // preload must not create an impression
        session.loaded(); session.loaded()
        session.impression(); session.impression()
        session.finish(); session.finish()

        assertEquals(listOf("register", "loaded", "impression", "unregister", "finish"), provider.calls)
    }

    @Test fun providerLoadedFailureTerminatesSessionAndDoesNotReceiveImpression() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val provider = RecordingSession(throwLoaded = true, throwUnregister = true)
        val session = coordinator(provider, diagnostics).createSession(
            OpenMeasurementSessionContext(OpenMeasurementCreativeType.HTML, AdFormat.BANNER, FrameLayout(activity)),
            emptyList(),
        )!!

        session.loaded()
        session.impression()
        session.finish()

        assertEquals(listOf("loaded", "unregister", "finish"), provider.calls)
        assertTrue(diagnostics.any { it.code == "measurement_session_failed" && it.metadata["operation"] == "loaded" })
        assertTrue(diagnostics.any { it.code == "measurement_session_failed" && it.metadata["operation"] == "unregister_obstructions" })
    }

    @Test fun invalidFriendlyObstructionReasonAndExcessCountAreRejected() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertThrows(IllegalArgumentException::class.java) {
            FriendlyObstruction(FrameLayout(activity), FriendlyObstructionPurpose.OTHER, "bad-hyphen")
        }
        val tooMany = (0..ResourceLimits.MAX_FRIENDLY_OBSTRUCTIONS).map {
            FriendlyObstruction(FrameLayout(activity), FriendlyObstructionPurpose.OTHER, "Disclosure $it")
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator(RecordingSession()).createSession(
                OpenMeasurementSessionContext(OpenMeasurementCreativeType.HTML, AdFormat.BANNER, FrameLayout(activity)),
                tooMany,
            )
        }
    }

    @Test fun failedOrOversizedHtmlPreparationDisablesOnlyThatMeasurementSession() {
        val diagnostics = mutableListOf<DiagnosticEvent>()
        val throwing = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
            override fun capability() = OpenMeasurementCapability(OpenMeasurementPartner("partner", "1"), setOf(OpenMeasurementCreativeType.HTML))
            override fun prepareHtml(document: String): String = error("injector unavailable")
            override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession = error("must not create")
        }, DiagnosticListener(diagnostics::add))
        assertNull(throwing.prepareHtml("<html></html>"))

        val oversized = OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
            override fun capability() = OpenMeasurementCapability(OpenMeasurementPartner("partner", "1"), setOf(OpenMeasurementCreativeType.HTML))
            override fun prepareHtml(document: String) = "x".repeat(ResourceLimits.MAX_MEASUREMENT_HTML_BYTES.toInt() + 1)
            override fun createSession(context: OpenMeasurementSessionContext): OpenMeasurementSession = error("must not create")
        }, DiagnosticListener(diagnostics::add))
        assertNull(oversized.prepareHtml("<html></html>"))
        assertEquals(2, diagnostics.count { it.code == "measurement_backend_failed" })
    }

    private fun coordinator(session: RecordingSession, diagnostics: MutableList<DiagnosticEvent> = mutableListOf()): OpenMeasurementCoordinator =
        OpenMeasurementCoordinator.snapshot(object : OpenMeasurementBackend {
            override fun capability() = OpenMeasurementCapability(
                OpenMeasurementPartner("partner", "1.0"),
                setOf(OpenMeasurementCreativeType.HTML, OpenMeasurementCreativeType.NATIVE),
            )
            override fun prepareHtml(document: String) = document
            override fun createSession(context: OpenMeasurementSessionContext) = session
        }, DiagnosticListener(diagnostics::add))

    private class RecordingSession(
        private val throwLoaded: Boolean = false,
        private val throwUnregister: Boolean = false,
    ) : OpenMeasurementSession {
        val calls = mutableListOf<String>()
        override fun registerFriendlyObstruction(obstruction: FriendlyObstruction) { calls += "register" }
        override fun unregisterFriendlyObstruction(view: android.view.View) { calls += "unregister_one" }
        override fun unregisterAllFriendlyObstructions() { calls += "unregister"; if (throwUnregister) error("cleanup") }
        override fun loaded() { calls += "loaded"; if (throwLoaded) error("loaded") }
        override fun impression() { calls += "impression" }
        override fun finish() { calls += "finish" }
    }
}
