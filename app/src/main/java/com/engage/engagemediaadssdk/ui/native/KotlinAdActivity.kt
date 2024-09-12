package com.engage.engagemediaadssdk.ui.native

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EmClientContentController

class KotlinAdActivity : AppCompatActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = FrameLayout(this)

        val adView = EMAdView(this)
        layout.addView(adView)
        adView.setContentController(object: EmClientContentController {
            override fun pauseContent() {
                Toast.makeText(this@KotlinAdActivity, "My content has paused", Toast.LENGTH_SHORT).show()
            }

            override fun resumeContent() {
                Toast.makeText(this@KotlinAdActivity, "My content has resumed", Toast.LENGTH_SHORT).show()
            }

        })
        adView.setAdEventListener(object : EMVideoPlayerListener {
            override fun onAdsLoaded() {
                Toast.makeText(this@KotlinAdActivity, "Ad has Loaded", Toast.LENGTH_SHORT).show()
                if (!EMAdsModule.getInstance().isAutoPlay) {
                    // Example given here.
                    adView.showAd()
                }
            }

            override fun onAdLoadError(message: String) {
                super.onAdLoadError(message)
                Toast.makeText(this@KotlinAdActivity, "Ad load failed", Toast.LENGTH_SHORT).show()
                adView.isVisible = false
            }

            override fun onAdLoading() {
                Toast.makeText(this@KotlinAdActivity , "Ad is loading", Toast.LENGTH_SHORT).show()
            }

            override fun onAdStarted() {
                adView.isVisible = true
                Toast.makeText(this@KotlinAdActivity, "Ad has started", Toast.LENGTH_SHORT).show()
            }

            override fun onAdEnded() {
                adView.isVisible = false
                Toast.makeText(this@KotlinAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
            }

            override fun onAdPaused() {
                Toast.makeText(this@KotlinAdActivity, "Ad has paused", Toast.LENGTH_SHORT).show()
            }

            override fun onAdResumed() {
                Toast.makeText(this@KotlinAdActivity, "Ad has resumed", Toast.LENGTH_SHORT).show()
            }

        })
        adView.loadAd()

        val textView = TextView(this).apply {
            text = "your content goes here"
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        layout.addView(textView)

        setContentView(layout)
    }
}