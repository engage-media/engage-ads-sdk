package com.engage.engageadssdk.network.response.json

import com.engage.engageadssdk.network.VASTResponse
import com.google.gson.Gson

data class EMVASTResponseDto(
    val id: String,
    val seatbid: List<SeatBid>,
    val bidid: String,
    val cur: String
) {
    companion object {
        fun fromJson(someResponse: String?): EMVASTResponseDto {
            return Gson().fromJson(someResponse, EMVASTResponseDto::class.java)
        }
    }
}

data class SeatBid(
    val bid: List<Bid>,
    val seat: String
)

data class Bid(
    val id: String,
    val impid: String,
    val price: Double,
    val adid: String,
    val nurl: String,
    val burl: String,
    val lurl: String,
    val adm: String,
    val cat: List<String>,
    val w: Int,
    val h: Int
) {
    var admParsed: VASTResponse? = null
}
