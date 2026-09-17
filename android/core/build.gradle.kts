plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
    signing
}

android {
    namespace = "com.engage.ads"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.google.ads.interactivemedia.v3:interactivemedia:3.40.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// The root packaging workflow supplies the canonical shared/mraid/mraid.js here.
// Keeping the copy explicit prevents either Android artifact from drifting from iOS.
val syncMraidBridge by tasks.registering(Copy::class) {
    val canonical = layout.projectDirectory.file("../../shared/mraid/mraid.js")
    onlyIf { canonical.asFile.exists() }
    from(canonical)
    into(layout.buildDirectory.dir("generated/mraid"))
}
android.sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/mraid"))
tasks.named("preBuild").configure { dependsOn(syncMraidBridge) }

afterEvaluate {
    publishing {
        publications.create<MavenPublication>("release") {
            from(components["release"])
            artifactId = "engage-ads-core"
            pom { engagePom("Core transport and rendering runtime") }
        }
        repositories { maven { name = "Staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repository")) } }
    }
    signing {
        val key = providers.gradleProperty("signingKey").orNull
        if (key != null) { useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull); sign(publishing.publications) }
    }
}

fun MavenPom.engagePom(componentDescription: String) {
    name.set("Engage Ads SDK")
    description.set(componentDescription)
    url.set("https://github.com/engage-media/engage-ads-sdk")
    val licenseName = providers.gradleProperty("licenseName").orNull
    val licenseUrl = providers.gradleProperty("licenseUrl").orNull
    require((licenseName == null) == (licenseUrl == null)) { "licenseName and licenseUrl must be provided together" }
    require(licenseName == null || licenseName.isNotBlank()) { "licenseName must not be blank" }
    require(licenseUrl == null || licenseUrl.startsWith("https://")) { "licenseUrl must be an HTTPS URL" }
    if (licenseName != null && licenseUrl != null) licenses { license { name.set(licenseName); url.set(licenseUrl) } }
    developers { developer { id.set("engage-media"); name.set("Engage Media") } }
    scm { connection.set("scm:git:https://github.com/engage-media/engage-ads-sdk.git"); developerConnection.set("scm:git:ssh://git@github.com/engage-media/engage-ads-sdk.git"); url.set("https://github.com/engage-media/engage-ads-sdk") }
}
