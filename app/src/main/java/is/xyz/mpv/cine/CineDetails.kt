package `is`.xyz.mpv.cine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsContent(
    id: String,
    type: String,
    saved: Boolean,
    progressMap: Map<String, CineProgress>,
    onBack: () -> Unit,
    onToggleSave: (CineTitle) -> Unit,
    onPlay: (CineTitle, Int?, Int?, String) -> Unit,
    onCustomPlay: (CineTitle, String, String) -> Unit,
) {
    var title by remember(id) { mutableStateOf<CineTitle?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var retryTick by remember(id) { mutableStateOf(0) }
    var season by remember(id) { mutableStateOf(0) }

    val context = LocalContext.current
    var pendingDownload by remember(id) { mutableStateOf<DownloadTarget?>(null) }
    var downloadTick by remember(id) { mutableStateOf(0) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            CineDownloads.setTreeUri(context, uri)
            Toast.makeText(context, "Download folder set", Toast.LENGTH_SHORT).show()
            pendingDownload?.let { startDownload(context, it) }
        }
        pendingDownload = null
    }

    LaunchedEffect(id) {
        while (true) {
            kotlinx.coroutines.delay(1_500)
            downloadTick++
        }
    }

    LaunchedEffect(id, retryTick) {
        loading = true
        error = null
        runCatching { CinemetaApi.details(id, type) }
            .onSuccess {
                title = it
                season = it.videos.minOfOrNull { video -> video.season } ?: 0
            }
            .onFailure { error = it.message ?: "Could not load this title" }
        loading = false
    }

    val loaded = title

    if (loading && loaded == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        return
    }

    if (loaded == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: "Could not load this title", color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { retryTick++ }) { Text("Retry") }
            }
        }
        return
    }

    val seasons = loaded.videos.map { it.season }.distinct().sorted()
    val episodes = loaded.videos.filter { it.season == season }
    val prefetchEpisode = if (loaded.isSeries) episodes.minByOrNull { it.episode } else null

    val titleDownloads = remember(downloadTick, loaded.id) {
        CineDownloads.list(context).filter { it.titleId == loaded.id }
    }

    fun requestDownload(target: DownloadTarget) {
        if (CineDownloads.hasTree(context)) {
            startDownload(context, target)
        } else {
            pendingDownload = target
            folderLauncher.launch(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Warm the stream while the user is still reading, so Watch plays at once.
        PrefetchStream(
            title = loaded,
            season = prefetchEpisode?.season,
            episode = prefetchEpisode?.episode,
        )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                PosterImage(
                    url = loaded.background ?: loaded.poster,
                    contentDescription = loaded.name,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color(0x00000000),
                                1f to Color(0xFF0B0B12),
                            )
                        )
                )
            }
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PosterImage(
                    url = loaded.poster,
                    contentDescription = loaded.name,
                    modifier = Modifier
                        .width(112.dp)
                        .height(168.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = loaded.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = loaded.metaLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(loaded.genres.take(4), key = { it }) { genre ->
                            MetaChip(genre)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .combinedClickable(
                                    onClick = {
                                        val first = loaded.videos
                                            .minByOrNull { it.season * 1000 + it.episode }
                                        if (loaded.isSeries && first != null) {
                                            onPlay(
                                                loaded,
                                                first.season,
                                                first.episode,
                                                "${loaded.name} · ${first.label}",
                                            )
                                        } else {
                                            onPlay(loaded, null, null, loaded.name)
                                        }
                                    },
                                    onLongClick = {
                                        val first = loaded.videos
                                            .minByOrNull { it.season * 1000 + it.episode }
                                        if (loaded.isSeries && first != null) {
                                            onCustomPlay(
                                                loaded,
                                                playbackKey(loaded.id, first.season, first.episode),
                                                "${loaded.name} · ${first.label}",
                                            )
                                        } else {
                                            onCustomPlay(
                                                loaded,
                                                playbackKey(loaded.id, null, null),
                                                loaded.name,
                                            )
                                        }
                                    },
                                )
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (loaded.isSeries) "Play S${seasons.firstOrNull() ?: 1}E1" else "Watch",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val context = LocalContext.current
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onToggleSave(loaded) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Watchlist",
                                tint = if (saved) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val firstEpisode = loaded.videos
                            .minByOrNull { it.season * 1000 + it.episode }
                        val mainTarget = if (loaded.isSeries && firstEpisode != null) {
                            DownloadTarget(
                                title = loaded,
                                season = firstEpisode.season,
                                episode = firstEpisode.episode,
                                label = "${loaded.name} · ${firstEpisode.label}",
                            )
                        } else {
                            DownloadTarget(loaded, null, null, loaded.name)
                        }
                        val mainDownload = titleDownloads.firstOrNull {
                            if (loaded.isSeries) it.season == mainTarget.season && it.episode == mainTarget.episode
                            else !it.isSeriesEpisode
                        }
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    when {
                                        mainDownload?.isComplete == true -> Toast.makeText(
                                            context,
                                            "Already downloaded",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        mainDownload?.isActive == true -> Toast.makeText(
                                            context,
                                            "Download in progress",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        else -> requestDownload(mainTarget)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                mainDownload?.isComplete == true -> Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                mainDownload?.isActive == true -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = "Overview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = loaded.description ?: "No overview available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loaded.cast.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Cast",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = loaded.cast.take(8).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loaded.director.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Director · ${loaded.director.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (loaded.isSeries && loaded.videos.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons, key = { it }) { number ->
                            SeasonPill(
                                label = if (number == 0) "Specials" else "Season $number",
                                selected = number == season,
                                onClick = { season = number },
                            )
                        }
                    }
                }
            }

            items(episodes, key = { it.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    progress = progressMap[playbackKey(loaded.id, episode.season, episode.episode)],
                    onClick = {
                        onPlay(
                            loaded,
                            episode.season,
                            episode.episode,
                            "${loaded.name} · ${episode.label}",
                        )
                    },
                    onLongClick = {
                        onCustomPlay(
                            loaded,
                            playbackKey(loaded.id, episode.season, episode.episode),
                            "${loaded.name} · ${episode.label}",
                        )
                    },
                    download = titleDownloads.firstOrNull {
                        it.season == episode.season && it.episode == episode.episode
                    },
                    onDownload = {
                        requestDownload(
                            DownloadTarget(
                                title = loaded,
                                season = episode.season,
                                episode = episode.episode,
                                label = "${loaded.name} · ${episode.label}",
                            )
                        )
                    },
                )
            }
        }
    }

        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SeasonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    episode: CineEpisode,
    progress: CineProgress?,
    download: CineDownload?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PosterPlaceholder)
        ) {
            if (!episode.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }
            if (progress != null && progress.isResumable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x55000000))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.label}  ${episode.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.overview
                    ?: listOf(episode.year.takeIf { it.isNotBlank() }, episode.released?.take(10))
                        .filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = download?.isActive != true && download?.isComplete != true) {
                    onDownload()
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                download?.isComplete == true -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                download?.isActive == true -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    Icons.Default.Download,
                    contentDescription = "Download episode",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Advanced escape hatch: play a direct file / HLS / DASH link in mpv instead of
 * the embed player. Opened by long-pressing a Watch button or an episode.
 */
@Composable
fun StreamDialog(
    request: PlayRequest,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
) {
    val context = LocalContext.current
    var url by remember(request.key) {
        mutableStateOf(CineLibrary.linkFor(context, request.key) ?: "")
    }
    val valid = url.startsWith("http://") || url.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play “${request.label}” in mpv") },
        text = {
            Column {
                Text(
                    text = "Optional: paste a direct video, HLS (.m3u8) or DASH (.mpd) link to play " +
                        "it in mpv instead of the built-in player. CineVerse remembers it for this title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPlay(url) }, enabled = valid) { Text("Play") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** A movie or episode the user asked to download. */
private data class DownloadTarget(
    val title: CineTitle,
    val season: Int?,
    val episode: Int?,
    val label: String,
)

/**
 * Resolves the best source through the normal provider flow and hands the
 * resulting stream URL to the download service.
 */
private fun startDownload(context: Context, target: DownloadTarget) {
    try {
        context.startActivity(
            CineResolverActivity.intent(
                context = context,
                title = target.title,
                season = target.season,
                episode = target.episode,
                label = target.label,
                download = true,
            )
        )
        Toast.makeText(context, "Finding best source…", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        Toast.makeText(context, "Could not start the download", Toast.LENGTH_LONG).show()
    }
}
