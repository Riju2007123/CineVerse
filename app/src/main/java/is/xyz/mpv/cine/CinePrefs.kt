package `is`.xyz.mpv.cine

import android.content.Context

/** Playback preferences shared by the catalogue, the resolver and the player. */
object CinePrefs {
    private const val PREFS = "cineverse"
    private const val KEY_QUALITY = "quality"
    private const val KEY_DISMISSED_UPDATE = "dismissed_update"

    const val QUALITY_AUTO = "auto"

    /** Highest first; the value is the vertical resolution cap. */
    val QUALITY_OPTIONS = listOf(QUALITY_AUTO, "1080", "720", "480", "360")

    fun quality(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUALITY, QUALITY_AUTO)
            ?: QUALITY_AUTO
        return raw.takeIf { it in QUALITY_OPTIONS } ?: QUALITY_AUTO
    }

    fun setQuality(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUALITY, value)
            .apply()
    }

    /** Vertical resolution cap handed to the HLS playlist picker. */
    fun maxHeight(context: Context): Int = when (quality(context)) {
        "360" -> 360
        "480" -> 480
        "720" -> 720
        else -> 1080
    }

    fun qualityLabel(context: Context): String = qualityLabel(quality(context))

    fun qualityLabel(value: String): String = when (value) {
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        else -> "Auto (up to 1080p)"
    }

    fun qualityNames(): Array<String> = QUALITY_OPTIONS.map { qualityLabel(it) }.toTypedArray()

    // --------------------------------------------------------------- updates

    fun dismissedUpdate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_UPDATE, null)
            .orEmpty()

    fun dismissUpdate(context: Context, tag: String) {
        if (tag.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_UPDATE, tag)
            .apply()
    }

    fun clearDismissedUpdate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DISMISSED_UPDATE)
            .apply()
    }
}
