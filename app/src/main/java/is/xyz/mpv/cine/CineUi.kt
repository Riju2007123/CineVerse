package `is`.xyz.mpv.cine

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

val PosterPlaceholder = Brush.linearGradient(
    listOf(Color(0xFF241B33), Color(0xFF101019))
)

@Composable
fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(PosterPlaceholder)) {
        if (url.isNullOrBlank()) {
            Text(
                text = "CineVerse",
                color = Color(0x66FFFFFF),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun PosterCard(
    title: CineTitle,
    modifier: Modifier = Modifier,
    progress: CineProgress? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            PosterImage(
                url = title.poster,
                contentDescription = title.name,
                modifier = Modifier.fillMaxSize(),
            )
            if (!title.rating.isNullOrBlank()) {
                Text(
                    text = "★ ${title.rating}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = title.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                title.year.takeIf { it.isNotBlank() },
                if (title.isSeries) "Series" else "Movie",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onSeeAll() }
                    .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun PosterRow(
    title: String,
    items: List<CineTitle>,
    loading: Boolean,
    onOpen: (CineTitle) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    progressFor: (CineTitle) -> CineProgress? = { null },
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        SectionHeader(title = title, onSeeAll = onSeeAll)
        if (loading && items.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    PosterCard(
                        title = item,
                        progress = progressFor(item),
                        onClick = { onOpen(item) },
                        modifier = Modifier.width(118.dp),
                    )
                }
            }
        }
    }
}

/**
 * Auto-advancing hero, Stremio style: full-bleed backdrop, logo, meta line and
 * a single primary action.
 */
@Composable
fun HeroCarousel(
    titles: List<CineTitle>,
    savedIds: Set<String>,
    onOpen: (CineTitle) -> Unit,
    onPlay: (CineTitle) -> Unit,
    onToggleSave: (CineTitle) -> Unit,
    onPageChange: (CineTitle) -> Unit = {},
) {
    if (titles.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(PosterPlaceholder)
        )
        return
    }

    val pager = rememberPagerState(pageCount = { titles.size })
    LaunchedEffect(titles.size) {
        if (titles.size > 1) {
            while (true) {
                delay(7_000)
                if (!pager.isScrollInProgress) {
                    pager.animateScrollToPage((pager.currentPage + 1) % titles.size)
                }
            }
        }
    }

    LaunchedEffect(pager.settledPage, titles) {
        titles.getOrNull(pager.settledPage)?.let(onPageChange)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(state = pager) { page ->
            val title = titles[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(470.dp)
            ) {
                PosterImage(
                    url = title.background ?: title.poster,
                    contentDescription = title.name,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x99000000),
                                0.35f to Color(0x33000000),
                                0.7f to Color(0xCC0B0B12),
                                1f to Color(0xFF0B0B12),
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 22.dp),
                ) {
                    var logoFailed by remember(title.id) { mutableStateOf(false) }
                    if (!title.logo.isNullOrBlank() && !logoFailed) {
                        AsyncImage(
                            model = title.logo,
                            contentDescription = title.name,
                            contentScale = ContentScale.Fit,
                            onError = { logoFailed = true },
                            modifier = Modifier
                                .height(62.dp)
                                .width(230.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(
                            text = title.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    Text(
                        text = title.metaLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFD6D6E0),
                    )
                    if (title.genres.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = title.genres.take(3).joinToString("  •  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9E9EB3),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White)
                                .clickable { onPlay(title) }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Watch",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0x40FFFFFF))
                                .clickable { onToggleSave(title) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (savedIds.contains(title.id)) {
                                    Icons.Default.Check
                                } else {
                                    Icons.Default.Add
                                },
                                contentDescription = "My list",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0x33FFFFFF))
                                .clickable { onOpen(title) }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = "Details",
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        // Dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(titles.size.coerceAtMost(7)) { dot ->
                val active = pager.currentPage % 7 == dot
                val width by animateFloatAsState(if (active) 16f else 6f, label = "dot")
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(width.dp)
                        .clip(CircleShape)
                        .background(if (active) Color.White else Color(0x66FFFFFF))
                )
            }
        }
    }
}

@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color.White
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun EmptyState(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
