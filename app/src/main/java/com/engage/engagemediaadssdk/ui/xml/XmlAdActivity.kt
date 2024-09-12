// XmlAdActivity.kt
package com.engage.engagemediaadssdk.ui.xml

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EmClientContentController
import com.engage.engagemediaadssdk.R

class XmlAdActivity : AppCompatActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_ad)

        val adView = findViewById<EMAdView>(R.id.adView)
        adView.setContentController(object: EmClientContentController {
            override fun pauseContent() {
                Toast.makeText(this@XmlAdActivity, "My content has paused", Toast.LENGTH_SHORT).show()
            }

            override fun resumeContent() {
                Toast.makeText(this@XmlAdActivity, "My content has resumed", Toast.LENGTH_SHORT).show()
            }

        })
        adView.setAdEventListener(object : EMVideoPlayerListener {
            override fun onAdsLoaded() {
                Toast.makeText(this@XmlAdActivity, "Ad has Loaded", Toast.LENGTH_SHORT).show()
                if (!EMAdsModule.getInstance().isAutoPlay) {
                    // Example given here.
                    adView.showAd()
                }
            }

            override fun onAdLoadError(message: String) {
                super.onAdLoadError(message)
                Toast.makeText(this@XmlAdActivity, "Ad load failed", Toast.LENGTH_SHORT).show()
                adView.isVisible = false
            }

            override fun onAdLoading() {
                Toast.makeText(this@XmlAdActivity, "Ad is loading", Toast.LENGTH_SHORT).show()
            }

            override fun onAdStarted() {
                adView.isVisible = true
                Toast.makeText(this@XmlAdActivity, "Ad has started", Toast.LENGTH_SHORT).show()
            }

            override fun onAdEnded() {
                adView.isVisible = false
                Toast.makeText(this@XmlAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
            }

            override fun onAdPaused() {
                Toast.makeText(this@XmlAdActivity, "Ad has paused", Toast.LENGTH_SHORT).show()
            }

            override fun onAdResumed() {
                Toast.makeText(this@XmlAdActivity, "Ad has resumed", Toast.LENGTH_SHORT).show()
            }

        })
        adView.loadAd()
    }
}