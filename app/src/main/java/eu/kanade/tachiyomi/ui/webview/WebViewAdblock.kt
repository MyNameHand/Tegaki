package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-contained network ad-blocker for the in-app WebView.
 *
 * Loads a domain blocklist (hosts / plain-domain / EasyList "||domain^" formats) and:
 *  - blocks matching sub-resource requests in shouldInterceptRequest, and
 *  - is queried by the WebView client to block navigations/popups to blocked domains.
 *
 * No native engine or external library. Cosmetic element-hiding is intentionally out of scope;
 * popup/redirect suppression is handled in the WebView client itself.
 */
object WebViewAdblock {
    private val network: NetworkHelper by injectLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // HaGeZi "Pro++" — aggressive ads/tracking/popup/interstitial network list; covers the adult
    // ad networks (tsyndicate, trafficstars, exosrv, juicyads, adsco.re, …) behind the in-page
    // interstitials on manga aggregator sites, on top of the popunder networks.
    private val BLOCKLIST_URLS = listOf(
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.plus.txt",
    )
    // Bump the suffix whenever the list source changes so the cached copy is refreshed.
    private const val CACHE_FILE = "webview_adblock_hosts_v2.txt"
    private val CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var loading = false

    val isReady: Boolean get() = blocked.isNotEmpty()

    /** Loads (or refreshes) the blocklist in the background. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (blocked.isNotEmpty() || loading) return
        loading = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val cache = File(appContext.filesDir, CACHE_FILE)
                val fresh = cache.exists() &&
                    System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
                val text = if (fresh) {
                    cache.readText()
                } else {
                    val downloaded = download()
                    if (downloaded.isNotBlank()) runCatching { cache.writeText(downloaded) }
                    downloaded.ifBlank { if (cache.exists()) cache.readText() else "" }
                }
                blocked = parse(text)
                logcat { "WebView adblock: loaded ${blocked.size} blocked hosts" }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "WebView adblock: failed to load blocklist" }
            } finally {
                loading = false
            }
        }
    }

    private fun download(): String = buildString {
        for (url in BLOCKLIST_URLS) {
            runCatching {
                network.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        append(resp.body?.string().orEmpty())
                        append('\n')
                    }
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "WebView adblock: failed to fetch $url" } }
        }
    }

    private fun parse(text: String): Set<String> {
        val set = HashSet<String>(1 shl 19)
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val host = when {
                line.startsWith("||") ->
                    line.removePrefix("||").substringBefore('^').substringBefore('/')
                        .takeIf { it.isNotEmpty() && '*' !in it && '.' in it }
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") ->
                    line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#')
                ' ' !in line && '.' in line && '/' !in line -> line
                else -> null
            }?.lowercase()?.removePrefix("www.")
            if (host != null && '.' in host && host != "localhost") {
                set.add(host)
            }
        }
        return set
    }

    /** For shouldInterceptRequest: returns an empty response to block a sub-resource, else null. */
    fun shouldIntercept(request: WebResourceRequest): WebResourceResponse? {
        if (blocked.isEmpty()) return null
        if (request.isForMainFrame) return null
        val host = request.url?.host?.lowercase() ?: return null
        return if (isBlockedHost(host)) blockResponse() else null
    }

    /** For shouldOverrideUrlLoading / popup handling: is this URL's host on the blocklist? */
    fun isBlockedUrl(url: String?): Boolean {
        if (blocked.isEmpty() || url.isNullOrEmpty()) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return isBlockedHost(host)
    }

    private fun isBlockedHost(host: String): Boolean {
        var h = host
        while (h.contains('.')) {
            if (h in blocked) return true
            h = h.substringAfter('.')
        }
        return false
    }

    private fun blockResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
