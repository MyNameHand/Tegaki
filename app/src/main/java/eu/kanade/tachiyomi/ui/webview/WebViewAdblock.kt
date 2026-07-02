package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import eu.kanade.domain.source.service.SourcePreferences
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
 * Loads user-configured domain blocklists (hosts / plain-domain / EasyList "||domain^" formats) and:
 *  - blocks matching sub-resource requests in shouldInterceptRequest, and
 *  - is queried by the WebView client to block navigations/popups to blocked domains.
 *
 * Controlled by preferences: [SourcePreferences.webViewAdblockEnabled] and
 * [SourcePreferences.webViewAdblockFilters] (one blocklist URL per line). No native engine or
 * external library; cosmetic element-hiding is intentionally out of scope.
 */
object WebViewAdblock {
    private val network: NetworkHelper by injectLazy()
    private val sourcePreferences: SourcePreferences by injectLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var loading = false

    // The filter-URL set that produced [blocked]; used to reload when the user edits the lists.
    @Volatile
    private var loadedKey: String = ""

    private fun enabled(): Boolean = sourcePreferences.webViewAdblockEnabled().get()

    private fun filterUrls(): List<String> = sourcePreferences.webViewAdblockFilterUrls().get()
        .map { it.trim() }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .sorted()

    /** Loads (or refreshes) the configured blocklists in the background. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (!enabled()) {
            blocked = emptySet()
            loadedKey = ""
            return
        }
        val urls = filterUrls()
        val key = urls.joinToString("\n")
        if (key.isEmpty()) {
            blocked = emptySet()
            loadedKey = ""
            return
        }
        if ((blocked.isNotEmpty() && loadedKey == key) || loading) return
        loading = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val cache = File(appContext.filesDir, "webview_adblock_${key.hashCode()}.txt")
                val fresh = cache.exists() &&
                    System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
                val text = if (fresh) {
                    cache.readText()
                } else {
                    val downloaded = download(urls)
                    if (downloaded.isNotBlank()) runCatching { cache.writeText(downloaded) }
                    downloaded.ifBlank { if (cache.exists()) cache.readText() else "" }
                }
                blocked = parse(text)
                loadedKey = key
                logcat { "WebView adblock: loaded ${blocked.size} blocked hosts from ${urls.size} list(s)" }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "WebView adblock: failed to load blocklists" }
            } finally {
                loading = false
            }
        }
    }

    private fun download(urls: List<String>): String = buildString {
        for (url in urls) {
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
        if (!enabled() || blocked.isEmpty()) return null
        if (request.isForMainFrame) return null
        val host = request.url?.host?.lowercase() ?: return null
        return if (isBlockedHost(host)) blockResponse() else null
    }

    /** For shouldOverrideUrlLoading / popup handling: is this URL's host on the blocklist? */
    fun isBlockedUrl(url: String?): Boolean {
        if (!enabled() || blocked.isEmpty() || url.isNullOrEmpty()) return false
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
