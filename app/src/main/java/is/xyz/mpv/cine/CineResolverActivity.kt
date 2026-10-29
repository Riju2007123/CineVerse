package `is`.xyz.mpv.cine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import `is`.xyz.mpv.MPVActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Turns a provider page into a direct stream URL and hands it to mpv.
 *
 * The provider's own player JavaScript runs in a WebView that is completely
 * hidden behind an opaque cover; the moment it requests an HLS/MP4 playlist we
 * stop it and play that URL in [MPVActivity]. Nothing from the embed page is
 * ever shown, and successful results are cached so the next tap goes straight
 * to the player.
 */
class CineResolverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CineResolver"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_TMDB = "tmdb"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_START_MS = "start_ms"

        private const val PER_SOURCE_TIMEOUT_MS = 12_000L

        fun intent(
            context: Context,
            title: CineTitle,
            season: Int?,
            episode: Int?,
            label: String,
            startMs: Int = 0,
        ): Intent = Intent(context, CineResolverActivity::class.java).apply {
            putExtra(EXTRA_IMDB, title.id)
            putExtra(EXTRA_TMDB, title.tmdbId ?: 0)
            putExtra(EXTRA_TYPE, title.type)
            putExtra(EXTRA_NAME, title.name)
            putExtra(EXTRA_SEASON, season ?: 0)
            putExtra(EXTRA_EPISODE, episode ?: 0)
            putExtra(EXTRA_LABEL, label)
            if (startMs > 0) putExtra(EXTRA_START_MS, startMs)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var sourceView: TextView

    private var sources: List<Pair<StreamSource, String>> = emptyList()
    private var index = 0
    private var launching = false
    private var pageUrl: String = ""
    private var label: String = ""
    private var cacheKey: String = ""
    private var imdbId: String = ""
    private var tmdbId: Int = 0
    private var typeName: String = "movie"
    private var seasonNumber: Int = 0
    private var episodeNumber: Int = 0
    private var startMs: Int = 0
    private var userAgent: String = HlsPlaylist.defaultUserAgent

    private val tried = HashSet<String>()

    private val advanceRunnable = Runnable { advance("timeout") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)

        val imdb = intent.getStringExtra(EXTRA_IMDB).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        val tmdb = intent.getIntExtra(EXTRA_TMDB, 0)
        val season = intent.getIntExtra(EXTRA_SEASON, 0).takeIf { it > 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, 0).takeIf { it > 0 }
        label = intent.getStringExtra(EXTRA_LABEL) ?: name
        cacheKey = playbackKey(imdb, season, episode)
        imdbId = imdb
        tmdbId = tmdb
        typeName = type
        seasonNumber = season ?: 0
        episodeNumber = episode ?: 0
        startMs = intent.getIntExtra(EXTRA_START_MS, 0)
        if (startMs <= 0) {
            val saved = CineLibrary.progress(this, cacheKey)
            if (saved != null && saved.isResumable) {
                startMs = (saved.positionMs - 2_000L).coerceAtLeast(0L).toInt()
            }
        }

        if (imdb.isBlank()) {
            Toast.makeText(this, "Missing title id", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // A recent resolution means playback can start with no waiting at all.
        CineStreamCache.get(this, cacheKey)?.let { cached ->
            if (cached.variant) {
                play(cached.url, cached.pageUrl, isVariant = true)
            } else {
                // Master playlist cached: point mpv at a single rendition.
                Thread {
                    val variant = HlsPlaylist.pickVariant(
                        cached.url,
                        cached.pageUrl,
                        userAgent,
                        CinePrefs.maxHeight(this),
                    )
                    handler.post {
                        play(variant ?: cached.url, cached.pageUrl, variant != null)
                    }
                }.start()
            }
            return
        }

        val title = CineTitle(
            id = imdb,
            type = type,
            name = name,
            poster = null,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            rating = null,
            releaseInfo = null,
            runtime = null,
            cast = emptyList(),
            director = emptyList(),
            tmdbId = tmdb.takeIf { it > 0 },
        )

        sources = CineSources.forTitle(title, season, episode)
        if (sources.isEmpty()) {
            Toast.makeText(this, "No source for this title", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()

        // A background warm-up is already running (details or hero screen):
        // consume its result instead of starting a second, competing resolver.
        if (CinePrefetchState.isActive(cacheKey)) {
            awaitPrefetch()
        } else {
            load(0)
        }
    }

    /** Waits for the prefetcher's stream, then falls back to a real resolve. */
    private fun awaitPrefetch() {
        statusView.text = "Starting the player…"
        Thread {
            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                val hit = CineStreamCache.get(this, cacheKey)
                if (hit != null) {
                    handler.post { play(hit.url, hit.pageUrl) }
                    return@Thread
                }
                if (!CinePrefetchState.isActive(cacheKey)) break
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            handler.post { if (!launching) load(0) }
        }.start()
    }

    // ----------------------------------------------------------------------- UI

    private fun dp(value: Number): Int =
        (value.toFloat() * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0B12")) }

        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Opaque cover — the provider page and its ads are never visible.
        val cover = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0B12"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        cover.addView(
            TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 17f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            },
        )

        cover.addView(
            ProgressBar(this).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#FF4D6D")
                )
            },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                topMargin = dp(22)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        statusView = TextView(this).apply {
            text = "Starting playback…"
            setTextColor(Color.parseColor("#B9B9C9"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        cover.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        sourceView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#7E7E92"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        cover.addView(
            sourceView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        cover.addView(
            Button(this).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(26)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        root.addView(
            cover,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(root)
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgent
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isMediaUrl(url)) handler.post { onMediaCandidate(url) }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) pageUrl = url
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    Log.d(TAG, "main frame error ${error?.description}")
                }
            }
        }

        // Nudge the provider's player for the first few seconds.
        repeat(7) { step ->
            handler.postDelayed({
                if (!launching) webView.evaluateJavascript(AUTO_PLAY_JS, null)
            }, 1_000L + step * 1_500L)
        }
    }

    private fun load(index: Int) {
        if (index >= sources.size) {
            failAll()
            return
        }
        this.index = index
        val (source, url) = sources[index]
        sourceView.text = "source · ${source.name}"
        handler.removeCallbacks(advanceRunnable)
        handler.postDelayed(advanceRunnable, PER_SOURCE_TIMEOUT_MS)
        pageUrl = url
        webView.loadUrl(url)
    }

    private fun advance(reason: String) {
        if (launching) return
        Log.d(TAG, "advance from ${sources.getOrNull(index)?.first?.name} ($reason)")
        handler.removeCallbacks(advanceRunnable)
        val next = index + 1
        if (next >= sources.size) failAll() else load(next)
    }

    private fun failAll() {
        if (launching) return
        launching = true
        Toast.makeText(
            this,
            "Could not find a stream for this title. Try again in a moment.",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    // ---------------------------------------------------------------- capturing

    private fun looksLikeMedia(url: String): Boolean = isMediaUrl(url)

    private fun onMediaCandidate(url: String) {
        if (launching) return
        if (!tried.add(url)) return
        Log.d(TAG, "candidate: $url")
        statusView.text = "Starting the player…"
        val referer = pageUrl
        val ua = userAgent
        Thread {
            val ok = probe(url, referer, ua)
            // Hand mpv one rendition instead of a master playlist full of probes.
            val variant = if (ok || url.contains(".m3u8")) {
                HlsPlaylist.pickVariant(url, referer, ua, CinePrefs.maxHeight(this))
            } else {
                null
            }
            handler.post {
                if (launching) return@post
                if (ok || url.contains(".m3u8")) {
                    play(variant ?: url, referer, variant != null)
                } else {
                    statusView.text = "Starting…"
                }
            }
        }.start()
    }

    /** Confirms the URL is a real media playlist/file before handing it to mpv. */
    private fun probe(url: String, referer: String, ua: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .build()
        probeClient.newCall(request).execute().use { response ->
            val type = response.header("Content-Type").orEmpty().lowercase()
            val head = runCatching { response.peekBody(512).string() }.getOrDefault("")
            val playlist = head.contains("#EXTM3U")
            val mediaType = type.contains("mpegurl") || type.contains("video/") ||
                type.contains("dash") || type.contains("octet-stream")
            val size = response.body?.contentLength() ?: -1L
            Log.d(TAG, "probe ${response.code} type=$type playlist=$playlist")
            response.isSuccessful && (playlist || mediaType || size > 1_000_000)
        }
    } catch (t: Throwable) {
        Log.d(TAG, "probe failed ${t.message}")
        false
    }

    private fun play(url: String, referer: String, isVariant: Boolean = false) {
        if (launching) return
        launching = true
        handler.removeCallbacks(advanceRunnable)
        Log.i(TAG, "playing $url (variant=$isVariant)")
        CineStreamCache.put(this, cacheKey, url, referer, variant = isVariant)

        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val intent = Intent(this, MPVActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(url)
            putExtra("title", label)
            putExtra("Referer", referer)
            putExtra("User-Agent", userAgent)
            putExtra("anime_track", cacheKey)
            // Start on the first available frame instead of filling the cache.
            putExtra("mpv_opts", "cache-pause=no\ndemuxer-readahead-secs=1")
            if (!cookie.isNullOrBlank()) putExtra("headers", "Cookie: $cookie")
            if (startMs > 0) putExtra("position", startMs)

            // Lets the player show a per-series "Skip OP" button.
            putExtra("intro_imdb", imdbId)
            putExtra("intro_season", seasonNumber)
            putExtra("intro_episode", episodeNumber)

            // Lets the player restart at the current quality without losing place.
            putExtra("stream_imdb", imdbId)
            putExtra("stream_tmdb", tmdbId)
            putExtra("stream_type", typeName)
            putExtra("stream_season", seasonNumber)
            putExtra("stream_episode", episodeNumber)
            putExtra("stream_label", label)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, "Could not start the player", Toast.LENGTH_LONG).show()
            }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private val AUTO_PLAY_JS = """
        (function () {
          try {
            /* Providers often ship the real player URL in data-src and only
               attach it on a click — attach every iframe ourselves. */
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
              if (t.length && texts.some(function (r) { return r.test(t); })) {
                nodes[i].click();
              }
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
