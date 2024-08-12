# Engage Ads SDK

Version: 1.0.32-alpha

## Overview

Engage Ads SDK is a comprehensive solution designed to integrate video ads into Android applications seamlessly. This SDK supports various ad formats, including pre-roll, mid-roll, and post-roll ads, leveraging the VAST standard for video ads. It is built with Kotlin and is compatible with Java projects.

## Features

- Support for VAST 2.0, 3.0, and 4.0.
- Pre-roll, Mid-roll, and Post-roll ad support.
- Easy integration with ExoPlayer.
- GDPR compliance support.
- Customizable ad loading and interaction listeners.

## Requirements

- Android SDK version 21 (Lollipop) or higher.
- Android Studio Koala | 2024.1.1 Patch 1 or later.
- Kotlin version 1.9.0.
- Gradle version 8.5.0.

## Installation

Add the following dependencies to your `build.gradle` file:

```groovy
dependencies {
    implementation 'com.engage.engageadssdk:engage-ads-sdk:v1.0.27-alpha'
}
```

Ensure you have `mavenCentral()` in your project's repositories list:

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

## Usage

1. Initialize the SDK in your `Application` class or before you start loading ads:

```kotlin
EMAdsModule.init(object: EMAdsModuleInput {
    override val isGdprApproved: Boolean = true
    override val publisherId: String = "Your Publisher ID"
    override val userId: String = "User ID"
    override val channelId: String = "Channel ID"
    override val context: Context = applicationContext
    override val isDebug: Boolean = true // To see debug ads set this to true
    override val bundleId: String? = if (isAmazonTVApp()) "Your Bundle ID" else null
})
```

2. Create an `EMAdView` and set it up with your `Activity` or `Fragment`:

### XML

```xml
<com.engage.engageadssdk.EMAdView
    android:id="@+id/adView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"/>
```
and in your `Activity` or `Fragment`:
```kotlin
val adView = findViewById<EMAdView>(R.id.adView).apply {
    setAdEventListener(DefaultVideoPlayerListener(playerView))
    setClientListener(object: EMClientListener {
        override fun onAdsLoaded() {
            // Ad loaded successfully
        }

        override fun onAdsLoadFailed() {
            // Ad loading failed
        }

        override fun onAdStarted() {
            // Ad started
        }

        override fun onAdCompleted() {
            // Ad completed
        }

        override fun onAdTapped(ad: EMVASTAd?) {
            // Ad tapped
        }

        override fun onNoAdsLoaded() {
            // No ads loaded
        }
    })
}
```
### Jetpack Compose
```kotlin
@Composable
fun AdViewComposable() {
    AndroidView(
        factory = { context ->
            EMAdView(context).apply {
                setAdEventListener(DefaultVideoPlayerListener(playerView))
                setClientListener(object: EMClientListener {
                    override fun onAdsLoaded() {
                        // Ad loaded successfully
                    }

                    override fun onAdsLoadFailed() {
                        // Ad loading failed
                    }

                    override fun onAdStarted() {
                        // Ad started
                    }

                    override fun onAdCompleted() {
                        // Ad completed
                    }

                    override fun onAdTapped(ad: EMVASTAd?) {
                        // Ad tapped
                    }

                    override fun onNoAdsLoaded() {
                        // No ads loaded
                    }
                })
            }
        },
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
    )
}
```
### Simple Kotlin/Java Format
```kotlin
val adView = EMAdView(context).apply {
    setAdEventListener(DefaultVideoPlayerListener(playerView))
    setClientListener(object: EMClientListener {
        override fun onAdsLoaded() {
            // Ad loaded successfully
        }

        override fun onAdsLoadFailed() {
            // Ad loading failed
        }

        override fun onAdStarted() {
            // Ad started
        }

        override fun onAdCompleted() {
            // Ad completed
        }

        override fun onAdTapped(ad: EMVASTAd?) {
            // Ad tapped
        }

        override fun onNoAdsLoaded() {
            // No ads loaded
        }
    })
}
```
3. Load ads using the `EMAdView` instance (or let the SDK do it for you automatically):

```kotlin
adView.loadAd()
```

# Contributing
We welcome contributions to the Engage Ads SDK. To contribute, Please submit any bugs, issues, or feature requests through the GitHub issue tracker. 

# License
Engage Ads SDK is licensed under the MIT License. See the LICENSE file for more details.
```