package `is`.xyz.mpv.cine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads a resolved provider stream into the user's chosen SAF folder.
 *
 * Direct MP4/WebM files are streamed straight to disk. HLS playlists are
 * downloaded segment-by-segment (including AES-128 keys and fMP4 init maps) and
 * written as a single .ts or .mp4 file that any external player can open.
 */
class CineDownloadService : Service() {

    companion object {
        private const val TAG = "CineDownload"
        private const val EXTRA_ID = "download_id"
        private const val CHANNEL_ID = "cine_downloads"
        private const val NOTIFICATION_ID = 4201

        private val canceled: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())

        fun start(context: Context, id: String) {
            val intent = Intent(context, CineDownloadService::class.java)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: String) {
            canceled.add(id)
            CineDownloads.cancel(context, id)
        }

        private fun isCanceled(context: Context, id: String): Boolean = canceled.contains(id)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = Collections.synchronizedMap(HashMap<String, Job>())
    private val lastProgressAt = Collections.synchronizedMap(HashMap<String, Long>())

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val task = CineDownloads.get(this, id)
        if (task == null || task.status == CineDownload.STATUS_CANCELED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(
            notificationId(id),
            buildNotification(task, "Starting…", 0f, indeterminate = true),
        )
        jobs[id] = scope.launch {
            try {
                runDownload(id)
            } finally {
                jobs.remove(id)
                if (jobs.isEmpty()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ download

    private fun runDownload(id: String) {
        val task = CineDownloads.get(this, id) ?: return
        if (isCanceled(this, id)) return

        CineDownloads.update(this, id) {
            it.copy(status = CineDownload.STATUS_DOWNLOADING, error = null)
        }

        try {
            val url = task.streamUrl?.takeIf { it.startsWith("http") }
                ?: throw IllegalStateException("No stream URL")
            val result = downloadToFile(task, url)
            CineDownloads.complete(this, id, result.uri, result.fileName, result.bytes)
            notifyFinished(task, result.fileName, success = true)
        } catch (t: Throwable) {
            val fresh = CineDownloads.get(this, id)
            CineDownloads.deleteFile(this, fresh?.fileUri)
            CineDownloads.update(this, id) { it.copy(fileUri = null, fileName = null) }
            if (isCanceled(this, id)) {
                notifyFinished(task, "", success = false, canceled = true)
            } else {
                CineDownloads.fail(this, id, t.message ?: "Download failed")
                notifyFinished(task, "", success = false)
            }
        }
    }

    private data class Result(val uri: Uri, val fileName: String, val bytes: Long)

    private fun downloadToFile(task: CineDownload, url: String): Result {
        val lower = url.lowercase()
        if (lower.contains(".mpd")) {
            throw IllegalStateException("DASH streams can't be downloaded")
        }
        if (lower.contains(".m3u8") || lower.contains("m3u8")) {
            return downloadHls(task, url)
        }

        val request = buildRequest(task, url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val finalUrl = response.request.url.toString()

            if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
                return downloadHls(task, finalUrl)
            }

            val ext = extensionFor(finalUrl, contentType)
            val (uri, fileName) = CineDownloads.createFile(
                this,
                fileBaseName(task),
                ext,
            ) ?: throw IllegalStateException("Choose a download folder first")
            CineDownloads.update(this, task.id) {
                it.copy(fileUri = uri.toString(), fileName = fileName)
            }

            val total = response.body?.contentLength() ?: -1L
            var written = 0L
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot open destination file")

            output.use { out ->
                val input = response.body?.byteStream()
                    ?: throw IllegalStateException("Empty response")
                input.use { stream ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCanceled(this, task.id)) throw CanceledException()
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val progress = if (total > 0) written.toFloat() / total else 0f
                        onProgress(task, written, total, progress)
                    }
                }
                out.flush()
            }
            return Result(uri, fileName, written)
        }
    }

    // --------------------------------------------------------------------- HLS

    private fun downloadHls(task: CineDownload, url: String): Result {
        val (playlist, playlistUrl) = loadPlaylist(task, url)
        val media = if (playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val variant = pickVariant(task, playlistUrl, playlist)
            loadPlaylist(task, variant)
        } else {
            playlist to playlistUrl
        }

        val text = media.first
        val baseUrl = media.second
        val hasMap = text.contains("#EXT-X-MAP", ignoreCase = true)
        val ext = if (hasMap) "mp4" else "ts"
        val (uri, fileName) = CineDownloads.createFile(
            this,
            fileBaseName(task),
            ext,
        ) ?: throw IllegalStateException("Choose a download folder first")
        CineDownloads.update(this, task.id) {
            it.copy(fileUri = uri.toString(), fileName = fileName)
        }

        val output = contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Cannot open destination file")

        var written = 0L
        output.use { out ->
            written = writeHls(task, text, baseUrl, out)
            out.flush()
        }
        return Result(uri, fileName, written)
    }

    private fun loadPlaylist(task: CineDownload, url: String): Pair<String, String> {
        val request = buildRequest(task, url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IllegalStateException("Empty playlist")
            if (body.length > 4 * 1024 * 1024) throw IllegalStateException("Playlist too large")
            return body to response.request.url.toString()
        }
    }

    private fun pickVariant(task: CineDownload, masterUrl: String, text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        data class Variant(val url: String, val height: Int)

        val variants = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
            val height = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            variants += Variant(absolute(masterUrl, uri), height)
        }
        if (variants.isEmpty()) return masterUrl

        val cap = CinePrefs.maxHeight(this)
        return (variants.filter { it.height in 1..cap }.maxByOrNull { it.height }
            ?: variants.minByOrNull { it.height }
            ?: variants.first()).url
    }

