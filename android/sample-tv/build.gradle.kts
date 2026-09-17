plugins { id("com.android.application"); kotlin("android") }

fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.engage.ads.sample.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.engage.ads.sample.tv"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1.0"
        buildConfigField("String", "ENGAGE_POD_ENDPOINT", buildConfigString(providers.gradleProperty("engageSamplePodEndpoint").orElse("http://10.0.2.2:8787/openrtb/2.6/auto").get()))
        buildConfigField("String", "ENGAGE_POD_PROTOCOL", buildConfigString(providers.gradleProperty("engageSamplePodProtocol").orElse("openrtb26").get()))
    }
    buildFeatures { buildConfig = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5"); implementation(project(":tv")) }
