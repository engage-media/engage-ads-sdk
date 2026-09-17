#!/usr/bin/env python3
"""Build equivalent shrunk release consumers and report SDK/dependency APK growth.

The SDK consumer exposes every format through a runtime-selected request, so R8
cannot turn this into an initialization-only measurement. No broad keep rules.
APK bytes are not Play download size; use bundletool/device splits for that gate.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
import zipfile

BASE_ACTIVITY = '''package example.footprint
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Engage footprint baseline" })
    }
}
'''

SDK_ACTIVITY = '''package example.footprint
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.lang.ref.WeakReference
import com.engage.ads.*
import com.engage.ads.FACADE.FACTORY

class MainActivity : Activity() {
    private var client: EngageClient? = null
    private var ad: EngageAd? = null
    private val main = Handler(Looper.getMainLooper())
    private var cyclesRemaining = 0
    private var cycle = 0
    private var loadButton: Button? = null
    private val releasedAds = mutableListOf<WeakReference<EngageAd>>()
    private val releasedViews = mutableListOf<WeakReference<View>>()
    private fun newClient(): EngageClient = FACTORY.create(this, EngageConfiguration(
        if (intent.getBooleanExtra("direct_vast", false))
            Endpoint.VastTag(intent.getStringExtra("endpoint") ?: "http://10.0.2.2:8787/vast/linear.xml")
        else Endpoint.OpenRTB26(intent.getStringExtra("endpoint") ?: "http://10.0.2.2:8787/openrtb/2.6/auto"),
        AppMetadata(packageName, "Footprint probe"), deviceCategory = DeviceCategory.CATEGORY,
        diagnostics = DiagnosticListener { Log.i("EngageFootprint", "diagnostic=" + it.code) }))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cpuStart = Debug.threadCpuTimeNanos()
        val start = SystemClock.elapsedRealtimeNanos()
        client = newClient()
        Log.i("EngageFootprint", "cold_init_us=" + (SystemClock.elapsedRealtimeNanos()-start)/1000 +
            " cpu_us=" + (Debug.threadCpuTimeNanos()-cpuStart)/1000)
        if (intent.getBooleanExtra("init_stress", false)) {
            val measurements = mutableListOf<Long>()
            repeat(50) {
                val before = SystemClock.elapsedRealtimeNanos()
                newClient().destroy()
                measurements += (SystemClock.elapsedRealtimeNanos()-before)/1000
            }
            measurements.sort()
            Log.i("EngageFootprint", "warm_init_destroy_p50_us="+measurements[25]+" p95_us="+measurements[47])
        }
        val container = FrameLayout(this)
        cyclesRemaining = intent.getIntExtra("load_cycles", 0).coerceIn(0, 50)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setPadding(0, (64 * resources.displayMetrics.density).toInt(), 0, 0)
        loadButton = Button(this).apply {
            text = "Load ad"
            setOnClickListener {
                ad?.destroy()
                val format = if ("FACADE" == "tv") AdFormat.INSTREAM else
                    AdFormat.values().firstOrNull { it.name == intent.getStringExtra("format") } ?: AdFormat.BANNER
                val request = AdRequest("footprint", format,
                    size = AdSize(640, 360),
                    video = if (format in setOf(AdFormat.INSTREAM, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) VideoConstraints() else null,
                    native = if (format == AdFormat.NATIVE) NativeRequest(supportsVideo = true) else null)
                ad = client!!.createAd(request) { event ->
                    val label = when (event) {
                        AdEvent.Loaded -> "Loaded"
                        AdEvent.Displayed -> "Displayed"
                        AdEvent.AdCompleted -> "AdCompleted"
                        AdEvent.BreakCompleted -> "BreakCompleted"
                        AdEvent.RewardEarned -> "RewardEarned"
                        AdEvent.Dismissed -> "Dismissed"
                        AdEvent.NoFill -> "NoFill"
                        is AdEvent.Error -> "Error:" + event.error.code
                        else -> "Other"
                    }
                    Log.i("EngageFootprint", "event=" + label)
                    if (event == AdEvent.Loaded) {
                        val delay = intent.getIntExtra("display_delay_ms", 0).coerceIn(0, 10000).toLong()
                        if (delay == 0L) ad?.display(container)
                        else main.postDelayed({ ad?.display(container) }, delay)
                    }
                    if (event == AdEvent.Displayed && intent.getBooleanExtra("terminate_renderer", false)) main.postDelayed({
                        if (Build.VERSION.SDK_INT >= 29) {
                            Log.i("EngageFootprint", "renderer_termination_requested=" + findWebView(container)?.webViewRenderProcess?.terminate())
                        }
                    }, 500)
                    if (event == AdEvent.Displayed && cyclesRemaining > 0) main.postDelayed({
                        ad?.let { releasedAds += WeakReference(it) }
                        container.getChildAt(0)?.let { releasedViews += WeakReference(it) }
                        ad?.destroy(); ad = null
                        cyclesRemaining -= 1; cycle += 1
                        Log.i("EngageFootprint", "cycle=" + cycle + " java_used=" +
                            (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))
                        if (cyclesRemaining > 0) loadButton?.performClick()
                        else {
                            client?.destroy(); client = null; loadButton?.isEnabled = false
                            Log.i("EngageFootprint", "stress_done=" + cycle)
                            checkReleasedObjects(3)
                        }
                    }, 500)
                }.also { it.load() }
            }
        }
        root.addView(loadButton)
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (cyclesRemaining > 0) main.postDelayed({ loadButton?.performClick() }, 500)
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun checkReleasedObjects(attempts: Int) {
        main.postDelayed({
            System.gc()
            if (attempts > 1) checkReleasedObjects(attempts - 1)
            else main.postDelayed({
                Log.i("EngageFootprint", "retained_ads=" + releasedAds.count { it.get() != null } +
                    " retained_views=" + releasedViews.count { it.get() != null })
            }, 500)
        }, 500)
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); ad?.destroy(); client?.destroy(); super.onDestroy() }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='dist/hardening/footprint')
    parser.add_argument('--version', default='2.0.0-alpha.1')
    parser.add_argument('--max-added-mib', type=float, help='Fail when a facade exceeds this compressed APK growth budget')
    parser.add_argument('--refresh-dependencies', action='store_true', help='Refresh mutable local staging before measuring')
    args = parser.parse_args()
    if not all(c.isalnum() or c in '.-' for c in args.version):
        parser.error('Invalid package version')
    root = Path(__file__).resolve().parents[1]
    output = (root / args.output).resolve()
    project = output / 'consumer'
    project.mkdir(parents=True, exist_ok=True)
    staging = root / 'android/build/staging-repository'
    settings = '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven { url = uri(STAGING) }; google(); mavenCentral() } }
rootProject.name = "EngageFootprint"
include(":baseline", ":mobile", ":tv")
'''.replace('STAGING', json.dumps(staging.as_uri()))
    (project / 'settings.gradle.kts').write_text(settings)
    (project / 'build.gradle.kts').write_text('''plugins {
 id("com.android.application") version "8.7.3" apply false
 kotlin("android") version "2.0.21" apply false
}
''')
    (project / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3g\n')
    for name in ('baseline', 'mobile', 'tv'):
        module = project / name
        source = module / 'src/main/kotlin/example/footprint'
        source.mkdir(parents=True, exist_ok=True)
        (module / 'build.gradle.kts').write_text('''plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "example.footprint"
 compileSdk = 35
 defaultConfig { applicationId = "example.footprint.NAME"; minSdk = 24; targetSdk = 35 }
 compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildTypes { getByName("release") {
   isMinifyEnabled = true
   isShrinkResources = true
   signingConfig = signingConfigs.getByName("debug")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
 } }
}
dependencies {
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
 SDK_DEPENDENCY
}
'''.replace('NAME', name).replace('SDK_DEPENDENCY', '' if name == 'baseline' else
    f'implementation("com.github.engage-media:engage-ads-{name}:{args.version}")'))
        (module / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.INTERNET"/>
<application android:label="Footprint" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:usesCleartextTraffic="true">
<activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
</application></manifest>''')
        activity = BASE_ACTIVITY if name == 'baseline' else SDK_ACTIVITY.replace('FACADE', name).replace('FACTORY', 'EngageMobile' if name == 'mobile' else 'EngageTv').replace('CATEGORY', 'MOBILE' if name == 'mobile' else 'TV')
        (source / 'MainActivity.kt').write_text(activity)
    with (output / 'build.log').open('w') as log:
        command = [str(root / 'scripts/android-gradle.sh'), '-p', str(project), 'assembleRelease', '--console=plain']
        if args.refresh_dependencies:
            command.append('--refresh-dependencies')
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    result = {'method': 'R8 + resource-shrunk signed release APK, all runtime-selected formats reachable; identical toolchain/baseline', 'apps': {}}
    for name in ('baseline', 'mobile', 'tv'):
        apk = project / name / f'build/outputs/apk/release/{name}-release.apk'
        with zipfile.ZipFile(apk) as archive:
            entries = archive.infolist()
            result['apps'][name] = {
                'apk_bytes': apk.stat().st_size,
                'apk_path': str(apk.relative_to(root)),
                'dex_uncompressed_bytes': sum(e.file_size for e in entries if e.filename.endswith('.dex')),
                'native_uncompressed_bytes': sum(e.file_size for e in entries if e.filename.startswith('lib/')),
                'entries_uncompressed_bytes': sum(e.file_size for e in entries),
            }
    baseline = result['apps']['baseline']['apk_bytes']
    for name in ('mobile', 'tv'):
        result['apps'][name]['added_apk_bytes'] = result['apps'][name]['apk_bytes'] - baseline
    result['sdk_aars'] = {str(p.relative_to(staging)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in staging.rglob('*.aar')}
    (output / 'report.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if args.max_added_mib is not None:
        for name in ('mobile', 'tv'):
            if result['apps'][name]['added_apk_bytes'] > args.max_added_mib * 1024 * 1024:
                raise SystemExit(f'{name} exceeds {args.max_added_mib} MiB APK growth budget')


if __name__ == '__main__':
    main()
