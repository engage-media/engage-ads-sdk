
# Engage Ads SDK

Engage Ads SDK is a comprehensive library designed to simplify the integration of advertisement functionality into your Android applications. It provides a range of features to manage ads and enhance user experience.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)
- [Configuration](#configuration)
- [License](#license)
- [Contributing](#contributing)
- [Contact Information](#contact-information)

## Installation

To include Engage Ads SDK in your project, you can add it as a dependency from JitPack.

### Step 1: Add JitPack Repository

Add the JitPack repository to your root `build.gradle` file:

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2: Add Dependency

Add the dependency to your module-level `build.gradle` file:

```groovy
dependencies {
    implementation 'com.github.engage-media:engage-ads-sdk:v1.0.2'
}
```

## Usage

### Step 1: Initialize the SDK

In your `MainActivity.kt` (or equivalent), initialize the SDK:

```kotlin
import com.engage.engageadssdk.EMAdsModule
import com.engage.engageadssdk.EMAdsModuleInput

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adsModule = EMAdsModule(object : EMAdsModuleInput {
            override val isGdprApproved: Boolean
                get() = true // or fetch from user settings
            override val userId: String
                get() = "user123"
            override val context: Context
                get() = this@MainActivity
        })
    }
}
```

### Step 2: Display an Ad

Use `EMAdView` to display an ad:

```kotlin
import com.engage.engageadssdk.EMAdView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adView = findViewById<EMAdView>(R.id.adView)
        adView.setAdEventListener(object : EMVideoPlayerListener() {
            override fun onAdStarted() {
                // Ad started
            }

            override fun onAdCompleted() {
                // Ad completed
            }
        })
    }
}
```

Or alternatively, use the default:

```kotlin
import com.engage.engageadssdk.EMAdView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adView = findViewById<EMAdView>(R.id.adView)
        adView.setAdEventListener(DefaultAdEventListener())
    }
}
```

### XML Layout

Add the `EMAdView` to your XML layout:

```xml
<com.engage.engageadssdk.EMAdView
    android:id="@+id/adView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

## Configuration

You can configure various parameters of the Engage Ads SDK through the `EMAdsModuleInput` interface. Customize as needed for your application requirements.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

We welcome contributions to enhance Engage Ads SDK. Please follow the guidelines outlined in the [CONTRIBUTING](CONTRIBUTING.md) file.

## Contact Information

For any inquiries, please contact us at [info@engagemedia.com](mailto:info@engagemedia.com).
