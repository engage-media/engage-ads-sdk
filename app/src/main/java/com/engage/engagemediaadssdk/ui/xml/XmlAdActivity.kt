// XmlAdActivity.kt
package com.engage.engagemediaadssdk.ui.xml

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMClientListener
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engagemediaadssdk.R

class XmlAdActivity : AppCompatActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_ad)

        val adView = findViewById<EMAdView>(R.id.adView)
        adView.setClientListener(object : EMClientListener {
            override fun onAdsLoaded() {}
            override fun onAdsLoadFailed() {
                Toast.makeText(this@XmlAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
            }
            override fun onAdStarted() {
                adView.isVisible = true
                Toast.makeText(this@XmlAdActivity, "Ad has started", Toast.LENGTH_SHORT).show()
            }
            override fun onAdCompleted() {
                adView.isVisible = false
                Toast.makeText(this@XmlAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
            }
            override fun onAdTapped(ad: EMVASTAd?) {}
            override fun onNoAdsLoaded() {
                adView.isVisible = false
                Toast.makeText(this@XmlAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()

            }
        })
        adView.loadAd()
    }
}