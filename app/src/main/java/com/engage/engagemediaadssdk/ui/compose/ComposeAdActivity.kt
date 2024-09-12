package com.engage.engagemediaadssdk.ui.compose

import android.os.Bundle
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
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.EMVideoPlayerListener
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.ui.EmClientContentController
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
                                    adView = this
                                    adView.setContentController(object: EmClientContentController {
                                        override fun pauseContent() {
                                            Toast.makeText(this@ComposeAdActivity, "My content has paused", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun resumeContent() {
                                            Toast.makeText(this@ComposeAdActivity, "My content has resumed", Toast.LENGTH_SHORT).show()
                                        }

                                    })
                                    adView.setAdEventListener(object : EMVideoPlayerListener {
                                        override fun onAdLoading() {
                                            Toast.makeText(this@ComposeAdActivity, "Ad is loading", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onAdsLoaded() {
                                            Toast.makeText(this@ComposeAdActivity, "Ad has Loaded", Toast.LENGTH_SHORT).show()
                                            if (!EMAdsModule.getInstance().isAutoPlay) {
                                                // Example given here.
                                                adView.showAd()
                                            }
                                        }

                                        override fun onAdLoadError(message: String) {
                                            super.onAdLoadError(message)
                                            Toast.makeText(this@ComposeAdActivity, "Ad load failed", Toast.LENGTH_SHORT).show()
                                            adView.isVisible = false
                                        }

                                        override fun onAdStarted() {
                                            adView.isVisible = true
                                            Toast.makeText(this@ComposeAdActivity, "Ad has started", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onAdEnded() {
                                            adView.isVisible = false
                                            Toast.makeText(this@ComposeAdActivity, "Ad has completed", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onAdPaused() {
                                            Toast.makeText(this@ComposeAdActivity, "Ad has paused", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onAdResumed() {
                                            Toast.makeText(this@ComposeAdActivity, "Ad has resumed", Toast.LENGTH_SHORT).show()
                                        }

                                    })
                                    adView.loadAd()
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