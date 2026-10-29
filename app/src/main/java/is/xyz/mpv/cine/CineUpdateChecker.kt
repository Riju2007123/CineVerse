package `is`.xyz.mpv.cine

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CineUpdateInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val releaseUrl: String,
    val isUpdateAvailable: Boolean
)

object CineUpdateChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/Riju2007123/CineVerse/releases/latest"
    const val CURRENT_VERSION_TAG = "v1.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): CineUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "CineVerseApp")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext CineUpdateInfo(
                    tagName = CURRENT_VERSION_TAG,
                    title = "",
                    notes = "",
                    apkUrl = "",
                    releaseUrl = "",
                    isUpdateAvailable = false
                )
            }

            val bodyString = response.body?.string() ?: return@withContext CineUpdateInfo(
                tagName = CURRENT_VERSION_TAG,
                title = "",
                notes = "",
                apkUrl = "",
                releaseUrl = "",
                isUpdateAvailable = false
            )

            val json = JSONObject(bodyString)
            val tagName = json.optString("tag_name", "").trim()
            val name = json.optString("name", "New Update Available").ifBlank { "New Update Available" }
            val body = json.optString("body", "Bug fixes and performance improvements.").ifBlank { "Bug fixes and performance improvements." }
            val htmlUrl = json.optString("html_url", "https://github.com/Riju2007123/CineVerse/releases/latest")

            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = downloadUrl
                        break
                    }
                }
            }

            val isNewer = isVersionNewer(tagName, CURRENT_VERSION_TAG)

            CineUpdateInfo(
                tagName = tagName,
                title = name,
                notes = body,
                apkUrl = apkUrl.ifBlank { htmlUrl },
                releaseUrl = htmlUrl,
                isUpdateAvailable = isNewer
            )
        } catch (e: Exception) {
            e.printStackTrace()
            CineUpdateInfo(
                tagName = CURRENT_VERSION_TAG,
                title = "",
                notes = "",
                apkUrl = "",
                releaseUrl = "",
                isUpdateAvailable = false
            )
        }
    }

    private fun isVersionNewer(latestTag: String, currentTag: String): Boolean {
        if (latestTag.isBlank()) return false
        val latestClean = latestTag.removePrefix("v").removePrefix("V").trim()
        val currentClean = currentTag.removePrefix("v").removePrefix("V").trim()

        if (latestClean == currentClean) return false

        val latestParts = latestClean.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    fun openUpdateUrl(context: Context, updateInfo: CineUpdateInfo) {
        val targetUrl = updateInfo.apkUrl.ifBlank { updateInfo.releaseUrl }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
