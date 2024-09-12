package com.engage.engageadssdk.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.engage.engageadssdk.network.request.AdRequestBuilder
import com.engage.engageadssdk.network.request.VastAdRequestDto
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.UUID


internal class AdNetworkService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "sdk_prefs",
        Context.MODE_PRIVATE
    ),
    private val adRequestBuilder: AdRequestBuilder = AdRequestBuilder(context.packageManager, sharedPreferences)
) {
    // keep retry counter
    var retryCounter: Int = 0

    private val deviceId: String = getOrCreateDeviceId()
    private val userAgent: String = "${Build.MODEL}.Android:${Build.VERSION.SDK_INT}"

    val countryCode: String? by lazy {
        return@lazy getCountryCodeFromApi()?.getString("country") ?: run {
            Log.e("AdNetworkService", "Failed to get country code from API")
            // get country code from locale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales.get(0).country
            } else {
                context.resources.configuration.locale.country
            }
        }
    }

    val ip: String? by lazy {
        return@lazy  getIPAddress(true)
    }

    fun getIPAddress(useIPv4: Boolean): String {
        try {
            val interfaces: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        //boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                        val isIPv4 = sAddr.indexOf(':') < 0

                        if (useIPv4) {
                            if (isIPv4) return sAddr
                        } else {
                            if (!isIPv4) {
                                val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                return if (delim < 0) sAddr.uppercase(Locale.getDefault()) else sAddr.substring(
                                    0,
                                    delim
                                ).uppercase(
                                    Locale.getDefault()
                                )
                            }
                        }
                    }
                }
            }
        } catch (ignored: java.lang.Exception) {
        } // for now eat exceptions

        return ""
    }


    private fun getCountryCodeFromApi(): JSONObject? {
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
                jsonResponse
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
    fun aggregateVastDeviceData(
        adTagUrl: String,
        requestBody: VastAdRequestDto = adRequestBuilder.createVastAdRequestDto()
    ): String {
        return buildUrl(adTagUrl, requestBody).build().toString()
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
            appendQueryParameter("idfa", deviceId)
            appendQueryParameter("adid", deviceId)
            if (countryCode?.isNotEmpty() == true) {
                appendQueryParameter("country", countryCode)
            }
            appendQueryParameter("dnt", "0")
            appendQueryParameter("lmt", "0")
            appendQueryParameter("os", requestBody.device.os)
            appendQueryParameter("ifa", requestBody.device.ifa)
            appendQueryParameter("ifa_type", requestBody.device.ext.ifaType)
            appendQueryParameter("model", requestBody.device.model)
            appendQueryParameter("js", requestBody.device.js.toString())
            appendQueryParameter("devicetype", requestBody.device.devicetype.toString())
            if (requestBody.device.ip?.isNotEmpty() == true) {
                appendQueryParameter("ip", requestBody.device.ip)
            }
            appendQueryParameter("secure", requestBody.imp[0].secure.toString())
            appendQueryParameter("vast_version", "3.0")
            appendQueryParameter("channel", requestBody.app.channelId)
            appendQueryParameter("publisher", requestBody.app.publisherId)
        }
        return uri
    }
}

class EmptyVASTResponseException : Exception("Empty VAST Response")
