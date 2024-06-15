package com.engage.engageadssdk.network.request

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.NetworkInterface
import java.util.UUID


data class Video(
    val mimes: List<String>,
    val protocols: List<Int>,
    val w: Int,
    val h: Int,
    val linearity: Int,
    val boxingallowed: Int
)

data class Imp(
    val id: String,
    val video: Video,
    val bidfloor: Double,
    val bidfloorcur: String,
    val secure: Int
)

data class Geo(
    val lat: Double,
    val lon: Double,
    val type: Int,
    val accuracy: Int,
    val ipservice: Int,
    val country: String? = null,
    val region: String? = null,
    val metro: String? = null,
    val city: String? = null,
    val zip: String? = null
)

data class DeviceExt(
    @SerializedName("ifa_type") val ifaType: String
)

data class Device(
    val ua: String,
    val geo: Geo?,
    val dnt: Int,
    val lmt: Int,
    val ip: String? = null,
    val devicetype: Int,
    val model: String,
    val os: String,
    val js: Int,
    val ifa: String,
    val ext: DeviceExt
)

data class App(
    val name: String,
    val bundle: String,
    val storeurl: String
)

data class User(
    val id: String
)

data class Regs(
    val gdpr: Int
)

data class VastAdRequestDto(
    val id: String,
    val imp: List<Imp>,
    val app: App,
    val device: Device,
    val user: User,
    val at: Int,
    val tmax: Int,
    val cur: List<String>,
    val regs: Regs
) {
    companion object {
        const val TAG = "VastAdRequestDto"
    }

    fun toJson(): String {
        return Gson().toJson(this)
    }
}
