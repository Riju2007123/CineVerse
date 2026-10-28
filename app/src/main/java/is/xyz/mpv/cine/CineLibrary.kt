package `is`.xyz.mpv.cine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CineProgress(val positionMs: Long, val durationMs: Long, val at: Long) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val isResumable: Boolean
        get() = durationMs > 0 && positionMs > 5_000 && positionMs < durationMs * 0.94
}

data class HistoryEntry(
    val key: String,
    val titleId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val subtitle: String,
    val at: Long,
)

/** Watchlist, watch history, per-episode stream links and resume positions. */
object CineLibrary {
    private const val PREFS = "cineverse"
    private const val KEY_WATCHLIST = "watchlist_titles"
    private const val KEY_HISTORY = "history"
    private const val KEY_LINKS = "links"
    private const val KEY_SEARCHES = "searches"
    private const val PROGRESS_PREFS = "anime_progress"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- watchlist

    fun watchlist(context: Context): List<CineTitle> {
        val raw = prefs(context).getString(KEY_WATCHLIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CineTitle.fromJson(it) }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isSaved(context: Context, id: String): Boolean =
        watchlist(context).any { it.id == id }

    fun toggleWatchlist(context: Context, title: CineTitle): List<CineTitle> {
        val current = watchlist(context).toMutableList()
        val existing = current.indexOfFirst { it.id == title.id }
        if (existing >= 0) current.removeAt(existing) else current.add(0, title)
        val arr = JSONArray()
        current.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_WATCHLIST, arr.toString()).apply()
        return current
    }

    // ------------------------------------------------------------------ history

    fun history(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    key = o.optString("key"),
                    titleId = o.optString("titleId"),
                    type = o.optString("type", "movie"),
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    subtitle = o.optString("subtitle"),
                    at = o.optLong("at"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun recordPlay(context: Context, title: CineTitle, key: String, subtitle: String) {
        val entries = history(context).filterNot { it.key == key }.toMutableList()
        entries.add(
            0,
            HistoryEntry(
                key = key,
                titleId = title.id,
                type = title.type,
                name = title.name,
                poster = title.poster,
                subtitle = subtitle,
                at = System.currentTimeMillis(),
            ),
        )
        val arr = JSONArray()
        entries.take(40).forEach {
            arr.put(
                JSONObject()
                    .put("key", it.key)
                    .put("titleId", it.titleId)
                    .put("type", it.type)
                    .put("name", it.name)
                    .put("poster", it.poster)
                    .put("subtitle", it.subtitle)
                    .put("at", it.at)
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun isWatched(context: Context, key: String): Boolean {
        val progress = progress(context, key) ?: return false
        return progress.durationMs > 0 && progress.positionMs >= progress.durationMs * 0.9
    }

    // ------------------------------------------------------------------- stream

    fun linkFor(context: Context, key: String): String? {
        val raw = prefs(context).getString(KEY_LINKS, null) ?: return null
        return try {
            JSONObject(raw).optString(key).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun saveLink(context: Context, key: String, url: String) {
        val current = try {
            prefs(context).getString(KEY_LINKS, null)?.let { JSONObject(it) } ?: JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
        current.put(key, url)
        prefs(context).edit().putString(KEY_LINKS, current.toString()).apply()
    }

    fun seenLinks(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_LINKS, null) ?: return emptySet()
        return try {
            JSONObject(raw).keys().asSequence().toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    // ----------------------------------------------------------------- progress

    fun progress(context: Context, key: String): CineProgress? {
        val raw = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            val json = JSONObject(raw)
            CineProgress(
                positionMs = json.optLong("pos"),
                durationMs = json.optLong("dur"),
                at = json.optLong("at"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------- recent searches

    fun recentSearches(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SEARCHES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addSearch(context: Context, term: String): List<String> {
        val cleaned = term.trim()
        if (cleaned.isEmpty()) return recentSearches(context)
        val current = recentSearches(context)
            .filterNot { it.equals(cleaned, ignoreCase = true) }
            .toMutableList()
        current.add(0, cleaned)
        val arr = JSONArray()
        current.take(12).forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_SEARCHES, arr.toString()).apply()
        return current.take(12)
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
