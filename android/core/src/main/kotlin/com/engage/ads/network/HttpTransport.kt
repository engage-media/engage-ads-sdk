package com.engage.ads.network

import com.engage.ads.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.Buffer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpResult(val code: Int, val body: String)

internal class HttpTransport(
    timeoutMillis: Long,
    private val diagnostics: DiagnosticListener,
    client: OkHttpClient? = null,
) {
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    // Notices are intentionally one-attempt deliveries. Give them a connection pool with no
    // idle sockets so a delayed event cannot select a server-closed keep-alive connection and
    // fail before the request reaches the server. This preserves the no-retry contract while
    // keeping ordinary request connections pooled.
    private val notificationClient = this.client.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(false)
        .build()

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        if (body.exceedsUtf8Bytes(ResourceLimits.MAX_OPENRTB_REQUEST_BYTES)) {
            throw EngageError(EngageError.Code.INVALID_REQUEST, "OpenRTB request exceeds the size limit")
        }
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON))
            .header("content-type", "application/json")
            .header("x-openrtb-version", "2.6")
        headers.forEach { (name, value) ->
            if (name.equals("content-type", true) || name.equals("x-openrtb-version", true)) {
                throw EngageError(EngageError.Code.INVALID_REQUEST, "endpoint headers cannot replace protocol header $name")
            }
            builder.header(name, value)
        }
        return execute(builder.build())
    }

    suspend fun get(url: String): HttpResult = execute(Request.Builder().url(url).get().build())

    suspend fun notify(url: String) {
        try {
            val result = execute(Request.Builder().url(url).get().build(), readBody = false, callClient = notificationClient)
            if (result.code !in 200..299) throw IOException("notification returned HTTP ${result.code}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostics.emitSafely(DiagnosticEvent(DiagnosticEvent.Level.WARNING, "notification_failed", "Notification failed", mapOf("url" to Redactor.url(url))))
        }
    }

    private suspend fun execute(
        request: Request,
        readBody: Boolean = true,
        callClient: OkHttpClient = client,
    ): HttpResult = withContext(Dispatchers.IO) {
        val call = callClient.newCall(request)
        val startedAt = System.nanoTime()
        fun record(level: DiagnosticEvent.Level, code: String, status: Int? = null) {
            val metadata = mutableMapOf(
                "method" to request.method,
                "duration_ms" to ((System.nanoTime() - startedAt) / 1_000_000L).toString(),
            )
            status?.let { metadata["status"] = it.toString() }
            diagnostics.emitSafely(DiagnosticEvent(level, code, code.replace('_', ' '), metadata))
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel(); record(DiagnosticEvent.Level.INFO, "http_request_cancelled") }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isCancelled) return
                    val mapped = if (error.message?.contains("timeout", ignoreCase = true) == true) EngageError.Code.TIMEOUT else EngageError.Code.NETWORK
                    record(DiagnosticEvent.Level.ERROR, "http_request_failed")
                    continuation.resumeWithException(EngageError(mapped, "HTTP request failed", error))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val text = if (readBody) it.body?.readBoundedString(ResourceLimits.MAX_HTTP_RESPONSE_BYTES).orEmpty() else ""
                            if (!continuation.isCancelled) {
                                record(DiagnosticEvent.Level.DEBUG, "http_request_completed", it.code)
                                continuation.resume(HttpResult(it.code, text))
                            }
                        }
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) {
                            record(DiagnosticEvent.Level.ERROR, "http_response_read_failed", response.code)
                            continuation.resumeWithException(EngageError(EngageError.Code.NETWORK, "HTTP response could not be read", error))
                        }
                    }
                }
            })
        }
    }

    private fun ResponseBody.readBoundedString(maxBytes: Long): String {
        val declared = contentLength()
        if (declared > maxBytes) throw EngageError(EngageError.Code.NETWORK, "HTTP response exceeds the size limit")
        val input = source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = input.read(buffer, minOf(8_192L, maxBytes + 1L - total))
            if (read == -1L) break
            total += read
            if (total > maxBytes) throw EngageError(EngageError.Code.NETWORK, "HTTP response exceeds the size limit")
        }
        return buffer.readString(contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8)
    }

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
