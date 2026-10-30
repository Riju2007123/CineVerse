package `is`.xyz.mpv.cine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

data class PendingDownloadTask(
    val title: CineTitle,
    val season: Int?,
    val episode: Int?,
    val label: String
)

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
    val context = LocalContext.current
    var title by remember(id) { mutableStateOf<CineTitle?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var retryTick by remember(id) { mutableIntStateOf(0) }
    var season by remember(id) { mutableIntStateOf(1) }
    var selectedTab by remember(id) { mutableStateOf("Episodes") }
    var pendingTask by remember { mutableStateOf<PendingDownloadTask?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            CineDownloads.setTreeUri(context, uri)
            pendingTask?.let { task ->
                val key = playbackKey(task.title.id, task.season, task.episode)
                val download = CineDownloads.newTask(
                    title = task.title,
                    key = key,
                    label = task.label,
                    season = task.season,
                    episode = task.episode,
                    streamUrl = "",
                    referer = null,
                    userAgent = null,
                    cookie = null
                )
                CineDownloads.enqueue(context, download)
                CineDownloadService.start(context, download.id)
                Toast.makeText(context, "Download queued: ${task.label}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Download folder required", Toast.LENGTH_SHORT).show()
        }
        pendingTask = null
    }

    fun triggerDownload(taskTitle: CineTitle, taskSeason: Int?, taskEpisode: Int?, taskLabel: String) {
        val task = PendingDownloadTask(taskTitle, taskSeason, taskEpisode, taskLabel)
        if (CineDownloads.hasTree(context)) {
            val key = playbackKey(taskTitle.id, taskSeason, taskEpisode)
            val download = CineDownloads.newTask(
                title = taskTitle,
                key = key,
                label = taskLabel,
                season = taskSeason,
                episode = taskEpisode,
                streamUrl = "",
                referer = null,
                userAgent = null,
                cookie = null
            )
            CineDownloads.enqueue(context, download)
            CineDownloadService.start(context, download.id)
            Toast.makeText(context, "Download queued: $taskLabel", Toast.LENGTH_SHORT).show()
        } else {
            pendingTask = task
            folderLauncher.launch(null)
        }
    }

    LaunchedEffect(id, retryTick) {
        loading = true
        error = null
        runCatching { CinemetaApi.details(id, type) }
            .onSuccess {
                title = it
                season = it.videos.minOfOrNull { video -> video.season } ?: 1
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
            contentAlignment = Alignment.Center
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        .height(260.dp)
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
                                    0.3f to Color.Transparent,
                                    1.0f to MaterialTheme.colorScheme.background,
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = loaded.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                loaded.year.takeIf { it.isNotBlank() },
                                if (loaded.isSeries) "${seasons.size} seasons" else "Movie",
                                loaded.rating?.let { "★ $it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.LightGray
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val first = loaded.videos.minByOrNull { it.season * 1000 + it.episode }
                            val buttonText = if (loaded.isSeries && first != null) "Resume S${first.season}:E${first.episode}" else "Play"

                            PrimaryPlayButton(
                                text = buttonText,
                                onClick = {
                                    if (loaded.isSeries && first != null) {
                                        onPlay(loaded, first.season, first.episode, "${loaded.name} · ${first.label}")
                                    } else {
                                        onPlay(loaded, null, null, loaded.name)
                                    }
                                }
                            )

                            Spacer(Modifier.width(10.dp))

                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x44FFFFFF))
                                    .clickable { onToggleSave(loaded) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark",
                                    tint = if (saved) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x44FFFFFF))
                                    .clickable {
                                        val firstVideo = loaded.videos.minByOrNull { it.season * 1000 + it.episode }
                                        if (loaded.isSeries && firstVideo != null) {
                                            triggerDownload(loaded, firstVideo.season, firstVideo.episode, "${loaded.name} · ${firstVideo.label}")
                                        } else {
                                            triggerDownload(loaded, null, null, loaded.name)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (loaded.isSeries && seasons.isNotEmpty()) {
                            SelectorPill("Season $season ▾") {
                                val nextIndex = (seasons.indexOf(season) + 1) % seasons.size
                                season = seasons[nextIndex]
                            }
                        }
                        SelectorPill("Audio: EN") {}
                        SelectorPill("Subs: Off") {}
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = loaded.description ?: "No description available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        listOf("Episodes", "More like this", "Details").forEach { tab ->
                            val isSelected = selectedTab == tab
                            Column(
                                modifier = Modifier.clickable { selectedTab = tab },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = tab,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .width(24.dp)
                                            .height(2.dp)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedTab == "Episodes" && loaded.isSeries) {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeCard(
                        episode = episode,
                        progress = progressMap[playbackKey(loaded.id, episode.season, episode.episode)],
                        onClick = {
                            onPlay(loaded, episode.season, episode.episode, "${loaded.name} · ${episode.label}")
                        },
                        onDownload = {
                            triggerDownload(loaded, episode.season, episode.episode, "${loaded.name} · ${episode.label}")
                        }
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
private fun SelectorPill(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun EpisodeCard(
    episode: CineEpisode,
    progress: CineProgress?,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(110.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(PosterPlaceholder)
        ) {
            if (!episode.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (progress != null && progress.isResumable) {
                AnimatedProgressBar(
                    fraction = progress.fraction,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.episode}. ${episode.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "52 min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { onDownload() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
