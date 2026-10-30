package `is`.xyz.mpv.cine

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

val PosterPlaceholder = Brush.linearGradient(
    listOf(Color(0xFF202030), Color(0xFF0F0F1A))
)

val EaseEntrance = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)

/** 1. Screen entrance wrapper: fades in & slides up 18px over 0.6s with cubic-bezier */
@Composable
fun AnimatedEntrance(
    index: Int = 0,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    if (reduceMotion) {
        content()
        return
    }

    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(index * 80L)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600, easing = EaseEntrance),
        label = "entranceAlpha"
    )

    val translateY by animateFloatAsState(
        targetValue = if (visible) 0f else 18f,
        animationSpec = tween(600, easing = EaseEntrance),
        label = "entranceTranslate"
    )

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = translateY * density
        }
    ) {
        content()
    }
}

/** 7. Toggle switches: 48x28 track, 22px knob that slides 20px with slight spring overshoot */
@Composable
fun CustomSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false
) {
    val trackColor = if (checked) MaterialTheme.colorScheme.primary else Color(0xFF383852)
    val thumbOffsetDp by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = if (reduceMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "switchThumb"
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x = thumbOffsetDp.roundToPx(), y = 0) }
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** 4. Primary Play Button: subtle pulse & expanding ring fading to transparent */
@Composable
fun PrimaryPlayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "pulseRing")

    val ringScale by if (reduceMotion) remember { mutableFloatStateOf(1f) } else transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringScale"
    )

    val ringAlpha by if (reduceMotion) remember { mutableFloatStateOf(0f) } else transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        if (!reduceMotion) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                        alpha = ringAlpha
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onClick() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

/** 5. Progress Bar: animate width from 0 to value over 1.2s, delayed 0.5s */
@Composable
fun AnimatedProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    var targetFraction by remember { mutableFloatStateOf(if (reduceMotion) fraction else 0f) }

    LaunchedEffect(fraction) {
        if (!reduceMotion) {
            delay(500)
            targetFraction = fraction
        } else {
            targetFraction = fraction
        }
    }

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) tween(0) else tween(1200, easing = EaseEntrance),
        label = "progressBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color(0x44000000))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(barColor)
        )
    }
}

@Composable
fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(PosterPlaceholder)) {
        if (url.isNullOrBlank()) {
            Text(
                text = "CineVerse",
                color = Color(0x66FFFFFF),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 6. Poster Card: press scale (0.95) / hover lift (1.04) */
@Composable
fun PosterCard(
    title: CineTitle,
    modifier: Modifier = Modifier,
    progress: CineProgress? = null,
    reduceMotion: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reduceMotion) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "cardScale"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
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
                modifier = Modifier.fillMaxSize()
            )

            if (!title.rating.isNullOrBlank()) {
                Text(
                    text = "★ ${title.rating}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            if (progress != null && progress.isResumable) {
                AnimatedProgressBar(
                    fraction = progress.fraction,
                    reduceMotion = reduceMotion,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = title.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(
                title.year.takeIf { it.isNotBlank() },
                if (title.isSeries) "Series" else "Movie"
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 2 & 3. Hero Carousel / Banner with 14s ambient slow zoom, sequential text entrance, and 6s auto-advance carousel pill indicators */
@Composable
fun HeroCarousel(
    titles: List<CineTitle>,
    savedIds: Set<String>,
    onOpen: (CineTitle) -> Unit,
    onPlay: (CineTitle) -> Unit,
    onToggleSave: (CineTitle) -> Unit,
    onPageChange: (CineTitle) -> Unit = {},
    reduceMotion: Boolean = false
) {
    if (titles.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { titles.size })

    LaunchedEffect(pagerState.currentPage) {
        titles.getOrNull(pagerState.currentPage)?.let { onPageChange(it) }
    }

    // Auto advance carousel every 6s
    LaunchedEffect(pagerState) {
        while (true) {
            delay(6000)
            if (!reduceMotion && titles.size > 1) {
                val nextPage = (pagerState.currentPage + 1) % titles.size
                pagerState.animateScrollToPage(nextPage, animationSpec = tween(800, easing = EaseEntrance))
            }
        }
    }

    val currentTitle = titles.getOrNull(pagerState.currentPage) ?: titles.first()
    val isItemSaved = savedIds.contains(currentTitle.id)

    // 2. Background slowly zooms 100% -> 112% over 14s
    val infiniteTransition = rememberInfiniteTransition(label = "heroZoom")
    val heroScale by if (reduceMotion) remember { mutableFloatStateOf(1f) } else infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgZoom"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141422))
            .clickable { onOpen(currentTitle) }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val title = titles[page]
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = title.background ?: title.poster,
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = heroScale
                            scaleY = heroScale
                        }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xEC0E0E17)),
                                startY = 40f
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = "New & Popular",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = currentTitle.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = listOfNotNull(
                    currentTitle.year.takeIf { it.isNotBlank() },
                    if (currentTitle.isSeries) "Series" else "Movie",
                    currentTitle.rating?.let { "★ $it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryPlayButton(
                    text = "Play",
                    onClick = { onPlay(currentTitle) },
                    reduceMotion = reduceMotion
                )

                Spacer(Modifier.width(10.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable { onToggleSave(currentTitle) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isItemSaved) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isItemSaved) "In list" else "+ My list",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 3. Carousel indicators: inactive 14px, active 34px pill with filling bar
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            titles.indices.forEach { index ->
                val isActive = index == pagerState.currentPage
                val targetWidth = if (isActive) 34.dp else 14.dp
                val animatedWidth by animateDpAsState(
                    targetValue = targetWidth,
                    animationSpec = tween(300),
                    label = "indicatorWidth"
                )

                Box(
                    modifier = Modifier
                        .width(animatedWidth)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (isActive) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF))
                )
            }
        }
    }
}

@Composable
fun PosterRow(
    title: String,
    items: List<CineTitle>,
    progressMap: Map<String, CineProgress> = emptyMap(),
    onOpen: (CineTitle) -> Unit,
    onSeeAll: (() -> Unit)? = null
) {
    if (items.isEmpty()) return

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        SectionHeader(title = title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(
                    title = item,
                    progress = progressMap[playbackKey(item.id, null, null)],
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(124.dp)
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (onSeeAll != null) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSeeAll() }
            )
        }
    }
}

@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StreamDialog(
    request: PlayRequest,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    val valid = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.label) },
        text = {
            Column {
                Text(
                    "Enter a custom direct video or HLS URL (.mp4, .m3u8):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPlay(url) }, enabled = valid) { Text("Play") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
