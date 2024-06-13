package com.engage.engageadssdk.network


class AdTagURI(
    var templateType: String? = null,
    var text: String? = null,
)

class AdSource(
    var AdTagURI: AdTagURI? = null,
    var id: String? = null,
    var allowMultipleAds: Boolean = false,
    var followRedirects: Boolean = false,
    var text: String? = null,
)

data class AdBreak(
    var AdSource: AdSource? = null,
    var timeOffset: String? = null,
    var breakType: String? = null,
    var breakId: String? = null,
    var text: String? = null,
)

data class VMAPResponse(
    var AdBreak: List<AdBreak>? = null,
    var vmap: String? = null,
    var version: Double = 0.0,
    var text: String? = null,
)