package com.engage.engageadssdk.data

import com.engage.engageadssdk.network.VASTResponse
import com.engage.engageadssdk.network.MediaFile
import java.text.SimpleDateFormat

class EMAdMapper {

    fun mapToEMVASTAd(vastResponse: VASTResponse): List<EMVASTAd> {
        // Mapping VASTResponse to EMVASTAd based on the creatives and media files
        // The first creative is a PREROLL ad, otherwise it's a midroll ad based on the offset
        // the last ad is a POSTROLL ad
        val adSystem = vastResponse.Ad?.InLine?.AdSystem
        val adTitle = vastResponse.Ad?.InLine?.AdTitle
        val adId = vastResponse.Ad?.id
        val emVastAds = vastResponse.Ad?.InLine?.Creatives?.Creative?.map {
            when (it.sequence) {
                1 -> EMVASTPreLoadAd(
                    adSystem = adSystem,
                    adTitle = adTitle,
                    adMediaFiles = mapToEMVASTMediaFile(
                        adSystem,
                        adTitle,
                        listOf(it.Linear?.MediaFiles?.MediaFile),
                        adId
                    ),
                    adId = adId,
                    adSequence = it.sequence ?: 0,
                    adError = vastResponse.extensions?.serverError,
                )

                vastResponse.Ad?.InLine?.Creatives?.Creative?.size?.minus(1) -> {
                    EMVASTPostrollAd(
                        adSystem = adSystem,
                        adTitle = adTitle,
                        adMediaFiles = mapToEMVASTMediaFile(
                            adSystem,
                            adTitle,
                            listOf(it.Linear?.MediaFiles?.MediaFile),
                            adId
                        ),
                        adId = adId,
                        adSequence = it.sequence,
                        adError = vastResponse.extensions?.serverError,

                        )
                }

                else -> EMVASTMidrollAd(
                    offset = SimpleDateFormat.getDateInstance().parse(it.Offset!!),
                    adSystem = adSystem,
                    adTitle = adTitle,
                    adMediaFiles = mapToEMVASTMediaFile(
                        adSystem,
                        adTitle,
                        listOf(it.Linear?.MediaFiles?.MediaFile),
                        adId
                    ),
                    adId = adId,
                    adSequence = it.sequence ?: 0,
                    adError = vastResponse.extensions?.serverError,
                )
            }
        }

        return emVastAds ?: emptyList()
    }

    private fun mapToEMVASTMediaFile(
        adSystem: String?,
        adTitle: String?,
        adMediaFiles: List<MediaFile?>?,
        adId: String?,
    ): List<EMVASTMediaFile>? {
        return adMediaFiles?.map {
            EMVASTMediaFile(
                id = it?.id,
                delivery = it?.delivery,
                width = it?.width ?: 0,
                height = it?.height ?: 0,
                type = it?.type,
                bitrate = it?.bitrate ?: 0,
                scalable = it?.scalable ?: false,
                maintainAspectRatio = it?.maintainAspectRatio ?: false,
                text = it?.adLink
            )
        }
    }

}