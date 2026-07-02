package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import io.github.edsuns.adfilter.AdFilter
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Proof-of-concept WebView ad-blocking backed by Edsuns/AdblockAndroid (brave/ad-block engine).
 *
 * Initialized lazily on first WebView use so any native-library issue stays contained to the
 * WebView flow rather than crashing app startup. For now it is hard-wired to EasyList; a proper
 * settings screen / subscription management is the follow-up step if this PoC holds up.
 */
object WebViewAdblock {
    private const val EASYLIST_NAME = "EasyList"
    private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"

    @Volatile
    private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val filter = AdFilter.create(context.applicationContext)
                filter.viewModel.isEnabled.postValue(true)
                if (!filter.hasInstallation) {
                    val easylist = filter.viewModel.addFilter(EASYLIST_NAME, EASYLIST_URL)
                    filter.viewModel.download(easylist.id)
                }
                initialized = true
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to initialize WebView adblock" }
            }
        }
    }

    fun shouldIntercept(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!initialized) return null
        return runCatching { AdFilter.get().shouldIntercept(webView, request).resourceResponse }.getOrNull()
    }

    fun setupWebView(webView: WebView) {
        if (!initialized) return
        runCatching { AdFilter.get().setupWebView(webView) }
    }

    fun performScript(webView: WebView?, url: String?) {
        if (!initialized) return
        runCatching { AdFilter.get().performScript(webView, url) }
    }
}
