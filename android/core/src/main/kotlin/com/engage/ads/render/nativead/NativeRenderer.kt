package com.engage.ads.render.nativead

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import com.engage.ads.*
import com.engage.ads.network.HttpTransport
import com.engage.ads.render.CreativeRenderer
import com.engage.ads.render.video.ImaVideoRenderer
import com.engage.ads.render.mraid.isActuallyVisible
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class NativeRenderer(
    private val context: Context,
    private val transport: HttpTransport,
    private val timeoutMillis: Long,
    private val measurement: OpenMeasurementCoordinator = OpenMeasurementCoordinator.disabled(),
    private val friendlyObstructions: List<FriendlyObstruction> = emptyList(),
    private val diagnostics: DiagnosticListener = DiagnosticListener.NONE,
) : CreativeRenderer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var videoRenderer: ImaVideoRenderer? = null
    private val impressionSent = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private var boundView: NativeAdView? = null
    private var ownsBoundView = false
    private var boundClickTargets: Set<View> = emptySet()
    private var measurementSession: SafeOpenMeasurementSession? = null

    override fun display(creative: LoadedCreative, request: AdRequest, container: ViewGroup, callback: CreativeRenderer.Callback) {
        if (destroyed.get()) return
        val native = creative.native ?: return callback.failed(EngageError(EngageError.Code.RENDER, "native creative payload is missing"))
        val customView = container as? NativeAdView
        val adView = customView ?: defaultView(container.context).also {
            container.removeAllViews()
            container.addView(it, ViewGroup.LayoutParams(-1, -2))
        }
        boundView = adView
        ownsBoundView = customView == null
        scope.launch {
            try {
                withTimeout(timeoutMillis) {
                    bind(adView, native, request, callback)
                    if (native.assets.none { it.videoVast != null }) {
                        val candidateSession = measurement.createSession(
                            OpenMeasurementSessionContext(
                                creativeType = OpenMeasurementCreativeType.NATIVE,
                                format = request.format,
                                adView = adView,
                                verificationResources = native.verificationResources,
                            ),
                            friendlyObstructions,
                        )
                        if (destroyed.get()) candidateSession?.finish() else {
                            measurementSession = candidateSession
                            candidateSession?.loaded()
                        }
                    }
                    awaitVisible(adView)
                }
                if (destroyed.get()) return@launch
                measurementSession?.impression()
                if (destroyed.get()) return@launch
                callback.displayed()
                scope.launch(Dispatchers.IO) { fireImpression(native) }
            } catch (cancelled: CancellationException) {
                if (scope.isActive) callback.failed(EngageError(EngageError.Code.TIMEOUT, "native creative did not become ready and visible", cancelled))
            } catch (error: Exception) {
                measurementSession?.finish()
                measurementSession = null
                callback.failed(error)
            }
        }
    }

    private suspend fun bind(view: NativeAdView, creative: NativeCreative, request: AdRequest, callback: CreativeRenderer.Callback) {
        val requiredIds = request.native?.assets?.filter { it.required }?.map { it.id }.orEmpty()
        requiredIds.forEach { id -> if (view.assetViews[id] == null) throw EngageError(EngageError.Code.RENDER, "required native asset view $id is not registered") }
        if (creative.assets.none { view.assetViews.containsKey(it.id) }) throw EngageError(EngageError.Code.RENDER, "no native creative assets are bound")
        val deliveredIds = creative.assets.mapTo(mutableSetOf()) { it.id }
        view.assetViews.forEach { (id, assetView) -> assetView.visibility = if (id in deliveredIds) View.VISIBLE else View.GONE }
        creative.assets.forEach { asset ->
            val target = view.assetViews[asset.id] ?: return@forEach
            when {
                asset.title != null || asset.text != null -> {
                    val textView = target as? TextView ?: throw EngageError(EngageError.Code.RENDER, "native text asset ${asset.id} requires TextView")
                    textView.text = asset.title ?: asset.text
                }
                asset.imageUrl != null -> {
                    val imageView = target as? ImageView ?: throw EngageError(EngageError.Code.RENDER, "native image asset ${asset.id} requires ImageView")
                    imageView.setImageBitmap(downloadImage(asset.imageUrl))
                }
                asset.videoVast != null -> {
                    val videoContainer = target as? ViewGroup ?: throw EngageError(EngageError.Code.RENDER, "native video asset ${asset.id} requires ViewGroup")
                    val renderer = ImaVideoRenderer(context, AdFormat.NATIVE, null, timeoutMillis, friendlyObstructions, diagnostics).also { videoRenderer = it }
                    val ready = CompletableDeferred<Unit>()
                    renderer.display(
                        LoadedCreative(asset.videoVast, CreativeKind.VAST, "native-video", null, creative.hashCode().toString(), null, null),
                        request,
                        videoContainer,
                        object : CreativeRenderer.Callback {
                            override fun displayed() { ready.complete(Unit) }
                            override fun clicked(url: String?) { if (!destroyed.get()) callback.clicked(url) }
                            override fun adCompleted() { if (!destroyed.get()) callback.adCompleted() }
                            override fun breakCompleted() = Unit
                            override fun dismissed() = Unit
                            override fun rewardEarned() = Unit
                            override fun failed(error: Throwable) {
                                if (!ready.completeExceptionally(error) && !destroyed.get()) callback.failed(error)
                            }
                        },
                    )
                    ready.await()
                }
            }
        }
        val clickTargets = view.clickViews.ifEmpty { mutableSetOf(view) }
        boundClickTargets = clickTargets.toSet()
        clickTargets.forEach { target ->
            target.isClickable = true
            target.setOnClickListener {
                if (destroyed.get()) return@setOnClickListener
                scope.launch(Dispatchers.IO) {
                    creative.clickTrackers.distinct().forEach { transport.notify(it) }
                }
                creative.clickUrl?.let { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        // A click is still reported when no activity can handle the destination.
                    }
                }
                callback.clicked(creative.clickUrl)
            }
        }
    }

    private suspend fun fireImpression(creative: NativeCreative) {
        if (destroyed.get() || !impressionSent.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            (creative.impressionTrackers + creative.eventTrackers[1].orEmpty()).distinct().forEach { transport.notify(it) }
        }
    }

    private suspend fun downloadImage(url: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) throw EngageError(EngageError.Code.RENDER, "native image URL must use HTTP(S)")
        val connection = URL(url).openConnection() as HttpURLConnection
        activeConnections += connection
        connection.connectTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = connection.connectTimeout
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw EngageError(EngageError.Code.NETWORK, "native image returned HTTP $status")
            val declared = connection.contentLengthLong
            if (declared > ResourceLimits.MAX_NATIVE_IMAGE_BYTES) throw EngageError(EngageError.Code.RENDER, "native image exceeds the download limit")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(declared.coerceAtLeast(0L), ResourceLimits.MAX_NATIVE_IMAGE_BYTES.toLong()).toInt())
                val chunk = ByteArray(8_192)
                var total = 0
                while (true) {
                    ensureActive()
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > ResourceLimits.MAX_NATIVE_IMAGE_BYTES) throw EngageError(EngageError.Code.RENDER, "native image exceeds the download limit")
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            }
            decodeBoundedImage(bytes)
        } finally { activeConnections.remove(connection); connection.disconnect() }
    }

    private fun decodeBoundedImage(bytes: ByteArray): android.graphics.Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw EngageError(EngageError.Code.RENDER, "native image could not be decoded")
        }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong() > ResourceLimits.MAX_NATIVE_IMAGE_PIXELS) {
            sample = sample shl 1
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw EngageError(EngageError.Code.RENDER, "native image could not be decoded")
    }

    private suspend fun awaitVisible(view: View) {
        fun ready(): Boolean {
            val rect = Rect()
            return view.width > 0 && view.height > 0 && view.isActuallyVisible() && view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0
        }
        if (ready()) return
        suspendCancellableCoroutine { continuation ->
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (ready() && continuation.isActive) { view.viewTreeObserver.removeOnPreDrawListener(this); continuation.resume(Unit) }
                    return true
                }
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            continuation.invokeOnCancellation { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    private fun defaultView(context: Context): NativeAdView {
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val root = NativeAdView(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(padding, padding, padding, padding)
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply { textSize = 18f; setTextColor(android.graphics.Color.BLACK) }
        val image = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val description = TextView(context).apply { setTextColor(android.graphics.Color.DKGRAY) }
        val video = FrameLayout(context).apply { visibility = View.GONE }
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        column.addView(image, LinearLayout.LayoutParams(-1, (180 * density).toInt()))
        column.addView(description, LinearLayout.LayoutParams(-1, -2))
        column.addView(video, LinearLayout.LayoutParams(-1, (180 * density).toInt()))
        root.addView(column, FrameLayout.LayoutParams(-1, -2))
        root.registerAssetView(1, title); root.registerAssetView(2, image); root.registerAssetView(3, description); root.registerAssetView(4, video)
        root.registerClickView(root)
        return root
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        scope.cancel()
        activeConnections.toList().forEach { connection -> try { connection.disconnect() } catch (_: Exception) {} }
        activeConnections.clear()
        try { videoRenderer?.destroy() } catch (_: Exception) {} finally { videoRenderer = null }
        measurementSession?.finish()
        measurementSession = null
        boundClickTargets.forEach { it.setOnClickListener(null); it.isClickable = false }
        boundClickTargets = emptySet()
        boundView?.let { view ->
            if (ownsBoundView) (view.parent as? ViewGroup)?.removeView(view)
            if (ownsBoundView) view.clearBindings()
        }
        boundView = null
    }

}
