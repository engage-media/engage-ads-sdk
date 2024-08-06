package com.engage.engagemediaadssdk.ui.compose

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.engage.engageadssdk.DefaultVideoPlayerListener
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMClientListener
import com.engage.engageadssdk.data.EMVASTAd
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.module.EMAdsModuleInput
import com.engage.engagemediaadssdk.ui.theme.EngageMediaAdsSdkTheme

@UnstableApi
class ComposeAdActivity : ComponentActivity() {
    private lateinit var adView: EMAdView

    @UnstableApi
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EngageMediaAdsSdkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    Text(text = "Content Goes here", modifier = Modifier.fillMaxSize().align(
                        Alignment.Center))
                    AndroidView(
                        factory = { context ->
                            FrameLayout(context).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                                val playerView = PlayerView(context).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                                playerView.player = ExoPlayer.Builder(context)
                                    .build()
                                addView(playerView)
                                addView(EMAdView(context).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                    setAdEventListener(DefaultVideoPlayerListener(playerView))
                                    adView = this
                                    setClientListener(object: EMClientListener {
                                        override fun onAdsLoaded() {
                                            // log
                                            Log.d("MainActivity", "Ads loaded")
                                            Toast.makeText(this@ComposeAdActivity, "Ads loaded", Toast.LENGTH_SHORT).show()
                                            adView.isVisible = true
                                        }

                                        override fun onAdsLoadFailed() {
                                            }

                                        override fun onAdStarted() {
                                            Toast.makeText(this@ComposeAdActivity, "Ads Started", Toast.LENGTH_SHORT).show()
                                            adView.isVisible = true
                                        }

                                        override fun onAdCompleted() {
                                            Toast.makeText(this@ComposeAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
                                            adView.isVisible = false
                                        }

                                        override fun onAdTapped(ad: EMVASTAd?) {
                                            //TODO("Not yet implemented")
                                        }

                                        override fun onNoAdsLoaded() {
                                            // log
                                            Log.d("MainActivity", "No ads loaded")
                                            Toast.makeText(this@ComposeAdActivity, "No ads loaded", Toast.LENGTH_SHORT).show()
                                            adView.isVisible = false
                                        }

                                    })
                                })
                            }

                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EngageMediaAdsSdkTheme {
        Greeting("Android")
    }
}