package `is`.xyz.mpv.cine

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A catalogue row source, e.g. "Popular movies" -> movie/top. */
data class CatalogSpec(
    val title: String,
    val type: String,
    val catalog: String = "top",
    val genre: String? = null,
)

/**
 * Cinemeta (the Stremio metadata addon) is free, keyless and covers the whole
 * IMDb catalogue: charts, genres, new releases, latest episodes, search,
 * full details and every season/episode of a series. Supports paging via skip.
 */
object CinemetaApi {
    private const val BASE = "https://v3-cinemeta.strem.io"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val catalogCache = ConcurrentHashMap<String, List<CineTitle>>()
    private val detailCache = ConcurrentHashMap<String, CineTitle>()

    suspend fun catalog(
        type: String,
        genre: String? = null,
        search: String? = null,
        catalog: String = "top",
        skip: Int = 0,
    ): List<CineTitle> {
        val cacheKey = "$type|$catalog|${genre ?: ""}|${search ?: ""}|$skip"
        if (skip == 0) catalogCache[cacheKey]?.let { return it }

        val extras = buildList {
            when {
                search != null -> add("search=${Uri.encode(search)}")
                genre != null -> add("genre=${Uri.encode(genre)}")
            }
            if (skip > 0) add("skip=$skip")
        }
        val url = buildString {
            append(BASE).append("/catalog/").append(type).append('/').append(catalog)
            if (extras.isNotEmpty()) append('/').append(extras.joinToString("&"))
            append(".json")
        }

        val result = parseCatalog(getJson(url))
        if (result.isNotEmpty() && skip == 0) catalogCache[cacheKey] = result
        return result
    }

    /** Loads a row spec (used by the home screen). */
    suspend fun row(spec: CatalogSpec): List<CineTitle> =
        catalog(spec.type, spec.genre, catalog = spec.catalog)

    /** Searches movies and series at once and merges the results. */
    suspend fun search(query: String): List<CineTitle> = coroutineScope {
        val movies = async { runCatching { catalog("movie", search = query) }.getOrDefault(emptyList()) }
        val series = async { runCatching { catalog("series", search = query) }.getOrDefault(emptyList()) }
        val merged = LinkedHashMap<String, CineTitle>()
        (movies.await() + series.await()).forEach { merged.putIfAbsent(it.id, it) }
        val q = query.lowercase()
        merged.values.sortedWith(
            compareBy(
                { if (it.name.lowercase().startsWith(q)) 0 else 1 },
                { it.name.lowercase() },
            )
        )
    }

    suspend fun details(id: String, type: String): CineTitle {
        detailCache[id]?.let { return it }
        val json = getJson("$BASE/meta/$type/$id.json").optJSONObject("meta")
            ?: throw IOException("Title not found")
        val title = parseMeta(json)
        detailCache[id] = title
        return title
    }

    fun clearCache() {
        catalogCache.clear()
        detailCache.clear()
    }

    // ------------------------------------------------------------------ parsing

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CineVerse/1.0 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun parseCatalog(json: JSONObject): List<CineTitle> {
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = ArrayList<CineTitle>(metas.length())
        for (i in 0 until metas.length()) {
            val item = metas.optJSONObject(i) ?: continue
            val title = parseMeta(item)
            if (title.id.isNotBlank() && title.name.isNotBlank()) out += title
        }
        return out
    }

    private fun parseMeta(json: JSONObject): CineTitle {
        val id = json.optString("imdb_id").ifBlank { json.optString("id") }
        val type = json.optString("type", "movie")
        val genres = json.optJSONArray("genres").toStringList()
            .ifEmpty { json.optJSONArray("genre").toStringList() }

        val videos = json.optJSONArray("videos")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val season = v.optInt("season", 0)
                    if (season <= 0) continue
                    add(
                        CineEpisode(
                            id = v.optString("id"),
                            season = season,
                            episode = v.optInt("episode", 0),
                            name = v.optString("name").ifBlank { "Episode ${v.optInt("episode")}" },
                            overview = v.optString("overview").takeIf { it.isNotBlank() },
                            thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
                            released = v.optString("released").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } ?: emptyList()

        return CineTitle(
            id = id,
            type = type,
            name = json.optString("name"),
            poster = json.optString("poster").takeIf { it.startsWith("http") }
                ?: "https://images.metahub.space/poster/medium/$id/img",
            background = json.optString("background").takeIf { it.startsWith("http") }
                ?: "https://images.metahub.space/background/medium/$id/img",
            logo = json.optString("logo").takeIf { it.startsWith("http") },
            description = json.optString("description").takeIf { it.isNotBlank() },
            genres = genres,
            rating = json.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" },
            releaseInfo = json.optString("releaseInfo").ifBlank { json.optString("year") },
            runtime = json.optString("runtime").takeIf { it.isNotBlank() },
            cast = json.optJSONArray("cast").toStringList(),
            director = json.optJSONArray("director").toStringList(),
            videos = videos,
            tmdbId = json.optInt("moviedb_id", 0).takeIf { it > 0 },
        )
    }
}
