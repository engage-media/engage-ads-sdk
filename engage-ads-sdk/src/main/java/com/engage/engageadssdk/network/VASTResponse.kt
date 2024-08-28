package com.engage.engageadssdk.network

data class Extensions(
    var serverError: String? = null
)

data class MediaFile(
    var id: String? = null,
    var delivery: String? = null,
    var width: Int = 0,
    var height: Int = 0,
    var type: String? = null,
    var bitrate: Int = 0,
    var scalable: Boolean = false,
    var maintainAspectRatio: Boolean = false,
    var adLink: String? = null
)

data class MediaFiles(
    var mediaFile: MediaFile? = null
)

data class Linear(
    var duration: String? = null,
    var mediaFiles: MediaFiles? = null
)

data class Creative(
    var offset: String? = null,
    var linear: Linear? = null,
    var sequence: Int = 0
)

data class Creatives(
    var creative: MutableList<Creative>? = null
)

data class InLine(
    var adSystem: String? = null,
    var adTitle: String? = null,
    var creatives: Creatives? = null
)

data class Ad(
    var inLine: InLine? = null,
    var id: String? = null,
    var wrapper: Wrapper? = null
)

data class Wrapper(
    var adSystem: AdSystem? = null,
    var vastAdTagURI: VASTAdTagURI? = null,
    var error: String? = null,
    var creatives: Creatives? = null,
    var extensions: Extensions? = null
)

data class Impression(
    var text: String? = null
)

data class VASTAdTagURI(
    var text: String? = null
)

data class AdSystem(
    var text: String? = null,
    var version: String? = null
)

data class VASTResponse(
    var ad: Ad? = null,
    var version: Double = 0.0,
    var extensions: Extensions? = null,
    var error: String? = null
) {
    val isEmpty: Boolean
        get() = (ad == null || ad?.wrapper == null || ad?.wrapper?.creatives == null || ad?.wrapper?.creatives?.creative?.isEmpty() == true
                ) && (ad?.inLine == null || ad?.inLine?.creatives?.creative?.isEmpty() == true)
}
