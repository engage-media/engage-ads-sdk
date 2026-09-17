package com.engage.ads

import android.view.ViewGroup
import com.engage.ads.render.ContentController
import com.engage.ads.render.nativead.NativeAdView

/** Format-specific facade over one independently owned ad lifecycle. */
class BannerAd internal constructor(private val delegate: EngageAd) {
    val request: AdRequest get() = delegate.request
    val state: AdState get() = delegate.state
    fun load() = delegate.load()
    fun display(container: ViewGroup, friendlyObstructions: List<FriendlyObstruction> = emptyList()) = delegate.display(container, friendlyObstructions = friendlyObstructions)
    fun destroy() = delegate.destroy()
}

class InterstitialAd internal constructor(private val delegate: EngageAd) {
    val request: AdRequest get() = delegate.request
    val state: AdState get() = delegate.state
    fun load() = delegate.load()
    fun display(container: ViewGroup, friendlyObstructions: List<FriendlyObstruction> = emptyList()) = delegate.display(container, friendlyObstructions = friendlyObstructions)
    fun destroy() = delegate.destroy()
}

class RewardedAd internal constructor(private val delegate: EngageAd) {
    val request: AdRequest get() = delegate.request
    val state: AdState get() = delegate.state
    fun load() = delegate.load()
    fun display(container: ViewGroup, friendlyObstructions: List<FriendlyObstruction> = emptyList()) = delegate.display(container, friendlyObstructions = friendlyObstructions)
    fun destroy() = delegate.destroy()
}

class NativeAd internal constructor(private val delegate: EngageAd) {
    val request: AdRequest get() = delegate.request
    val state: AdState get() = delegate.state
    fun load() = delegate.load()
    fun display(container: ViewGroup, friendlyObstructions: List<FriendlyObstruction> = emptyList()) = delegate.display(container, friendlyObstructions = friendlyObstructions)
    fun bind(view: NativeAdView, friendlyObstructions: List<FriendlyObstruction> = emptyList()) = delegate.display(view, friendlyObstructions = friendlyObstructions)
    fun destroy() = delegate.destroy()
}

class InStreamAd internal constructor(private val delegate: EngageAd) {
    val request: AdRequest get() = delegate.request
    val state: AdState get() = delegate.state
    fun load() = delegate.load()
    fun display(
        container: ViewGroup,
        contentController: ContentController? = null,
        friendlyObstructions: List<FriendlyObstruction> = emptyList(),
    ) = delegate.display(container, contentController, friendlyObstructions)
    fun destroy() = delegate.destroy()
}
