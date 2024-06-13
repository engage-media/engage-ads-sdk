package com.engage.engageadssdk.network

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text


@Root(name = "Extensions", strict = false)
data class Extensions @JvmOverloads constructor(
    @field:Element(name = "ServerError", required = false)
    var serverError: String? = null
)

@Root(name = "MediaFile", strict = false)
data class MediaFile @JvmOverloads constructor(
    @field:Attribute(name = "id", required = false)
    var id: String? = null,
    @field:Attribute(name = "delivery", required = false)
    var delivery: String? = null,
    @field:Attribute(name = "width", required = false)
    var width: Int = 0,
    @field:Attribute(name = "height", required = false)
    var height: Int = 0,
    @field:Attribute(name = "type", required = false)
    var type: String? = null,
    @field:Attribute(name = "bitrate", required = false)
    var bitrate: Int = 0,
    @field:Attribute(name = "scalable", required = false)
    var scalable: Boolean = false,
    @field:Attribute(name = "maintainAspectRatio", required = false)
    var maintainAspectRatio: Boolean = false,
    @field:Text(data = true, required = false)
    var adLink: String? = null
)

@Root(name = "MediaFiles", strict = false)
data class MediaFiles @JvmOverloads constructor(
    @field:Element(name = "MediaFile", required = false)
    var MediaFile: MediaFile? = null
)

@Root(name = "Linear", strict = false)
data class Linear @JvmOverloads constructor(
    @field:Element(name = "Duration", required = false)
    var Duration: String? = null,
    @field:Element(name = "MediaFiles", required = false)
    var MediaFiles: MediaFiles? = null,
)

@Root(name = "Creative", strict = false)
data class Creative @JvmOverloads constructor(
    @field:Attribute(name = "offset", required = false,)
    var Offset: String? = null,
    @field:Element(name = "Linear", required = false)
    var Linear: Linear? = null,
    @field:Attribute(name = "sequence", required = false)
    var sequence: Int = 0,
)

@Root(name = "Creatives", strict = false)
data class Creatives @JvmOverloads constructor(
    @field:ElementList(required = false, inline = true, entry = "Creative", )
    var Creative: List<Creative>? = null
)

@Root(name = "InLine", strict = false)
data class InLine @JvmOverloads constructor(
    @field:Element(name = "AdSystem", required = false)
    var AdSystem: String? = null,
    @field:Element(name = "AdTitle", required = false)
    var AdTitle: String? = null,
    @field:Element(name = "Creatives", required = false,)
    var Creatives: Creatives? = null,
)

@Root(name = "Ad", strict = false )
class Ad @JvmOverloads constructor(
    @field:Element(name = "InLine", required = false)
    var InLine: InLine? = null,
    @field:Attribute(name = "id", required = false)
    var id: String? = null,
    var text: String? = null,
)

@Root(name = "VAST", strict = false)
data class VASTResponse @JvmOverloads constructor(
    @field:Element(name = "Ad", )
    var Ad: Ad? = null,
    @field:Attribute(name = "version",)
    var version: Double = 0.0,
    @field:Element(name = "Extensions", required = false)
    var extensions: Extensions? = null,
)

