package `is`.xyz.mpv.cine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One offline download, persisted across app restarts. */
data class CineDownload(
    val id: String,
    val key: String,
    val titleId: String,
    val type: String,
    val titleName: String,
    val label: String,
    val poster: String?,
    val season: Int?,
    val episode: Int?,
    val streamUrl: String?,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
    val status: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val fileName: String?,
    val fileUri: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isActive: Boolean get() = status == STATUS_QUEUED || status == STATUS_DOWNLOADING
    val isComplete: Boolean get() = status == STATUS_COMPLETED && !fileUri.isNullOrBlank()
    val isFailed: Boolean get() = status == STATUS_FAILED || status == STATUS_CANCELED
    val isSeriesEpisode: Boolean get() = season != null && episode != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("key", key)
        put("titleId", titleId)
        put("type", type)
        put("titleName", titleName)
        put("label", label)
        put("poster", poster)
        put("season", season ?: 0)
        put("episode", episode ?: 0)
        put("streamUrl", streamUrl)
        put("referer", referer)
        put("userAgent", userAgent)
        put("cookie", cookie)
        put("status", status)
        put("progress", progress.toDouble())
        put("bytes", bytesDownloaded)
        put("total", totalBytes)
        put("fileName", fileName)
        put("fileUri", fileUri)
        put("error", error)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELED = "canceled"

        fun fromJson(o: JSONObject): CineDownload = CineDownload(
            id = o.optString("id"),
            key = o.optString("key"),
            titleId = o.optString("titleId"),
            type = o.optString("type", "movie"),
            titleName = o.optString("titleName"),
            label = o.optString("label"),
            poster = o.optString("poster").takeIf { it.isNotBlank() },
            season = o.optInt("season", 0).takeIf { it > 0 },
            episode = o.optInt("episode", 0).takeIf { it > 0 },
            streamUrl = o.optString("streamUrl").takeIf { it.isNotBlank() },
            referer = o.optString("referer").takeIf { it.isNotBlank() },
            userAgent = o.optString("userAgent").takeIf { it.isNotBlank() },
            cookie = o.optString("cookie").takeIf { it.isNotBlank() },
            status = o.optString("status", STATUS_QUEUED),
            progress = o.optDouble("progress", 0.0).toFloat(),
            bytesDownloaded = o.optLong("bytes"),
            totalBytes = o.optLong("total"),
            fileName = o.optString("fileName").takeIf { it.isNotBlank() },
            fileUri = o.optString("fileUri").takeIf { it.isNotBlank() },
            error = o.optString("error").takeIf { it.isNotBlank() },
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

/**
 * Download storage: SAF folder selection, task persistence and file helpers.
 *
 * Files are written into a "CineVerse" folder inside the tree the user picks.
 * The tree URI permission is persisted, so subsequent downloads never ask again.
 */
object CineDownloads {
    private const val PREFS = "cineverse"
    private const val KEY_TREE = "download_tree_uri"
    private const val KEY_TREE_NAME = "download_tree_name"
    private const val KEY_ITEMS = "download_items"
    private const val FOLDER = "CineVerse"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ folder

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun treeName(context: Context): String =
        prefs(context).getString(KEY_TREE_NAME, null).orEmpty()

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    fun setTreeUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Throwable) {
        }
        val name = DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.lastPathSegment
            ?: "Selected folder"
        prefs(context).edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_TREE_NAME, name)
            .apply()
    }

    fun clearTree(context: Context) {
        prefs(context).edit().remove(KEY_TREE).remove(KEY_TREE_NAME).apply()
    }

    /** Creates (or reuses) the CineVerse folder inside the selected tree. */
    private fun targetFolder(context: Context): DocumentFile? {
        val tree = treeUri(context) ?: return null
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        if (!root.canWrite()) return null
        return root.findFile(FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(FOLDER)
    }

    fun createFile(context: Context, baseName: String, extension: String): Pair<Uri, String>? {
        val folder = targetFolder(context) ?: return null
        val safeExt = extension.removePrefix(".").ifBlank { "mp4" }
        val mime = when (safeExt.lowercase()) {
            "ts" -> "video/mp2t"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m4v" -> "video/mp4"
            else -> "video/mp4"
        }
        val clean = sanitize(baseName).ifBlank { "CineVerse video" }
        var candidate = "$clean.$safeExt"
        var index = 1
        while (folder.findFile(candidate) != null && index < 100) {
            candidate = "$clean (${index}).$safeExt"
            index++
        }
        val file = folder.createFile(mime, candidate) ?: return null
        return file.uri to candidate
    }

    fun deleteFile(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.delete()
        } catch (_: Throwable) {
        }
    }

    fun fileSize(context: Context, uriString: String?): Long {
        if (uriString.isNullOrBlank()) return 0L
        return try {
            DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.length() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    // ------------------------------------------------------------------- tasks

    fun list(context: Context): List<CineDownload> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CineDownload.fromJson(it) }
            }.sortedByDescending { it.createdAt }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(context: Context, items: List<CineDownload>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun get(context: Context, id: String): CineDownload? =
        list(context).firstOrNull { it.id == id }

    fun findByKey(context: Context, key: String): CineDownload? =
        list(context).firstOrNull { it.key == key }

    fun enqueue(context: Context, task: CineDownload) {
        val current = list(context)
        current.filter { it.key == task.key && it.isActive }.forEach { existing ->
            CineDownloadService.cancel(context, existing.id)
        }
        val items = current.filterNot { it.key == task.key && it.isActive }.toMutableList()
        items.add(0, task)
        save(context, items)
    }

    fun update(context: Context, id: String, transform: (CineDownload) -> CineDownload) {
        val items = list(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = transform(items[index]).copy(updatedAt = System.currentTimeMillis())
        save(context, items)
    }

    fun setProgress(context: Context, id: String, bytes: Long, total: Long, progress: Float) {
        update(context, id) {
            if (it.status == CineDownload.STATUS_CANCELED) it
            else it.copy(
                status = CineDownload.STATUS_DOWNLOADING,
                bytesDownloaded = bytes,
                totalBytes = total,
                progress = progress.coerceIn(0f, 1f),
                error = null,
            )
        }
    }

    fun complete(context: Context, id: String, uri: Uri, fileName: String, bytes: Long) {
        update(context, id) {
            if (it.status == CineDownload.STATUS_CANCELED) it
            else it.copy(
                status = CineDownload.STATUS_COMPLETED,
                progress = 1f,
                bytesDownloaded = bytes,
                totalBytes = if (bytes > 0) bytes else it.totalBytes,
                fileUri = uri.toString(),
                fileName = fileName,
                error = null,
            )
        }
    }

    fun fail(context: Context, id: String, message: String) {
        update(context, id) {
            if (it.status == CineDownload.STATUS_CANCELED) it
            else it.copy(
                status = CineDownload.STATUS_FAILED,
                error = message,
            )
        }
    }

    fun cancel(context: Context, id: String) {
        update(context, id) {
            if (it.isComplete) it else it.copy(status = CineDownload.STATUS_CANCELED)
        }
    }

    fun remove(context: Context, id: String) {
        val task = get(context, id)
        deleteFile(context, task?.fileUri)
        save(context, list(context).filterNot { it.id == id })
    }

    fun removeAllCompleted(context: Context) {
        val items = list(context)
        items.filter { it.isComplete }.forEach { deleteFile(context, it.fileUri) }
        save(context, items.filterNot { it.isComplete })
    }

    fun newTask(
        title: CineTitle,
        key: String,
        label: String,
        season: Int?,
        episode: Int?,
        streamUrl: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
    ): CineDownload {
        val now = System.currentTimeMillis()
        return CineDownload(
            id = UUID.randomUUID().toString(),
            key = key,
            titleId = title.id,
            type = title.type,
            titleName = title.name,
            label = label,
            poster = title.poster,
            season = season,
            episode = episode,
            streamUrl = streamUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            status = CineDownload.STATUS_QUEUED,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            fileName = null,
            fileUri = null,
            error = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun sanitize(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(110)
    }

    fun titleStub(task: CineDownload): CineTitle = CineTitle(
        id = task.titleId,
        type = task.type,
        name = task.titleName,
        poster = task.poster,
        background = task.poster,
        logo = null,
        description = null,
        genres = emptyList(),
        rating = null,
        releaseInfo = null,
        runtime = null,
        cast = emptyList(),
        director = emptyList(),
    )

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }
}
