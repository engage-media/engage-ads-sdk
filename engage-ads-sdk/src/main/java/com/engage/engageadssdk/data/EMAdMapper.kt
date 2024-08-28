package com.engage.engageadssdk.data

import com.engage.engageadssdk.network.MediaFile
import com.engage.engageadssdk.network.VASTResponse
import java.text.SimpleDateFormat

object EMAdMapper {

    fun mapToEMVASTAd(vastResponse: VASTResponse, vastUrl: String): List<EMVASTAd> {
        // Mapping VASTResponse to EMVASTAd based on the creatives and media files
        // The first creative is a PREROLL ad, otherwise it's a midroll ad based on the offset
        // the last ad is a POSTROLL ad
        val adSystem = vastResponse.ad?.inLine?.adSystem
        val adTitle = vastResponse.ad?.inLine?.adTitle
        val adId = vastResponse.ad?.id
        val emVastAds = vastResponse.ad?.inLine?.creatives?.creative?.map {
            when (it.sequence) {
                1 -> EMVASTPreLoadAd(
                    adSystem = adSystem,
                    adTitle = adTitle,
                    adMediaFiles = mapToEMVASTMediaFile(
                        adSystem,
                        adTitle,
                        listOf(it.linear?.mediaFiles?.mediaFile),
                        adId
                    ),
                    adId = adId,
                    adSequence = it.sequence ?: 0,
                    adError = vastResponse.extensions?.serverError,
                    vastUrl = vastUrl,
                )

                vastResponse.ad?.inLine?.creatives?.creative?.size?.minus(1) -> {
                    EMVASTPostrollAd(
                        adSystem = adSystem,
                        adTitle = adTitle,
                        adMediaFiles = mapToEMVASTMediaFile(
                            adSystem,
                            adTitle,
                            listOf(it.linear?.mediaFiles?.mediaFile),
                            adId
                        ),
                        adId = adId,
                        adSequence = it.sequence,
                        adError = vastResponse.extensions?.serverError,
                        vastUrl = vastUrl,
                        )
                }

                else -> EMVASTMidrollAd(
                    offset = SimpleDateFormat.getDateInstance().parse(it.offset!!),
                    adSystem = adSystem,
                    adTitle = adTitle,
                    adMediaFiles = mapToEMVASTMediaFile(
                        adSystem,
                        adTitle,
                        listOf(it.linear?.mediaFiles?.mediaFile),
                        adId
                    ),
                    adId = adId,
                    adSequence = it.sequence ?: 0,
                    adError = vastResponse.extensions?.serverError,
                    vastUrl = vastUrl,
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