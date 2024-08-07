package com.engage.engageadssdk.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.engage.engageadssdk.R
import com.engage.engageadssdk.network.request.AdRequestBuilder
import com.engage.engageadssdk.network.request.VastAdRequestDto
import com.engage.engageadssdk.parser.VASTParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.IOException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


internal class AdNetworkService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "sdk_prefs",
        Context.MODE_PRIVATE
    ),
    private val adRequestBuilder: AdRequestBuilder = AdRequestBuilder(sharedPreferences)
) {

    init {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }
        )


// Install the all-trusting trust manager
        try {
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            // Apply the socket factory to OkHttpClient or your specific network client
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        } catch (e: KeyManagementException) {
            e.printStackTrace()
        }

    }

    // keep retry counter
    var retryCounter: Int = 0

    private val client = OkHttpClient.Builder().apply {
        connectTimeout(150, TimeUnit.MILLISECONDS)
        writeTimeout(10, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
    }.build()
    private val deviceId: String = getOrCreateDeviceId()
    private val userAgent: String = "${Build.MODEL}.Android:${Build.VERSION.SDK_INT}"

    private fun getOrCreateDeviceId(): String {
        val sharedPrefs = sharedPreferences
        return sharedPrefs?.getString("device_id", null) ?: run {
            val newDeviceId = UUID.randomUUID().toString()
            sharedPrefs?.edit()?.putString("device_id", newDeviceId)?.apply()
            newDeviceId
        }
    }

    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    @WorkerThread
    suspend fun fetchVASTResponse(adTagUrl: String): VASTResponse {
        val requestBody = adRequestBuilder.createVastAdRequestDto()
        val uri = buildUrl(adTagUrl, requestBody)
        Log.d("AdNetworkService", "Calling URL: $uri")
        val request = Request.Builder().run {
            url(uri.build().toString())
            return@run build()
        }

        // Create a trust manager that does not validate certificate chains
        Log.d("AdNetworkService", "Calling Request: $request")

        val result = CoroutineScope(Dispatchers.IO).async {
            val call = client.newCall(request)
            suspendCoroutine { cont ->
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        e.printStackTrace()  // Handle failure appropriately.
                        retryCounter = 0
                        val copy = call.request().newBuilder().build()
                        val buffer: Buffer = Buffer()
                        copy.body?.writeTo(buffer)
                        val bodyString = buffer.readUtf8()
                        Log.d("AdNetworkService", "Request: $bodyString")
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body
                        val stringBody = responseBody?.string()
                        if (response.isSuccessful) {
                            parseNetworkResponse(stringBody)
                        } else {
                            if (retryCounter < 3) {
                                retryCounter++
                                client.newCall(request).enqueue(this)
                            } else {
                                retryCounter = 0
                                throw EmptyVASTResponseException()
                            }
                        }
                    }

                    private fun parseNetworkResponse(stringBody: String?) {
                        Log.d("AdNetworkService", "Response: $stringBody")
                        val vastResponse: VASTResponse =
                            VASTParser().parseVASTResponse(stringBody)
                        Log.d("AdNetworkService", "Parsed response to POJO")
                        retryCounter = 0
                        if (vastResponse.isEmpty) {
                            if (vastResponse.Ad?.wrapper?.vastAdTagURI?.text?.isEmpty() == false) {
                                val newAdTagUrl = vastResponse.Ad?.wrapper?.vastAdTagURI?.text
                                val result = async {
                                    try {
                                        val result = fetchVASTResponse(newAdTagUrl!!)
                                        return@async result
                                    } catch (e: Exception) {
                                        throw e
                                    }
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        cont.resume(result.await())
                                    } catch (e: Exception) {
                                        cont.resumeWithException(e)
                                    }
                                }
                                return
                            } else {
                            cont.resumeWithException(EmptyVASTResponseException())
                                }
                        } else {
                            cont.resume(vastResponse)
                        }
                    }

                })
            }
        }
        return try {
            result.await()
        } catch (e: Exception) {
            throw e
        }
    }

    private fun buildUrl(
        adTagUrl: String,
        requestBody: VastAdRequestDto
    ): Uri.Builder {
        val uri = Uri.parse(adTagUrl).buildUpon().apply {
            appendQueryParameter("device_id", deviceId)
            appendQueryParameter("ua", userAgent)
            appendQueryParameter("appName", requestBody.app.name)//requestBody.app.name)
            appendQueryParameter("appBundle", requestBody.app.bundle) // requestBody.app.bundle)
            appendQueryParameter("appURL", requestBody.app.storeurl) // requestBody.app.bundle)
            appendQueryParameter("width", requestBody.imp[0].video.w.toString())
            appendQueryParameter("height", (requestBody.imp[0].video.h * 9 / 16).toString())
            appendQueryParameter("us_privacy", if (requestBody.regs.gdpr == 1) "1" else "0")
            appendQueryParameter("userId", requestBody.user.id)
            appendQueryParameter("cb", requestBody.user.id)
            appendQueryParameter("idfa", deviceId)
            appendQueryParameter("adid", deviceId)
            appendQueryParameter("country", "US")
            appendQueryParameter("dnt", "0")
            appendQueryParameter("lmt", "0")
            appendQueryParameter("os", requestBody.device.os)
            appendQueryParameter("ifa", requestBody.device.ifa)
            appendQueryParameter("ifa_type", requestBody.device.ext.ifaType)
            appendQueryParameter("model", requestBody.device.model)
            appendQueryParameter("js", requestBody.device.js.toString())
            appendQueryParameter("devicetype", requestBody.device.devicetype.toString())
            appendQueryParameter("ip", requestBody.device.ip)
            appendQueryParameter("secure", requestBody.imp[0].secure.toString())
            appendQueryParameter("vast_version", "3.0")
            appendQueryParameter("channel", requestBody.app.channelId)
            appendQueryParameter("publisher", requestBody.app.publisherId)
        }
        return uri
    }
}

class EmptyVASTResponseException : Exception("Empty VAST Response")
