package com.engage.engagemediaadssdk.ui.native

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMClientListener
import com.engage.engageadssdk.data.EMVASTAd

class KotlinAdActivity : AppCompatActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = FrameLayout(this)

        val adView = EMAdView(this)
        layout.addView(adView)
        adView.apply {
            setClientListener(object : EMClientListener {
                override fun onAdsLoaded() {}
                override fun onAdsLoadFailed() {}
                override fun onAdStarted() {
                    // Ad has started
                    adView.isVisible = true
                    Toast.makeText(this@KotlinAdActivity, "Ad has started", Toast.LENGTH_SHORT).show()
                }
                override fun onAdCompleted() {
                    // Ad has completed
                    adView.isVisible = false
                    Toast.makeText(this@KotlinAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
                }
                override fun onAdTapped(ad: EMVASTAd?) {}
                override fun onNoAdsLoaded() {
                    adView.isVisible = false
                    Toast.makeText(this@KotlinAdActivity, "No ads loaded", Toast.LENGTH_SHORT).show()
                }
            })
            loadAd()
        }

        val textView = TextView(this).apply {
            text = "your content goes here"
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        layout.addView(textView)

        setContentView(layout)
    }
}