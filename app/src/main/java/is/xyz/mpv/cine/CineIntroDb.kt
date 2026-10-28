package `is`.xyz.mpv.cine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Opening-credits timings for series.
 *
 * TheIntroDB is a free, keyless community database keyed by IMDb id, season and
 * episode, so the "Skip OP" button knows exactly where each show's intro sits
 * instead of guessing a fixed offset.
 */
object CineIntroDb {
    private const val BASE = "https://api.theintrodb.org/v2/media"

    data class Marks(
        val introStartMs: Long,
        val introEndMs: Long,
        val creditsStartMs: Long,
        val creditsEndMs: Long,
    ) {
        val hasIntro: Boolean get() = introEndMs > introStartMs
        val hasCredits: Boolean get() = creditsStartMs > 0
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val memory = ConcurrentHashMap<String, Marks>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    private const val PREFS = "cine_intro_db"
    private const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    private fun key(imdb: String, season: Int, episode: Int) = "$imdb:$season:$episode"

    /** Blocking lookup; call from a background thread. */
    fun load(context: Context, imdb: String, season: Int, episode: Int): Marks? {
        if (imdb.isBlank() || season <= 0 || episode <= 0) return null
        val key = key(imdb, season, episode)
        memory[key]?.let { return it }
        if (misses.contains(key)) return null

        prefs(context).getString(key, null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
                if (System.currentTimeMillis() - json.optLong("at") < TTL_MS) {
                    json.optJSONObject("marks")?.let { m ->
                        val marks = Marks(
                            introStartMs = m.optLong("is"),
                            introEndMs = m.optLong("ie"),
                            creditsStartMs = m.optLong("cs"),
                            creditsEndMs = m.optLong("ce"),
                        )
                        memory[key] = marks
                        return marks
                    }
                }
            }
        }

        val url = "$BASE?imdb_id=$imdb&season=$season&episode=$episode"
        val body = try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Throwable) {
            null
        }

        val marks = body?.let { parse(it) }
        if (marks == null || !marks.hasIntro) {
            misses.add(key)
            return null
        }

        memory[key] = marks
        prefs(context).edit().putString(
            key,
            JSONObject().put("at", System.currentTimeMillis()).put(
                "marks",
                JSONObject()
                    .put("is", marks.introStartMs)
                    .put("ie", marks.introEndMs)
                    .put("cs", marks.creditsStartMs)
                    .put("ce", marks.creditsEndMs),
            ).toString(),
        ).apply()
        return marks
    }

    private fun parse(raw: String): Marks? = try {
        val json = JSONObject(raw)
        val intro = json.optJSONArray("intro")?.optJSONObject(0)
        val credits = json.optJSONArray("credits")?.optJSONObject(0)
        if (intro == null) {
            null
        } else {
            Marks(
                introStartMs = intro.optLong("start_ms"),
                introEndMs = intro.optLong("end_ms") ?: 0L,
                creditsStartMs = credits?.optLong("start_ms") ?: 0L,
                creditsEndMs = credits?.optLong("end_ms") ?: 0L,
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
