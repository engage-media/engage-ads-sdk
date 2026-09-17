package com.engage.ads.network

import com.engage.ads.DiagnosticEvent
import com.engage.ads.DiagnosticListener
import com.engage.ads.EngageError
import com.engage.ads.ResourceLimits
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    @Test fun oversizedRequestIsRejectedBeforeNetwork() = runBlocking {
        MockWebServer().use { server ->
            val error = assertThrows(EngageError::class.java) {
                runBlocking {
                    HttpTransport(2_000, DiagnosticListener.NONE).postJson(
                        server.url("/bid").toString(),
                        emptyMap(),
                        "x".repeat(ResourceLimits.MAX_OPENRTB_REQUEST_BYTES.toInt() + 1),
                    )
                }
            }
            assertEquals(EngageError.Code.INVALID_REQUEST, error.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeBodyAllocation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", ResourceLimits.MAX_HTTP_RESPONSE_BYTES + 1))
            val error = assertThrows(EngageError::class.java) {
                runBlocking { HttpTransport(2_000, DiagnosticListener.NONE).get(server.url("/large").toString()) }
            }
            assertEquals(EngageError.Code.NETWORK, error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun throwingDiagnosticListenerCannotBreakNotificationFlow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            HttpTransport(2_000, DiagnosticListener { throw IllegalStateException("host failure") })
                .notify(server.url("/notice").toString())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun openRtbPostSetsFrozenProtocolHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val transport = HttpTransport(2_000, DiagnosticListener.NONE)
            transport.postJson(server.url("/bid").toString(), mapOf("x-test" to "yes"), "{}")
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("2.6", request.getHeader("x-openrtb-version")); assertEquals("yes", request.getHeader("x-test"))
        }
    }

    @Test fun notificationFailureIsDiagnosedAndNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val events = mutableListOf<DiagnosticEvent>()
            HttpTransport(2_000, DiagnosticListener(events::add)).notify(server.url("/notice?token=secret").toString())
            val failure = events.single { it.code == "notification_failed" }
            assertEquals(1, server.requestCount)
            assertFalse(failure.metadata.getValue("url").contains("secret"))
        }
    }

    @Test fun notificationsUseFreshConnectionsWithoutRetrying() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val transport = HttpTransport(2_000, DiagnosticListener.NONE)

            transport.notify(server.url("/notice/one").toString())
            transport.notify(server.url("/notice/two").toString())

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(2, server.requestCount)
            assertEquals(0, first.sequenceNumber)
            assertEquals(0, second.sequenceNumber)
        }
    }

    @Test fun completionDiagnosticContainsLatencyWithoutUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val events = mutableListOf<DiagnosticEvent>()
            HttpTransport(2_000, DiagnosticListener(events::add)).get(server.url("/private/id?token=secret").toString())
            val completed = events.single { it.code == "http_request_completed" }
            assertEquals("GET", completed.metadata["method"])
            assertEquals("204", completed.metadata["status"])
            assertNotNull(completed.metadata["duration_ms"]?.toLongOrNull())
            assertTrue(completed.metadata.values.none { it.contains("private") || it.contains("secret") })
        }
    }

    @Test fun connectionFailureIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            runCatching { HttpTransport(500, DiagnosticListener.NONE).get(server.url("/once").toString()) }
            assertEquals(1, server.requestCount)
        }
    }
}
