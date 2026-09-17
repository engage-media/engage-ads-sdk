#!/usr/bin/env python3
"""Compile fresh Android apps using only the staged Maven facade artifacts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='2.0.0-alpha.1')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    staging = root / 'android/build/staging-repository'
    if not staging.exists():
        raise SystemExit('Stage publications before checking consumers')
    if not all(c.isalnum() or c in '.-' for c in args.version):
        raise SystemExit('Invalid version')
    with tempfile.TemporaryDirectory(prefix='engage-consumer-') as temp:
        base = Path(temp)
        for facade in ('mobile', 'tv'):
            project = base / facade
            source = project / 'src/main/kotlin/example/consumer'
            source.mkdir(parents=True)
            (project / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven { url = uri(STAGING) }; google(); mavenCentral() } }
rootProject.name = "EngageConsumer"
'''.replace('STAGING', json.dumps(staging.as_uri())))
            (project / 'build.gradle.kts').write_text('''plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
}
android {
    namespace = "example.consumer"
    compileSdk = 35
    defaultConfig { applicationId = "example.consumer"; minSdk = 24; targetSdk = 35 }
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("String", "ADS_ENDPOINT", "\\\"https://example.invalid/openrtb\\\"") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.github.engage-media:engage-ads-FACADE:VERSION")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
'''.replace('FACADE', facade).replace('VERSION")', args.version + '")'))
            (project / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
            (project / 'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>')
            factory = 'EngageMobile' if facade == 'mobile' else 'EngageTv'
            category = 'MOBILE' if facade == 'mobile' else 'TV'
            (source / 'Integration.kt').write_text('''package example.consumer
import android.content.Context
import com.engage.ads.*
import com.engage.ads.FACADE.FACTORY
class Integration(context: Context, measurementBackend: OpenMeasurementBackend? = null) {
    val client: EngageClient = FACTORY.create(context, EngageConfiguration(
        endpoint = Endpoint.OpenRTB26(BuildConfig.ADS_ENDPOINT),
        app = AppMetadata("example.consumer", "Consumer"),
        deviceCategory = DeviceCategory.CATEGORY,
        measurementBackend = measurementBackend,
    ))
    fun close() = client.destroy()
}
'''.replace('FACADE', facade).replace('FACTORY', factory).replace('CATEGORY', category))
            subprocess.run([str(root / 'scripts/android-gradle.sh'), '-p', str(project), 'assembleDebug', '--refresh-dependencies', '--console=plain'],
                           check=True, env=os.environ.copy())
            print(f'PASS: fresh {facade} consumer resolved and compiled staged artifacts')


if __name__ == '__main__':
    main()
