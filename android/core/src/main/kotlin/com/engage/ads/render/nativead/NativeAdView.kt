package com.engage.ads.render.nativead

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class NativeAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    internal val assetViews = mutableMapOf<Int, View>()
    internal val clickViews = mutableSetOf<View>()

    fun registerAssetView(assetId: Int, view: View) {
        require(assetId >= 0)
        require(isDescendant(view)) { "asset view must be a descendant of NativeAdView" }
        assetViews[assetId] = view
    }

    fun registerClickView(view: View) {
        require(isDescendant(view)) { "click view must be a descendant of NativeAdView" }
        clickViews += view
    }

    fun clearBindings() {
        assetViews.clear()
        clickViews.clear()
    }

    private fun isDescendant(candidate: View): Boolean {
        if (candidate === this) return true
        var parent = candidate.parent
        while (parent is View) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
