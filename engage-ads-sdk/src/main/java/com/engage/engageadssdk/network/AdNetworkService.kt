package com.engage.engageadssdk.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.engage.engageadssdk.network.request.AdRequestBuilder
import com.engage.engageadssdk.network.request.VastAdRequestDto
import com.engage.engageadssdk.parser.parseVASTResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal class AdNetworkService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "sdk_prefs",
        Context.MODE_PRIVATE
    ),
    private val adRequestBuilder: AdRequestBuilder = AdRequestBuilder(sharedPreferences)
) {
    // keep retry counter
    var retryCounter: Int = 0

    private val deviceId: String = getOrCreateDeviceId()
    private val userAgent: String = "${Build.MODEL}.Android:${Build.VERSION.SDK_INT}"

    val countryCode: String? by lazy {
        return@lazy getCountryCodeFromApi()

    }

    private fun getCountryCodeFromApi(): String? {
        val url = URL("https://api.country.is/")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                jsonResponse.getString("country")
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getOrCreateDeviceId(): String {
        val sharedPrefs = sharedPreferences
        return sharedPrefs?.getString("device_id", null) ?: run {
            val newDeviceId = UUID.randomUUID().toString()
            sharedPrefs?.edit()?.putString("device_id", newDeviceId)?.apply()
            newDeviceId
        }
    }

    @WorkerThread
    suspend fun fetchVASTResponse(
        adTagUrl: String,
        requestBody: VastAdRequestDto = adRequestBuilder.createVastAdRequestDto()
    ): VASTResponse {
        val uri = buildUrl(adTagUrl, requestBody)
        Log.d("AdNetworkService", "Calling URL: $uri")

        val result = CoroutineScope(Dispatchers.IO).async {
            val url = URL(uri.build().toString())
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            try {
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response =
                        connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    parseNetworkResponse(response, requestBody)
                } else {
                    if (retryCounter < 3) {
                        retryCounter++
                        fetchVASTResponse(adTagUrl, requestBody)
                    } else {
                        retryCounter = 0
                        throw EmptyVASTResponseException()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                retryCounter = 0
                throw e
            } finally {
                connection.disconnect()
            }
        }

        return try {
            result.await()
        } catch (e: Exception) {
            throw e
        }
    }

    private fun parseNetworkResponse(
        response: String?,
        requestBody: VastAdRequestDto
    ): VASTResponse {
        Log.d("AdNetworkService", "Response: $response")
        val vastResponse: VASTResponse = parseVASTResponse(response)
        Log.d("AdNetworkService", "Parsed response to POJO")
        retryCounter = 0
        if (vastResponse.isEmpty) {
            if (vastResponse.ad?.wrapper?.vastAdTagURI?.text?.isNotEmpty() == true) {
                val newAdTagUrl = vastResponse.ad?.wrapper?.vastAdTagURI?.text
                return runBlocking {
                    fetchVASTResponse(newAdTagUrl!!, requestBody)
                }
            } else {
                throw EmptyVASTResponseException()
            }
        } else {
            return vastResponse
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
            appendQueryParameter("country", countryCode)
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
            appendQueryParameter("channelId", requestBody.app.channelId)
            appendQueryParameter("publisherId", requestBody.app.publisherId)
        }
        return uri
    }
}

class EmptyVASTResponseException : Exception("Empty VAST Response")