    private fun writeHls(
        task: CineDownload,
        text: String,
        baseUrl: String,
        out: OutputStream,
    ): Long {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var mediaSequence = 0L
        var keyMethod = "NONE"
        var keyUri: String? = null
        var keyIv: String? = null
        var mapUri: String? = null
        val segments = ArrayList<String>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    keyMethod = attrs["METHOD"] ?: "NONE"
                    keyUri = attrs["URI"]?.let { absolute(baseUrl, it) }
                    keyIv = attrs["IV"]
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    mapUri = attrs["URI"]?.let { absolute(baseUrl, it) }
                }
                !line.startsWith("#") -> segments += absolute(baseUrl, line)
            }
        }

        var written = 0L
        val keyCache = HashMap<String, ByteArray>()
        val totalSegments = segments.size.coerceAtLeast(1)
        var completedSegments = 0

        if (!mapUri.isNullOrBlank()) {
            if (isCanceled(this, task.id)) throw CanceledException()
            val init = fetchBytes(task, mapUri)
            out.write(init)
            written += init.size
        }

        for ((index, segmentUrl) in segments.withIndex()) {
            if (isCanceled(this, task.id)) throw CanceledException()
            var data = fetchBytes(task, segmentUrl)

            if (keyMethod.equals("AES-128", ignoreCase = true) && !keyUri.isNullOrBlank()) {
                val key = keyCache.getOrPut(keyUri) { fetchBytes(task, keyUri) }
                val iv = parseIv(keyIv, mediaSequence + index)
                data = decryptAes(data, key, iv)
            }

            out.write(data)
            written += data.size
            completedSegments = index + 1
            val progress = completedSegments.toFloat() / totalSegments
            onProgress(task, written, 0L, progress)
        }
        return written
    }

    private fun fetchBytes(task: CineDownload, url: String): ByteArray {
        val request = buildRequest(task, url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for segment")
            return response.body?.bytes() ?: throw IllegalStateException("Empty segment")
        }
    }

    private fun buildRequest(task: CineDownload, url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", task.userAgent ?: HlsPlaylist.defaultUserAgent)
            .header("Accept", "*/*")
        task.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        task.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        return builder.build()
    }

    private fun absolute(base: String, uri: String): String = try {
        URL(URL(base), uri).toString()
    } catch (_: Throwable) {
        uri
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        val regex = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)", RegexOption.IGNORE_CASE)
        regex.findAll(raw).forEach { match ->
            val key = match.groupValues[1].uppercase()
            val value = match.groupValues[2].trim().trim('"')
            out[key] = value
        }
        return out
    }

    private fun parseIv(raw: String?, sequence: Long): ByteArray {
        val cleaned = raw?.removePrefix("0x")?.removePrefix("0X")
        if (!cleaned.isNullOrBlank()) {
            val padded = cleaned.padStart(32, '0').takeLast(32)
            return padded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()
    }

    private fun decryptAes(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (t: Throwable) {
            throw IllegalStateException("Could not decrypt stream: ${t.message}")
        }
    }

    // -------------------------------------------------------------------- misc

    private fun fileBaseName(task: CineDownload): String {
        val base = if (task.isSeriesEpisode) {
            "${task.titleName} S${task.season}E${task.episode}"
        } else {
            task.titleName
        }
        val id = task.titleId.takeIf { it.isNotBlank() }
        return CineDownloads.sanitize(if (id != null) "$base [$id]" else base)
    }

    private fun extensionFor(url: String, contentType: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        if (ext in setOf("mp4", "webm", "mkv", "m4v", "mov", "avi", "ts")) return ext
        return when {
            contentType.contains("webm") -> "webm"
            contentType.contains("matroska") -> "mkv"
            contentType.contains("quicktime") -> "mov"
            else -> "mp4"
        }
    }

    private fun onProgress(task: CineDownload, bytes: Long, total: Long, progress: Float) {
        val now = System.currentTimeMillis()
        val last = lastProgressAt[task.id] ?: 0L
        if (now - last < 500L && progress < 1f) return
        lastProgressAt[task.id] = now

        CineDownloads.setProgress(this, task.id, bytes, total, progress)
        val label = if (total > 0) {
            "${CineDownloads.formatBytes(bytes)} / ${CineDownloads.formatBytes(total)}"
        } else {
            CineDownloads.formatBytes(bytes)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            notificationId(task.id),
            buildNotification(task, label, progress, indeterminate = total <= 0L),
        )
    }

    private fun notifyFinished(
        task: CineDownload,
        fileName: String,
        success: Boolean,
        canceled: Boolean = false,
    ) {
        val text = when {
            canceled -> "Download canceled"
            success -> "Saved ${fileName.ifBlank { "video" }}"
            else -> "Download failed"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(task.label)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)?.notify(notificationId(task.id), notification)
    }

    private fun notificationId(id: String): Int =
        NOTIFICATION_ID + (id.hashCode() and 0x7FFF) % 1000

    private fun buildNotification(
        task: CineDownload,
        text: String,
        progress: Float,
        indeterminate: Boolean,
    ): Notification {
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.label)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, pct, indeterminate)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, CineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Movie and episode downloads"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private class CanceledException : Exception("Download canceled")
}
