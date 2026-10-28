package `is`.xyz.mpv.cine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/**
 * Warms up the stream URL while the user is still reading the details page.
 *
 * A 1dp, almost invisible WebView keeps the provider's player running in the
 * background; the first media request it makes is cached, so tapping Watch
 * opens mpv immediately instead of waiting.
 */
@Composable
fun PrefetchStream(
    title: CineTitle,
    season: Int?,
    episode: Int?,
) {
    val context = LocalContext.current
    val key = playbackKey(title.id, season, episode)

    // Already resolved (or resolved since this screen opened): nothing to do.
    val cached = remember(key) { CineStreamCache.get(context, key) }
    if (cached != null) return

    var webView by remember(key) { mutableStateOf<WebView?>(null) }

    // key() forces a brand new WebView when the target title changes.
    key(key) {
        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    alpha = 0.01f
                    webView = this
                    configureForPrefetch(ctx, key)
                    val source = CineSources.forTitle(title, season, episode).firstOrNull()
                    if (source == null) {
                        destroy()
                    } else {
                        CinePrefetchState.begin(key)
                        Log.d("CinePrefetch", "warming ${source.first.name} for $key")
                        loadUrl(source.second)
                    }
                }
            },
        )
    }

    DisposableEffect(key) {
        onDispose {
            val view = webView
            webView = null
            CinePrefetchState.end(key)
            try {
                view?.stopLoading()
                view?.loadUrl("about:blank")
                view?.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}

private val AD_HOSTS = listOf(
    "doubleclick", "googlesyndication", "googleadservices", "adservice",
    "adsystem", "taboola", "outbrain", "popads", "popcash", "propellerads",
    "onclicka", "histats", "amung", "tagivi", "llvpn", "exoclick", "juicyads",
    "trafficjunky", "bidvertiser", "revcontent", "mgid", "analytics",
    "cloudflareinsights", "googletagmanager", "google-analytics",
)

internal fun isMediaUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (AD_HOSTS.any { lower.contains(it) }) return false
    if (lower.contains(".vtt") || lower.contains(".jpg") || lower.contains(".png")) return false
    if (lower.contains("/seg-") || lower.contains("segment")) return false
    return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4")
}

private const val AUTO_PLAY_JS = """
(function () {
  try {
    document.querySelectorAll('iframe').forEach(function (f) {
      var ds = f.getAttribute('data-src') || f.getAttribute('data-lazy-src');
      if (ds && (!f.src || f.src === 'about:blank')) f.src = ds;
    });
    document.querySelectorAll('video').forEach(function (v) {
      if (v.paused) { v.muted = true; v.play().catch(function(){}); }
    });
    if (window.jwplayer) { try { window.jwplayer().play(); } catch (e) {} }
    var texts = [/^\s*(play|play now|watch now|start)\s*$/i];
    var nodes = document.querySelectorAll('button, a, div[role=button], .play, #play, [class*=play-btn]');
    for (var i = 0; i < nodes.length; i++) {
      var t = (nodes[i].innerText || '').trim();
      if (t.length && texts.some(function (r) { return r.test(t); })) nodes[i].click();
    }
  } catch (e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForPrefetch(
    context: Context,
    key: String,
) {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        // Same UA the resolver hands to mpv, so provider sessions stay valid.
        userAgentString = HlsPlaylist.defaultUserAgent
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    // Cached once: WebView methods must not be touched off the main thread.
    val userAgent = HlsPlaylist.defaultUserAgent

    var pageUrl = ""
    var captured = false

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (!captured && isMediaUrl(url)) {
                captured = true
                Log.d("CinePrefetch", "ready $key -> $url")
                CineStreamCache.put(context, key, url, pageUrl)
                CinePrefetchState.end(key)
                view?.post {
                    try {
                        view.stopLoading()
                    } catch (_: Throwable) {
                    }
                }
                // Master playlists make mpv probe every rendition; store the
                // single best variant instead so playback starts immediately.
                val referer = pageUrl
                Thread {
                    val variant = HlsPlaylist.pickVariant(
                        url,
                        referer,
                        userAgent,
                        CinePrefs.maxHeight(context),
                    )
                    if (variant != null && variant != url) {
                        Log.d("CinePrefetch", "variant $key -> $variant")
                        CineStreamCache.put(context, key, variant, referer, variant = true)
                    }
                }.start()
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!url.isNullOrBlank()) pageUrl = url
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Pull the real player forward in providers that gate it behind a click.
            view?.evaluateJavascript(AUTO_PLAY_JS, null)
            view?.postDelayed({ view.evaluateJavascript(AUTO_PLAY_JS, null) }, 1_500)
            view?.postDelayed({ view.evaluateJavascript(AUTO_PLAY_JS, null) }, 4_000)
        }
    }
}
