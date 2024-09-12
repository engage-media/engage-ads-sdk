// DemoActivity.kt
package com.engage.engagemediaadssdk.ui.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.module.EMAdsModuleInput
import com.engage.engageadssdk.module.EMAdsModuleInputBuilder
import com.engage.engagemediaadssdk.R
import com.engage.engagemediaadssdk.ui.compose.ComposeAdActivity
import com.engage.engagemediaadssdk.ui.native.KotlinAdActivity
import com.engage.engagemediaadssdk.ui.xml.XmlAdActivity

class DemoActivity : AppCompatActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        EMAdsModule.init(
            EMAdsModuleInputBuilder()
                // Most of these fields are optional and do not have to be set
                .channelId("dd3cc3f6") // must be set
                .publisherId("a8ce40dc") // must be set
//                .bundleId("Some Bundle Id") // defaults to context.packageName, set if Amazon project
                .context(applicationContext) // must be set
                .isGdprApproved(true) // defaults to false
                .isAutoPlay(false) // defaults to false
                .isDebug(true)
                .build()
        )

        findViewById<Button>(R.id.btnXmlAd).setOnClickListener {
            startActivity(Intent(this, XmlAdActivity::class.java))
        }

        findViewById<Button>(R.id.btnKotlinAd).setOnClickListener {
            startActivity(Intent(this, KotlinAdActivity::class.java))
        }

        findViewById<Button>(R.id.btnComposeAd).setOnClickListener {
            startActivity(Intent(this, ComposeAdActivity::class.java))
        }
    }
}