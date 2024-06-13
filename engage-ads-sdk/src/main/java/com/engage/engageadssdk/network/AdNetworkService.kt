package com.engage.engageadssdk.network

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.engage.com.engage.engageadssdk.R
import com.engage.engageadssdk.parser.VASTParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class AdNetworkService(private val context: Context) {
    private lateinit var adTagUrl: String
    val vastUrl: String
        get() {
            return "$adTagUrl?advid=1111&device_id=$deviceId&ua=$userAgent&app_name=$appName&app_bundle=com.aetn.history.watch&width=$screenWidth&height=${screenWidth * 9 / 16}"
        }

    val defaultVastUrl: String
        get() {
            return "${context.getString(R.string.default_ads_url)}?advid=1111&device_id=$deviceId&ua=$userAgent&app_name=$appName&app_bundle=com.aetn.history.watch&width=$screenWidth&height=${screenWidth * 9 / 16}"
        }

    // keep retry counter
    var retryCounter: Int = 0

    private val client = OkHttpClient()
    private val deviceId: String = getOrCreateDeviceId()
    private val userAgent: String = "${Build.MODEL}.Android: ${Build.VERSION.BASE_OS}"
    private val appName: String =
        context.applicationInfo.loadLabel(context.packageManager).toString()
    private val appBundle: String = context.packageName

    private fun getOrCreateDeviceId(): String {
        val sharedPrefs = context.getSharedPreferences("sdk_prefs", Context.MODE_PRIVATE)
        return sharedPrefs.getString("device_id", null) ?: run {
            val newDeviceId = UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", newDeviceId).apply()
            newDeviceId
        }
    }

    // Get screen width as a val get()
    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    @WorkerThread
    suspend fun fetchVASTResponse(adTagUrl: String): VASTResponse {
        this.adTagUrl = adTagUrl
        val urlWithParams = vastUrl

        val request = Request.Builder()
            .url(urlWithParams)
            .build()

        Log.d("AdNetworkService", "Calling Request: $request")

        val result = CoroutineScope(Dispatchers.IO).async {
            val call = client.newCall(request)
            suspendCoroutine {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        e.printStackTrace()  // Handle failure appropriately.
                        retryCounter = 0
                        it.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body
                        val stringBody = responseBody?.string()
                        if (response.isSuccessful) {
                            Log.d("AdNetworkService", "Response: $stringBody")
                            val vastResponse: VASTResponse =
                                VASTParser().parseVASTResponse(stringBody)
                            Log.d("AdNetworkService", "Parsed response to POJO")
                            retryCounter = 0
                            if (vastResponse.extensions?.serverError != null) {
                                it.resumeWithException(Exception(vastResponse.extensions!!.serverError))
                            } else if (vastResponse.Ad != null) {
                                it.resume(vastResponse)
                            } else {
                                throw EmptyVASTResponseException()
                            }
                        } else {
                            if (retryCounter < 3) {
                                retryCounter++
                                client.newCall(request).enqueue(this)
                            } else if (!response.request.url.toString()
                                    .startsWith(defaultVastUrl)
                            ) {
                                retryCounter = 0
                                throw EmptyVASTResponseException()
                            } else {
                                val error =
                                    VASTParser().parseVASTResponse(stringBody).extensions?.serverError
                                it.resumeWithException(Exception(error))
                            }
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
}

class EmptyVASTResponseException : Exception("Empty VAST Response")