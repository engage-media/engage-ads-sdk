package com.engage.engageadssdk.data

import java.util.Date

sealed class EMVASTAd(
    val adSystem: String? = null,
    val adTitle: String? = null,
    val adDescription: String? = null,
    val adDuration: String? = null,
    val adMediaFiles: List<EMVASTMediaFile>? = null,
    val adId: String? = null,
    val adSequence: Int = 0,
    val adError: String? = null,
    val adImpressions: List<String>? = null,
)

class EMVASTMediaFile(
    val id: String? = null,
    val delivery: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val type: String? = null,
    val bitrate: Int = 0,
    val scalable: Boolean = false,
    val maintainAspectRatio: Boolean = false,
    val text: String? = null,
)

class EMVASTPreLoadAd(
    adSystem: String? = null,
    adTitle: String? = null,
    adDescription: String? = null,
    adDuration: String? = null,
    adMediaFiles: List<EMVASTMediaFile>? = null,
    adId: String? = null,
    adSequence: Int = 0,
    adError: String? = null,
    adImpressions: List<String>? = null,
) : EMVASTAd(
    adSystem = adSystem,
    adTitle = adTitle,
    adDescription = adDescription,
    adDuration = adDuration,
    adMediaFiles = adMediaFiles,
    adId = adId,
    adSequence = adSequence,
    adError = adError,
    adImpressions = adImpressions
)


class EMVASTMidrollAd(
    val offset: Date? = null,
    adSystem: String? = null,
    adTitle: String? = null,
    adDescription: String? = null,
    adDuration: String? = null,
    adMediaFiles: List<EMVASTMediaFile>? = null,
    adId: String? = null,
    adSequence: Int = 0,
    adError: String? = null,
    adImpressions: List<String>? = null,
) : EMVASTAd(
    adSystem = adSystem,
    adTitle = adTitle,
    adDescription = adDescription,
    adDuration = adDuration,
    adMediaFiles = adMediaFiles,
    adId = adId,
    adSequence = adSequence,
    adError = adError,
    adImpressions = adImpressions
)

class EMVASTPostrollAd(
    adSystem: String? = null,
    adTitle: String? = null,
    adDescription: String? = null,
    adDuration: String? = null,
    adMediaFiles: List<EMVASTMediaFile>? = null,
    adId: String? = null,
    adSequence: Int = 0,
    adError: String? = null,
    adImpressions: List<String>? = null,
) : EMVASTAd(
    adSystem = adSystem,
    adTitle = adTitle,
    adDescription = adDescription,
    adDuration = adDuration,
    adMediaFiles = adMediaFiles,
    adId = adId,
    adSequence = adSequence,
    adError = adError,
    adImpressions = adImpressions
)