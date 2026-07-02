package eu.kanade.tachiyomi.ui.webview

import android.content.Context
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
 * Lightweight, self-contained network ad-blocker for the in-app WebView.
 *
 * Loads domain blocklists (plain-domain / hosts / EasyList "||domain^" formats) and blocks matching
 * sub-resource requests in [android.webkit.WebViewClient.shouldInterceptRequest]. No native engine
 * or external library — just request-level blocking, which is what kills the popups/redirects on
 * manga source sites. Cosmetic element-hiding is intentionally out of scope.
 */
object WebViewAdblock {
    private val network: NetworkHelper by injectLazy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Peter Lowe's ad/tracking server list — the same list used as "plowe-0" in uBlock Origin.
    private val BLOCKLIST_URLS = listOf(
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext",
    )
    private const val CACHE_FILE = "webview_adblock_hosts.txt"
    private val CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var loading = false

    /** Loads (or refreshes) the blocklists in the background. Safe to call repeatedly. */
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
                logcat(LogPriority.ERROR, e) { "WebView adblock: failed to load blocklists" }
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
        val set = HashSet<String>(1 shl 14)
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val host = when {
                // EasyList network rule: ||domain^  (skip ones with wildcards/paths/options)
                line.startsWith("||") ->
                    line.removePrefix("||").substringBefore('^').substringBefore('/')
                        .takeIf { it.isNotEmpty() && '*' !in it && '.' in it }
                // hosts format: "0.0.0.0 domain" / "127.0.0.1 domain"
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") ->
                    line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#')
                // plain domain, one per line
                ' ' !in line && '.' in line && '/' !in line -> line
                else -> null
            }?.lowercase()?.removePrefix("www.")
            if (host != null && '.' in host && host != "localhost") {
                set.add(host)
            }
        }
        return set
    }

    /** Returns an empty response to block the request, or null to let it through. */
    fun shouldIntercept(request: WebResourceRequest): WebResourceResponse? {
        if (blocked.isEmpty()) return null
        if (request.isForMainFrame) return null // never block the page the user navigated to
        val host = request.url?.host?.lowercase() ?: return null
        return if (isBlocked(host)) blockResponse() else null
    }

    private fun isBlocked(host: String): Boolean {
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
