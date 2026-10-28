package `is`.xyz.mpv

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object WebUrlResolver {
    private const val TAG = "WebUrlResolver"

    fun isYoutubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/") || lower.contains("youtube-nocookie.com/")
    }

    data class ResolvedStream(val url: String, val title: String? = null)

    fun resolve(urlStr: String): ResolvedStream? {
        return try {
            if (!isYoutubeUrl(urlStr)) {
                return null
            }

            val youtubeVideoId = extractYoutubeId(urlStr)
            if (youtubeVideoId != null) {
                val invidiousResult = resolveInvidious(youtubeVideoId)
                if (invidiousResult != null) return invidiousResult

                val pipedResult = resolvePiped(youtubeVideoId)
                if (pipedResult != null) return pipedResult
            }

            val cobaltResult = resolveCobalt(urlStr)
            if (cobaltResult != null) return cobaltResult

            null
        } catch (e: Throwable) {
            Log.e(TAG, "resolve error: $e")
            null
        }
    }

    private fun extractYoutubeId(url: String): String? {
        return try {
            val pattern = "(?<=watch\\?v=|/videos/|embed/|youtu.be/|/v/|/e/|watch\\?node=|shorts/)[^#&?]*"
            val compiledPattern = Pattern.compile(pattern)
            val matcher = compiledPattern.matcher(url)
            if (matcher.find()) {
                val id = matcher.group()
                if (id.length == 11) id else null
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun resolveInvidious(videoId: String): ResolvedStream? {
        val instances = listOf(
            "https://yt.chocolatemoo53.com",
            "https://invidious.nerdvpn.de",
            "https://inv.nadeko.net"
        )
        for (base in instances) {
            try {
                val conn = URL("$base/api/v1/videos/$videoId").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val title = if (json.has("title")) json.getString("title") else null

                    // 1. Prefer formatStreams (combined video + audio MP4)
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        for (i in formatStreams.length() - 1 downTo 0) {
                            val stream = formatStreams.getJSONObject(i)
                            val streamUrl = stream.optString("url", "")
                            if (streamUrl.isNotEmpty()) {
                                Log.v(TAG, "Invidious formatStream (combined): $streamUrl")
                                return ResolvedStream(streamUrl, title)
                            }
                        }
                    }

                    // 2. Fallback to HLS master playlist (.m3u8)
                    val hls = json.optString("hls", "")
                    if (hls.isNotEmpty()) {
                        Log.v(TAG, "Invidious resolved HLS: $hls")
                        return ResolvedStream(hls, title)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Invidious instance $base failed: $e")
            }
        }
        return null
    }

    private fun resolvePiped(videoId: String): ResolvedStream? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacydev.net",
            "https://pipedapi.mha.fi",
            "https://piped-api.garudalinux.org"
        )
        for (base in instances) {
            try {
                val conn = URL("$base/streams/$videoId").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val title = if (json.has("title")) json.getString("title") else null

                    // 1. Prefer combined video+audio streams
                    val streams = json.optJSONArray("videoStreams")
                    if (streams != null && streams.length() > 0) {
                        for (i in 0 until streams.length()) {
                            val stream = streams.getJSONObject(i)
                            val streamUrl = stream.optString("url", "")
                            val videoOnly = stream.optBoolean("videoOnly", true)
                            if (streamUrl.isNotEmpty() && !videoOnly) {
                                Log.v(TAG, "Piped combined stream: $streamUrl")
                                return ResolvedStream(streamUrl, title)
                            }
                        }
                    }

                    // 2. Fallback to HLS master playlist (.m3u8)
                    val hls = json.optString("hls", "")
                    if (hls.isNotEmpty()) {
                        Log.v(TAG, "Piped resolved HLS: $hls")
                        return ResolvedStream(hls, title)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Piped instance $base failed: $e")
            }
        }
        return null
    }

    private fun resolveCobalt(urlStr: String): ResolvedStream? {
        val cobaltInstances = listOf(
            "https://co.wuk.sh"
        )
        for (base in cobaltInstances) {
            try {
                val conn = URL("$base/").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("url", urlStr)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val status = json.optString("status", "")
                    if (status == "redirect" || status == "stream" || status == "tunnel") {
                        val streamUrl = json.optString("url", "")
                        if (streamUrl.isNotEmpty()) {
                            val filename = json.optString("filename", null)
                            Log.v(TAG, "Cobalt resolved stream: $streamUrl")
                            return ResolvedStream(streamUrl, filename)
                        }
                    } else if (status == "picker") {
                        val picker = json.optJSONArray("picker")
                        if (picker != null && picker.length() > 0) {
                            val item = picker.getJSONObject(0)
                            val streamUrl = item.optString("url", "")
                            if (streamUrl.isNotEmpty()) {
                                Log.v(TAG, "Cobalt picker resolved stream: $streamUrl")
                                return ResolvedStream(streamUrl, null)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Cobalt instance $base failed: $e")
            }
        }
        return null
    }
}