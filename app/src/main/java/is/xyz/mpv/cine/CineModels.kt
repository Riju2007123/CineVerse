package `is`.xyz.mpv.cine

import org.json.JSONArray
import org.json.JSONObject

/** A movie or series entry from the catalogue. */
data class CineTitle(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val genres: List<String>,
    val rating: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val cast: List<String>,
    val director: List<String>,
    val videos: List<CineEpisode> = emptyList(),
    val tmdbId: Int? = null,
) {
    val isSeries: Boolean get() = type == "series"

    val year: String
        get() = releaseInfo?.take(4)?.takeIf { it.any { ch -> ch.isDigit() } } ?: ""

    val metaLine: String
        get() = listOfNotNull(
            year.takeIf { it.isNotBlank() },
            runtime,
            rating?.let { "★ $it" },
        ).joinToString("  ·  ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("name", name)
        put("poster", poster)
        put("background", background)
        put("logo", logo)
        put("description", description)
        put("genres", JSONArray(genres))
        put("rating", rating)
        put("releaseInfo", releaseInfo)
        put("runtime", runtime)
        put("cast", JSONArray(cast))
        put("director", JSONArray(director))
        put("tmdb", tmdbId ?: 0)
    }

    companion object {
        fun fromJson(obj: JSONObject): CineTitle = CineTitle(
            id = obj.optString("id"),
            type = obj.optString("type", "movie"),
            name = obj.optString("name"),
            poster = obj.optString("poster").takeIf { it.isNotBlank() },
            background = obj.optString("background").takeIf { it.isNotBlank() },
            logo = obj.optString("logo").takeIf { it.isNotBlank() },
            description = obj.optString("description").takeIf { it.isNotBlank() },
            genres = obj.optJSONArray("genres").toStringList(),
            rating = obj.optString("rating").takeIf { it.isNotBlank() },
            releaseInfo = obj.optString("releaseInfo").takeIf { it.isNotBlank() },
            runtime = obj.optString("runtime").takeIf { it.isNotBlank() },
            cast = obj.optJSONArray("cast").toStringList(),
            director = obj.optJSONArray("director").toStringList(),
            tmdbId = obj.optInt("tmdb", 0).takeIf { it > 0 },
        )
    }
}

data class CineEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String?,
    val thumbnail: String?,
    val released: String?,
) {
    val label: String get() = "S%02d · E%02d".format(season, episode)
    val year: String get() = released?.take(4) ?: ""
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val value = optString(i)
        if (value.isNotBlank()) out += value
    }
    return out
}

/** Identifies one playable unit: a movie, or one episode of a series. */
fun playbackKey(titleId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "$titleId:$season:$episode" else titleId

/** A pending custom-link playback (handed to mpv instead of the embed player). */
data class PlayRequest(val title: CineTitle, val key: String, val label: String)

