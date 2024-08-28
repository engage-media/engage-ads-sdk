package com.engage.engageadssdk.parser

import com.engage.engageadssdk.network.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

fun parseVASTResponse(response: String?): VASTResponse {
    if (response == null) {
        return VASTResponse()
    }
    return parseVASTResponse(response.byteInputStream())
}

fun parseVASTResponse(inputStream: InputStream): VASTResponse {
    val factory = XmlPullParserFactory.newInstance()
    val parser = factory.newPullParser()
    parser.setInput(inputStream, null)

    val vastResponse = VASTResponse()
    var currentAd: Ad? = null
    var currentInLine: InLine? = null
    var currentCreative: Creative? = null
    var currentMediaFile: MediaFile? = null
    var currentWrapper: Wrapper? = null

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        val tagName = parser.name
        when (eventType) {
            XmlPullParser.START_TAG -> {
                when (tagName) {
                    "VAST" -> {
                        vastResponse.version = parser.getAttributeValue(null, "version")?.toDouble() ?: 0.0
                    }
                    "Ad" -> {
                        currentAd = Ad()
                        currentAd.id = parser.getAttributeValue(null, "id")
                        vastResponse.ad = currentAd
                    }
                    "InLine" -> {
                        currentInLine = InLine()
                        currentAd?.inLine = currentInLine
                    }
                    "AdSystem" -> {
                        if (currentInLine != null) {
                            currentInLine.adSystem = parser.nextText()
                        } else if (currentWrapper != null) {
                            currentWrapper.adSystem = AdSystem(parser.nextText(), null)
                        }
                    }
                    "AdTitle" -> {
                        currentInLine?.adTitle = parser.nextText()
                    }
                    "Creatives" -> {
                        if (currentInLine != null) {
                            currentInLine.creatives = Creatives(mutableListOf())
                        } else if (currentWrapper != null) {
                            currentWrapper.creatives = Creatives(mutableListOf())
                        }
                    }
                    "Creative" -> {
                        currentCreative = Creative()
                        currentCreative.sequence = parser.getAttributeValue(null, "sequence")?.toInt() ?: 0
                        currentInLine?.creatives?.creative?.add(currentCreative)
                        currentWrapper?.creatives?.creative?.add(currentCreative)
                    }
                    "Linear" -> {
                        currentCreative?.linear = Linear()
                    }
                    "Duration" -> {
                        currentCreative?.linear?.duration = parser.nextText()
                    }
                    "MediaFiles" -> {
                        currentCreative?.linear?.mediaFiles = MediaFiles()
                    }
                    "MediaFile" -> {
                        currentMediaFile = MediaFile()
                        currentMediaFile.id = parser.getAttributeValue(null, "id")
                        currentMediaFile.delivery = parser.getAttributeValue(null, "delivery")
                        currentMediaFile.width = parser.getAttributeValue(null, "width")?.toInt() ?: 0
                        currentMediaFile.height = parser.getAttributeValue(null, "height")?.toInt() ?: 0
                        currentMediaFile.type = parser.getAttributeValue(null, "type")
                        currentMediaFile.bitrate = parser.getAttributeValue(null, "bitrate")?.toInt() ?: 0
                        currentMediaFile.scalable = parser.getAttributeValue(null, "scalable")?.toBoolean() ?: false
                        currentMediaFile.maintainAspectRatio = parser.getAttributeValue(null, "maintainAspectRatio")?.toBoolean() ?: false
                        currentCreative?.linear?.mediaFiles?.mediaFile = currentMediaFile
                    }
                    "Wrapper" -> {
                        currentWrapper = Wrapper()
                        currentAd?.wrapper = currentWrapper
                    }
                    "VASTAdTagURI" -> {
                        if (currentWrapper != null) {
                            currentWrapper.vastAdTagURI = VASTAdTagURI(parser.nextText())
                        }
                    }
                }
            }
            XmlPullParser.TEXT -> {
                currentMediaFile?.adLink = parser.text
            }
            XmlPullParser.END_TAG -> {
                when (tagName) {
                    "MediaFile" -> {
                        currentMediaFile = null
                    }
                    "Creative" -> {
                        currentCreative = null
                    }
                    "InLine" -> {
                        currentInLine = null
                    }
                    "Ad" -> {
                        currentAd = null
                    }
                    "Wrapper" -> {
                        currentWrapper = null
                    }
                }
            }
        }
        eventType = parser.next()
    }

    return vastResponse
}
