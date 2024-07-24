# Engage Ads SDK

Version: 1.0.24-alpha

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
    implementation 'com.engage.engageadssdk:engage-ads-sdk:v1.0.24-alpha'
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
})
```

2. Create an `EMAdView` and set it up with your `Activity` or `Fragment`:

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

3. Load and display ads according to your application's flow.

## Documentation

For detailed documentation and additional usage examples, please refer to the [official documentation](#).

## Contributing

We welcome contributions! Please submit any bugs, issues, or feature requests through the GitHub issue tracker.

## License

Engage Ads SDK is licensed under the MIT License. See the LICENSE file for more details.