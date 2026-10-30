package `is`.xyz.mpv.cine

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Playback and UI preferences for CineVerse. */
object CinePrefs {
    private const val PREFS = "cineverse"
    private const val KEY_QUALITY = "quality"
    private const val KEY_DISMISSED_UPDATE = "dismissed_update"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_AUTOPLAY_NEXT = "autoplay_next"
    private const val KEY_AUTOPLAY_PREVIEWS = "autoplay_previews"
    private const val KEY_SKIP_INTRO = "skip_intro"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_DOWNLOAD_QUALITY = "download_quality"
    private const val KEY_USER_NAME = "user_name"

    const val QUALITY_AUTO = "auto"
    val QUALITY_OPTIONS = listOf(QUALITY_AUTO, "1080", "720", "480", "360")

    val ACCENT_OPTIONS = listOf(
        Color(0xFFD92B55), // Coral Crimson
        Color(0xFF8A5CF5), // Deep Violet
        Color(0xFF00B894), // Emerald Teal
        Color(0xFFF59E0B)  // Amber Gold
    )

    fun quality(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUALITY, QUALITY_AUTO) ?: QUALITY_AUTO
        return raw.takeIf { it in QUALITY_OPTIONS } ?: QUALITY_AUTO
    }

    fun setQuality(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUALITY, value).apply()
    }

    fun maxHeight(context: Context): Int = when (quality(context)) {
        "360" -> 360
        "480" -> 480
        "720" -> 720
        else -> 1080
    }

    fun qualityLabel(value: String): String = when (value) {
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        else -> "Auto (up to 1080p)"
    }

    fun qualityNames(): Array<String> = QUALITY_OPTIONS.map { qualityLabel(it) }.toTypedArray()

    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "dark") ?: "dark"

    fun setTheme(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply()
    }

    fun accentIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCENT, 0).coerceIn(0, ACCENT_OPTIONS.lastIndex)

    fun setAccentIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCENT, index).apply()
    }

    fun accentColor(context: Context): Color =
        ACCENT_OPTIONS[accentIndex(context)]

    fun reduceMotion(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REDUCE_MOTION, value).apply()
    }

    fun autoplayNext(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOPLAY_NEXT, true)

    fun setAutoplayNext(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOPLAY_NEXT, value).apply()
    }

    fun autoplayPreviews(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOPLAY_PREVIEWS, false)

    fun setAutoplayPreviews(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOPLAY_PREVIEWS, value).apply()
    }

    fun skipIntro(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_INTRO, true)

    fun setSkipIntro(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SKIP_INTRO, value).apply()
    }

    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun downloadQuality(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD_QUALITY, "Standard") ?: "Standard"

    fun setDownloadQuality(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOWNLOAD_QUALITY, value).apply()
    }

    fun userName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_NAME, "Jack") ?: "Jack"

    fun setUserName(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_USER_NAME, value).apply()
    }

    fun dismissedUpdate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_UPDATE, null).orEmpty()

    fun dismissUpdate(context: Context, tag: String) {
        if (tag.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED_UPDATE, tag).apply()
    }
}
