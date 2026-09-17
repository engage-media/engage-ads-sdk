plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
    signing
}
android {
    namespace = "com.engage.ads.tv"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5"); api(project(":core")) }
afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "engage-ads-tv"
        pom { engagePom("TV facade for Engage Ads SDK") }
    }
    publishing.repositories { maven { name = "Staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repository")) } }
    signing { val key = providers.gradleProperty("signingKey").orNull; if (key != null) { useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull); sign(publishing.publications) } }
}

fun MavenPom.engagePom(componentDescription: String) {
    name.set("Engage Ads SDK"); description.set(componentDescription); url.set("https://github.com/engage-media/engage-ads-sdk")
    val licenseName = providers.gradleProperty("licenseName").orNull
    val licenseUrl = providers.gradleProperty("licenseUrl").orNull
    require((licenseName == null) == (licenseUrl == null)) { "licenseName and licenseUrl must be provided together" }
    require(licenseName == null || licenseName.isNotBlank()) { "licenseName must not be blank" }
    require(licenseUrl == null || licenseUrl.startsWith("https://")) { "licenseUrl must be an HTTPS URL" }
    if (licenseName != null && licenseUrl != null) licenses { license { name.set(licenseName); url.set(licenseUrl) } }
    developers { developer { id.set("engage-media"); name.set("Engage Media") } }
    scm { connection.set("scm:git:https://github.com/engage-media/engage-ads-sdk.git"); developerConnection.set("scm:git:ssh://git@github.com/engage-media/engage-ads-sdk.git"); url.set("https://github.com/engage-media/engage-ads-sdk") }
}
