package `is`.xyz.mpv.cine

import android.content.Context
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Embed hosts that turn an IMDb / TMDB id into a playable page.
 *
 * CineVerse does not display these pages: [CineResolverActivity] loads the page
 * in an off-screen WebView only long enough to capture the direct HLS/MP4 URL
 * the provider's own player resolves, then plays that URL in mpv.
 */
data class StreamSource(
    val name: String,
    val movieUrl: (CineTitle) -> String?,
    val episodeUrl: (CineTitle, Int, Int) -> String?,
)

object CineSources {

    /** Ordered by how quickly and reliably they resolve to a bare HLS URL. */
    val ALL: List<StreamSource> = listOf(
        StreamSource(
            name = "VidFast",
            movieUrl = { title ->
                title.tmdbId?.let { "https://vidfast.pro/movie/$it?autoPlay=true" }
            },
            episodeUrl = { title, season, episode ->
                title.tmdbId?.let {
                    "https://vidfast.pro/tv/$it/$season/$episode?autoPlay=true"
                }
            },
        ),
        StreamSource(
            name = "VidLink",
            movieUrl = { "https://vidlink.pro/movie/${it.id}" },
            episodeUrl = { title, season, episode ->
                "https://vidlink.pro/tv/${title.id}/$season/$episode"
            },
        ),
        StreamSource(
            name = "2Embed",
            movieUrl = { "https://www.2embed.cc/embed/${it.id}" },
            episodeUrl = { title, season, episode ->
                "https://www.2embed.cc/embedtv/${title.id}&s=$season&e=$episode"
            },
        ),
    )

    /** Every provider that can serve this title, in preference order. */
    fun forTitle(title: CineTitle, season: Int?, episode: Int?): List<Pair<StreamSource, String>> {
        val out = ArrayList<Pair<StreamSource, String>>(ALL.size)
        ALL.forEach { source ->
            val url = if (season != null && episode != null) {
                source.episodeUrl(title, season, episode)
            } else {
                source.movieUrl(title)
            }
            if (!url.isNullOrBlank()) out += source to url
        }
        return out
    }
}

/**
 * Picks one rendition out of an HLS master playlist.
 *
 * mpv downloads probe segments for *every* variant listed in a master before it
 * starts playing, which costs several seconds on 4K titles. Handing it a single
 * 1080p variant instead skips that entirely.
 */
object HlsPlaylist {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private data class Variant(val url: String, val height: Int, val bandwidth: Long)

    private val resolutionRegex = Regex("RESOLUTION=\\d+x(\\d+)")
    private val bandwidthRegex = Regex("BANDWIDTH=(\\d+)")

    fun fetchPlaylist(url: String, referer: String, userAgent: String): String? = try {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()?.takeIf { it.length < 512 * 1024 }
        }
    } catch (_: Throwable) {
        null
    }

    /** Returns the best single-variant URL, or null when [url] is not a master. */
    fun pickVariant(
        masterUrl: String,
        referer: String,
        userAgent: String,
        maxHeight: Int = 1080,
    ): String? {
        if (!masterUrl.contains(".m3u8", ignoreCase = true)) return null
        val text = fetchPlaylist(masterUrl, referer, userAgent) ?: return null
        if (!text.contains("#EXT-X-STREAM-INF")) return null

        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = ArrayList<Variant>()
        for (i in lines.indices) {
            val header = lines[i]
            if (!header.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
            variants += Variant(
                url = absolute(masterUrl, uri),
                height = resolutionRegex.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                bandwidth = bandwidthRegex.find(header)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            )
        }
        if (variants.isEmpty()) return null

        // Best quality under the user's cap; fall back to the smallest.
        val target = variants.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?: variants.minByOrNull { it.height }
            ?: return null
        return target.url
    }

    private fun absolute(base: String, uri: String): String = try {
        java.net.URL(java.net.URL(base), uri).toString()
    } catch (_: Throwable) {
        uri
    }

    val defaultUserAgent: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}

/**
 * Tracks which keys are currently being warmed by [PrefetchStream].
 *
 * When the user taps Watch before the background warmer finished, the resolver
 * waits for that result instead of starting a second, competing WebView.
 */
object CinePrefetchState {
    private val active: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun begin(key: String) {
        active.add(key)
    }

    fun end(key: String) {
        active.remove(key)
    }

    fun isActive(key: String): Boolean = active.contains(key)
}

/** Resolved stream URLs, remembered briefly so replays start instantly. */
object CineStreamCache {
    private const val PREFS = "cine_stream_cache"
    private const val TTL_MS = 30 * 60 * 1000L

    data class Entry(
        val url: String,
        val pageUrl: String,
        val at: Long,
        /** True when [url] already points at a single rendition (no master). */
        val variant: Boolean = false,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Entry? {
        val raw = prefs(context).getString(key, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val entry = Entry(
                url = json.optString("url"),
                pageUrl = json.optString("page"),
                at = json.optLong("at"),
                variant = json.optBoolean("variant", false),
            )
            if (entry.url.isBlank()) return null
            if (System.currentTimeMillis() - entry.at > TTL_MS) null else entry
        } catch (_: Throwable) {
            null
        }
    }

    fun put(context: Context, key: String, url: String, pageUrl: String, variant: Boolean = false) {
        prefs(context).edit().putString(
            key,
            JSONObject()
                .put("url", url)
                .put("page", pageUrl)
                .put("at", System.currentTimeMillis())
                .put("variant", variant)
                .toString(),
        ).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
