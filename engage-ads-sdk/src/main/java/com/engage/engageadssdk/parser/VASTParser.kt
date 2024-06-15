package com.engage.engageadssdk.parser

import com.engage.engageadssdk.network.VASTResponse
import com.engage.engageadssdk.network.response.json.EMVASTResponseDto
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import java.io.StringReader


class VASTParser {

    fun parseEMVASTResponseDto(someResponse: String?): EMVASTResponseDto {
        val response = EMVASTResponseDto.fromJson(someResponse)
        response.seatbid.forEach { seatBid ->
            seatBid.bid.forEach { bid ->
                bid.admParsed = parseVASTResponse(bid.adm)
            }
        }
        return response
    }

    @Throws(Exception::class)
    fun parseVASTResponse(someResponse: String?): VASTResponse {
        val serializer: Serializer = Persister()
        return serializer.read(VASTResponse::class.java, StringReader(someResponse))
    }
}
