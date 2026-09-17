package com.engage.ads

internal object ResourceLimits {
    const val MAX_HTTP_RESPONSE_BYTES = 2L * 1024L * 1024L
    const val MAX_OPENRTB_REQUEST_BYTES = 1L * 1024L * 1024L
    const val MAX_OPENRTB_RESPONSE_BYTES = 2L * 1024L * 1024L
    const val MAX_VAST_BYTES = 1L * 1024L * 1024L
    const val MAX_JSON_DEPTH = 64
    const val MAX_XML_DEPTH = 64
    const val MAX_NATIVE_ASSETS = 64
    const val MAX_NATIVE_TRACKERS = 128
    const val MAX_MEASUREMENT_VERIFICATION_RESOURCES = 32
    const val MAX_MEASUREMENT_URL_BYTES = 2_048L
    const val MAX_MEASUREMENT_VENDOR_KEY_BYTES = 256L
    const val MAX_MEASUREMENT_PARAMETERS_BYTES = 4_096L
    const val MAX_MEASUREMENT_HTML_BYTES = 4L * 1024L * 1024L
    const val MAX_FRIENDLY_OBSTRUCTIONS = 32
    const val MAX_NATIVE_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_NATIVE_IMAGE_PIXELS = 4_194_304L
}

internal fun String.exceedsUtf8Bytes(limit: Long): Boolean {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        bytes += when {
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            character.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true -> {
                index += 1
                4
            }
            else -> 3
        }
        if (bytes > limit) return true
        index += 1
    }
    return false
}
