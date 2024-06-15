package com.engage.engagemediaadssdk

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.engage.engageadssdk.DefaultVideoPlayerListener
import com.engage.engageadssdk.EMAdView
import com.engage.engageadssdk.module.EMAdsModule
import com.engage.engageadssdk.module.EMAdsModuleInput
import com.engage.engagemediaadssdk.ui.theme.EngageMediaAdsSdkTheme

class MainActivity : ComponentActivity() {
    @UnstableApi
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EMAdsModule.init(object:  EMAdsModuleInput {
            override val isGdprApproved: Boolean
                get() = true
            override val userId: String
                get() = "1111"
            override val context: Context
                get() = applicationContext

        })
        setContent {
            EngageMediaAdsSdkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
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