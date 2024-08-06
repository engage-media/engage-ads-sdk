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
import com.engage.engagemediaadssdk.R
import com.engage.engagemediaadssdk.ui.compose.ComposeAdActivity
import com.engage.engagemediaadssdk.ui.native.KotlinAdActivity
import com.engage.engagemediaadssdk.ui.xml.XmlAdActivity

class DemoActivity : AppCompatActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        EMAdsModule.init(object: EMAdsModuleInput {
            override val isGdprApproved: Boolean
                get() = true
            override val publisherId: String
                get() = "Some Publisher ID"
            override val userId: String
                get() = "1111"
            override val channelId: String
                get() = "Some Channel ID"
            override val context: Context
                get() = applicationContext

        })

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