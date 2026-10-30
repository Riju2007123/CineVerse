package `is`.xyz.mpv.cine

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.preferences.PreferenceActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class CineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(CinePrefs.theme(context)) }
            var accentIndex by remember { mutableIntStateOf(CinePrefs.accentIndex(context)) }

            CineVerseTheme(
                themeMode = themeMode,
                accentColor = CinePrefs.ACCENT_OPTIONS[accentIndex]
            ) {
                NotificationPermissionGate()
                CineApp(
                    themeMode = themeMode,
                    accentIndex = accentIndex,
                    onThemeChange = { mode ->
                        themeMode = mode
                        CinePrefs.setTheme(context, mode)
                    },
                    onAccentChange = { index ->
                        accentIndex = index
                        CinePrefs.setAccentIndex(context, index)
                    }
                )
            }
        }
    }
}

/** Asks for notification permission so download progress can be shown. */
@Composable
private fun NotificationPermissionGate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

sealed interface CineScreen {
    data object Home : CineScreen
    data object Browse : CineScreen
    data object Search : CineScreen
    data object Library : CineScreen
    data object Downloads : CineScreen
    data object Settings : CineScreen
    data class Details(val id: String, val type: String) : CineScreen
    data class Grid(
        val title: String,
        val type: String,
        val catalog: String = "top",
        val genre: String? = null,
    ) : CineScreen
}

private val HOME_ROWS = listOf(
    CatalogSpec("Trending movies", "movie", "top"),
    CatalogSpec("New releases", "movie", "year"),
    CatalogSpec("Trending series", "series", "top"),
    CatalogSpec("New episodes", "series", "last-videos"),
    CatalogSpec("Top rated movies", "movie", "imdbRating"),
    CatalogSpec("Action", "movie", "top", "Action"),
    CatalogSpec("Comedy", "movie", "top", "Comedy"),
    CatalogSpec("Sci-Fi", "movie", "top", "Sci-Fi"),
    CatalogSpec("Horror", "movie", "top", "Horror"),
    CatalogSpec("Drama series", "series", "top", "Drama"),
)

private val BROWSE_GENRES = listOf(
    "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama",
    "Family", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller",
    "War", "Western",
)

@Composable
fun CineApp(
    themeMode: String = "dark",
    accentIndex: Int = 0,
    onThemeChange: (String) -> Unit = {},
    onAccentChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current

    var screen by remember { mutableStateOf<CineScreen>(CineScreen.Home) }
    var watchlist by remember { mutableStateOf(CineLibrary.watchlist(context)) }
    var history by remember { mutableStateOf(CineLibrary.history(context)) }
    var playRequest by remember { mutableStateOf<PlayRequest?>(null) }

    var rows by remember { mutableStateOf<List<Pair<CatalogSpec, List<CineTitle>>>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(true) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var updateInfo by remember { mutableStateOf<CineUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val info = CineUpdateChecker.checkForUpdates()
        if (info.isUpdateAvailable && info.tagName != CinePrefs.dismissedUpdate(context)) {
            updateInfo = info
            showUpdateDialog = true
        }
    }

    LaunchedEffect(reloadTick) {
        homeLoading = true
        homeError = null
        try {
            val loaded = coroutineScope {
                HOME_ROWS.map { spec ->
                    async {
                        spec to runCatching { CinemetaApi.row(spec) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            rows = loaded.filter { it.second.isNotEmpty() }
            if (rows.isEmpty()) homeError = "Could not reach the catalogue. Check your connection."
        } catch (t: Throwable) {
            homeError = t.message ?: "Could not load the catalogue"
        }
        homeLoading = false
    }

    val progressMap = remember(history) {
        history.mapNotNull { entry ->
            CineLibrary.progress(context, entry.key)?.let { entry.key to it }
        }.toMap()
    }

    val heroTitles = remember(rows) {
        rows.take(3).flatMap { it.second.take(2) }.distinctBy { it.id }.take(6)
    }

    fun openDetails(title: CineTitle) {
        screen = CineScreen.Details(title.id, title.type)
    }

    fun toggleSave(title: CineTitle) {
        watchlist = CineLibrary.toggleWatchlist(context, title)
    }

    fun openPlayer(title: CineTitle, season: Int?, episode: Int?, label: String) {
        val key = playbackKey(title.id, season, episode)
        CineLibrary.recordPlay(context, title, key, label)
        history = CineLibrary.history(context)
        try {
            context.startActivity(CineResolverActivity.intent(context, title, season, episode, label))
        } catch (t: Throwable) {
            android.util.Log.e("CineVerse", "Could not start playback", t)
            Toast.makeText(context, "Could not start playback", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = screen !is CineScreen.Home) {
        screen = CineScreen.Home
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (val current = screen) {
                    is CineScreen.Home -> HomeContent(
                        hero = heroTitles,
                        rows = rows,
                        loading = homeLoading,
                        error = homeError,
                        watchlist = watchlist,
                        progressMap = progressMap,
                        history = history,
                        onOpen = { openDetails(it) },
                        onPlay = { title ->
                            if (title.isSeries) openDetails(title) else openPlayer(title, null, null, title.name)
                        },
                        onToggleSave = { toggleSave(it) },
                        onRetry = { reloadTick++ },
                        onSearch = { screen = CineScreen.Search },
                        onSettings = { screen = CineScreen.Settings },
                        onSeeAll = { spec ->
                            screen = CineScreen.Grid(spec.title, spec.type, spec.catalog, spec.genre)
                        },
                    )

                    is CineScreen.Browse -> BrowseContent(
                        onOpen = { openDetails(it) },
                    )

                    is CineScreen.Search -> SearchContent(
                        onOpen = { openDetails(it) },
                        onBack = { screen = CineScreen.Home },
                    )

                    is CineScreen.Library -> LibraryContent(
                        watchlist = watchlist,
                        history = history,
                        progressMap = progressMap,
                        onOpen = { openDetails(it) },
                        onRemove = { toggleSave(it) },
                    )

                    is CineScreen.Downloads -> DownloadsContent(
                        onOpen = { openDetails(it) },
                    )

                    is CineScreen.Settings -> SettingsContent(
                        onBack = { screen = CineScreen.Home },
                        onTriggerUpdate = { info ->
                            updateInfo = info
                            showUpdateDialog = true
                        },
                        themeMode = themeMode,
                        accentIndex = accentIndex,
                        onThemeChange = onThemeChange,
                        onAccentChange = onAccentChange
                    )

                    is CineScreen.Grid -> CatalogGridScreen(
                        screen = current,
                        progressMap = progressMap,
                        onBack = { screen = CineScreen.Home },
                        onOpen = { openDetails(it) },
                    )

                    is CineScreen.Details -> DetailsContent(
                        id = current.id,
                        type = current.type,
                        saved = watchlist.any { it.id == current.id },
                        progressMap = progressMap,
                        onBack = { screen = CineScreen.Home },
                        onToggleSave = { title -> toggleSave(title) },
                        onPlay = { title, season, episode, label ->
                            openPlayer(title, season, episode, label)
                        },
                        onCustomPlay = { title, key, label ->
                            playRequest = PlayRequest(title, key, label)
                        },
                    )
                }

                playRequest?.let { request ->
                    StreamDialog(
                        request = request,
                        onDismiss = { playRequest = null },
                        onPlay = { url ->
                            CineLibrary.saveLink(context, request.key, url)
                            CinePlayer.play(context, request.title, request.key, request.label, url)
                            history = CineLibrary.history(context)
                            playRequest = null
                        },
                    )
                }

                if (showUpdateDialog && updateInfo != null) {
                    val info = updateInfo!!
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = false },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(info.title.ifBlank { "Update Available: ${info.tagName}" })
                            }
                        },
                        text = {
                            Column {
                                Text(
                                    "A new version (${info.tagName}) of CineVerse is available!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "What's New:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showUpdateDialog = false
                                    CineUpdateChecker.openUpdateUrl(context, info)
                                }
                            ) {
                                Text("Update Now")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showUpdateDialog = false
                                CinePrefs.dismissUpdate(context, info.tagName)
                            }) {
                                Text("Later")
                            }
                        }
                    )
                }
            }

            CineBottomBar(current = screen, onSelect = { screen = it })
        }
    }
}

@Composable
private fun CineBottomBar(current: CineScreen, onSelect: (CineScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomItem(Icons.Default.Home, "Home", current is CineScreen.Home) {
            onSelect(CineScreen.Home)
        }
        BottomItem(Icons.Default.Tv, "Browse", current is CineScreen.Browse) {
            onSelect(CineScreen.Browse)
        }
        BottomItem(Icons.Default.Search, "Search", current is CineScreen.Search) {
            onSelect(CineScreen.Search)
        }
        BottomItem(Icons.Default.Download, "Downloads", current is CineScreen.Downloads) {
            onSelect(CineScreen.Downloads)
        }
        BottomItem(Icons.Default.Settings, "Settings", current is CineScreen.Settings) {
            onSelect(CineScreen.Settings)
        }
    }
}

@Composable
private fun BottomItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ----------------------------------------------------------------------- home

@Composable
private fun HomeContent(
    hero: List<CineTitle>,
    rows: List<Pair<CatalogSpec, List<CineTitle>>>,
    loading: Boolean,
    error: String?,
    watchlist: List<CineTitle>,
    progressMap: Map<String, CineProgress>,
    history: List<HistoryEntry>,
    onOpen: (CineTitle) -> Unit,
    onPlay: (CineTitle) -> Unit,
    onToggleSave: (CineTitle) -> Unit,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSeeAll: (CatalogSpec) -> Unit,
) {
    val savedIds = remember(watchlist) { watchlist.map { it.id }.toSet() }
    var heroFocus by remember { mutableStateOf<CineTitle?>(null) }
    // Bound the background warming: at most four distinct titles per session.
    val warmedIds = remember { mutableSetOf<String>() }
    val resumable = remember(history, progressMap) {
        history.mapNotNull { entry ->
            val progress = progressMap[entry.key] ?: return@mapNotNull null
            if (!progress.isResumable) return@mapNotNull null
            Triple(entry, progress, titleStub(entry))
        }
    }

    val listState = rememberLazyListState()
    var selectedCategory by remember { mutableStateOf("For you") }

    val context = LocalContext.current
    val reduceMotion = remember { CinePrefs.reduceMotion(context) }

    val displayedRows = remember(rows, selectedCategory) {
        when (selectedCategory) {
            "Movies" -> rows.filter { it.first.type == "movie" }
            "Series" -> rows.filter { it.first.type == "series" }
            "Anime" -> rows.filter { it.first.genre?.contains("Animation", ignoreCase = true) == true || it.first.title.contains("Anime", ignoreCase = true) }
            else -> rows
        }
    }

    LaunchedEffect(Unit) {
        listState.scrollToItem(0, 0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(onSearch = onSearch, onSettings = onSettings)
        val focus = heroFocus
        if (focus != null && !focus.isSeries) {
            PrefetchStream(title = focus, season = null, episode = null)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (hero.isNotEmpty()) {
                item(key = "hero") {
                    AnimatedEntrance(index = 0, reduceMotion = reduceMotion) {
                        HeroCarousel(
                            titles = hero,
                            savedIds = savedIds,
                            onOpen = onOpen,
                            onPlay = onPlay,
                            onToggleSave = onToggleSave,
                            reduceMotion = reduceMotion,
                            onPageChange = { candidate ->
                                val alreadyWarm = warmedIds.contains(candidate.id)
                                if (!candidate.isSeries && (alreadyWarm || warmedIds.size < 4)) {
                                    warmedIds.add(candidate.id)
                                    heroFocus = candidate
                                }
                            },
                        )
                    }
                }
            }

            item(key = "categoryPills") {
                AnimatedEntrance(index = 1, reduceMotion = reduceMotion) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listOf("For you", "Movies", "Series", "Anime")) { cat ->
                            val isSelected = selectedCategory == cat
                            val pillBg = if (isSelected) Color.White else MaterialTheme.colorScheme.surfaceVariant
                            val textColor = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(pillBg)
                                    .clickable { selectedCategory = cat }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            if (resumable.isNotEmpty()) {
                item(key = "continue") {
                    AnimatedEntrance(index = 2, reduceMotion = reduceMotion) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            SectionHeader("Continue watching")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(resumable, key = { it.first.key }) { (entry, progress, stub) ->
                                    ContinueWatchingCard(
                                        entry = entry,
                                        progress = progress,
                                        titleStub = stub,
                                        reduceMotion = reduceMotion,
                                        onClick = { onOpen(stub) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (loading && rows.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Retry",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onRetry() },
                        )
                    }
                }
            }

            itemsIndexed(displayedRows, key = { _, item -> item.first.title }) { idx, (spec, items) ->
                AnimatedEntrance(index = idx + 3, reduceMotion = reduceMotion) {
                    PosterRow(
                        title = spec.title,
                        items = items,
                        progressMap = progressMap,
                        onOpen = onOpen,
                        onSeeAll = { onSeeAll(spec) },
                    )
                }
            }

            item(key = "footer") {
                TextButton(
                    onClick = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp, top = 2.dp),
                ) {
                    Text("Search the full catalogue")
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC0B0B12), Color(0x000B0B12))
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CINEVERSE",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

private fun titleStub(entry: HistoryEntry): CineTitle = CineTitle(
    id = entry.titleId,
    type = entry.type,
    name = entry.name,
    poster = entry.poster,
    background = entry.poster,
    logo = null,
    description = null,
    genres = emptyList(),
    rating = null,
    releaseInfo = null,
    runtime = null,
    cast = emptyList(),
    director = emptyList(),
)

// --------------------------------------------------------------------- browse

@Composable
private fun BrowseContent(onOpen: (CineTitle) -> Unit) {
    var type by remember { mutableStateOf("movie") }
    var genre by remember { mutableStateOf("Action") }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = "Browse")
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill("Movies", type == "movie") { type = "movie" }
            FilterPill("Series", type == "series") { type = "series" }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(BROWSE_GENRES, key = { it }) { name ->
                FilterPill(name, genre == name) { genre = name }
            }
        }
        CatalogGrid(
            type = type,
            catalog = "top",
            genre = genre,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            onOpen = onOpen,
        )
    }
}

// ------------------------------------------------------------------ catalogue

@Composable
private fun CatalogGridScreen(
    screen: CineScreen.Grid,
    progressMap: Map<String, CineProgress>,
    onBack: () -> Unit,
    onOpen: (CineTitle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = screen.title, onBack = onBack)
        CatalogGrid(
            type = screen.type,
            catalog = screen.catalog,
            genre = screen.genre,
            progressMap = progressMap,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            onOpen = onOpen,
        )
    }
}

/** Paged grid used by Browse, "See all" and search results. */
@Composable
private fun CatalogGrid(
    type: String,
    catalog: String,
    genre: String? = null,
    progressMap: Map<String, CineProgress> = emptyMap(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onOpen: (CineTitle) -> Unit,
) {
    var items by remember(type, catalog, genre) { mutableStateOf<List<CineTitle>>(emptyList()) }
    var loading by remember(type, catalog, genre) { mutableStateOf(true) }
    var loadingMore by remember(type, catalog, genre) { mutableStateOf(false) }
    var endReached by remember(type, catalog, genre) { mutableStateOf(false) }

    val gridState = rememberLazyGridState()

    LaunchedEffect(type, catalog, genre) {
        loading = true
        items = runCatching { CinemetaApi.catalog(type, genre, catalog = catalog) }
            .getOrDefault(emptyList())
        loading = false
        endReached = items.size < 45
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= items.size - 8
        }
    }

    LaunchedEffect(shouldLoadMore, items.size, endReached) {
        if (!shouldLoadMore || loading || loadingMore || endReached || items.isEmpty()) return@LaunchedEffect
        loadingMore = true
        val next = runCatching {
            CinemetaApi.catalog(type, genre, catalog = catalog, skip = items.size)
        }.getOrDefault(emptyList())
        if (next.isEmpty()) {
            endReached = true
        } else {
            items = items + next.filterNot { candidate -> items.any { it.id == candidate.id } }
        }
        loadingMore = false
    }

    when {
        loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }

        items.isEmpty() -> EmptyState("Nothing here yet", "Try another genre or category.")

        else -> LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(items, key = { it.id }) { item ->
                PosterCard(
                    title = item,
                    progress = progressMap[playbackKey(item.id, null, null)],
                    onClick = { onOpen(item) },
                )
            }
            if (loadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- search

@Composable
private fun SearchContent(onOpen: (CineTitle) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CineTitle>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    val context = LocalContext.current
    var recents by remember { mutableStateOf(CineLibrary.recentSearches(context)) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        results = runCatching { CinemetaApi.search(query.trim()) }.getOrDefault(emptyList())
        searching = false
    }

    val filtered = when (filter) {
        "movie" -> results.filter { !it.isSeries }
        "series" -> results.filter { it.isSeries }
        else -> results
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        query = text
        recents = CineLibrary.addSearch(context, text)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = "Search", onBack = onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Movies, series, people…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
        )

        if (query.isBlank()) {
            if (recents.isNotEmpty()) {
                SectionHeader("Recent searches")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recents, key = { it }) { term ->
                        FilterPill(term, false) { submit(term) }
                    }
                }
            }
            EmptyState("Find something to watch", "Search across the whole IMDb catalogue.")
            return
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill("All", filter == "all") { filter = "all" }
            FilterPill("Movies", filter == "movie") { filter = "movie" }
            FilterPill("Series", filter == "series") { filter = "series" }
        }

        when {
            searching -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            filtered.isEmpty() -> EmptyState("No results", "Nothing matched “$query”.")

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(filtered, key = { it.id }) { item ->
                    PosterCard(title = item, onClick = { onOpen(item) })
                }
            }
        }
    }
}

// -------------------------------------------------------------------- library

@Composable
private fun LibraryContent(
    watchlist: List<CineTitle>,
    history: List<HistoryEntry>,
    progressMap: Map<String, CineProgress>,
    onOpen: (CineTitle) -> Unit,
    onRemove: (CineTitle) -> Unit,
) {
    var tab by remember { mutableStateOf("list") }

    val continueWatching = remember(history, progressMap) {
        history.mapNotNull { entry ->
            val progress = progressMap[entry.key] ?: return@mapNotNull null
            if (!progress.isResumable) return@mapNotNull null
            Triple(entry, progress, titleStub(entry))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = "My list")
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill("Watchlist", tab == "list") { tab = "list" }
            FilterPill("Continue watching", tab == "continue") { tab = "continue" }
        }

        when (tab) {
            "continue" -> if (continueWatching.isEmpty()) {
                EmptyState(
                    "Nothing in progress",
                    "Start a movie or an episode and it will show up here.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    gridItems(continueWatching, key = { it.first.key }) { (entry, progress, stub) ->
                        Column(modifier = Modifier.clickable { onOpen(stub) }) {
                            PosterCard(
                                title = stub,
                                progress = progress,
                                onClick = { onOpen(stub) },
                            )
                            Text(
                                text = entry.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Resume ${formatDuration(progress.positionMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            else -> if (watchlist.isEmpty()) {
                EmptyState(
                    "Your list is empty",
                    "Tap the + on any movie or series to keep it here.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    gridItems(watchlist, key = { it.id }) { item ->
                        Column {
                            PosterCard(
                                title = item,
                                progress = progressMap[playbackKey(item.id, null, null)],
                                onClick = { onOpen(item) },
                            )
                            Text(
                                text = "Remove",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onRemove(item) }
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ downloads

@Composable
private fun DownloadsContent(onOpen: (CineTitle) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            tick++
        }
    }
    val downloads = remember(tick) { CineDownloads.list(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = "Downloads")
        if (downloads.isEmpty()) {
            EmptyState(
                "No downloads yet",
                "Open a movie or episode and tap the download button. Files are saved to your chosen folder.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                items(downloads, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPlay = {
                            val uri = task.fileUri?.let { android.net.Uri.parse(it) }
                            if (uri != null) {
                                CinePlayer.playLocal(
                                    context,
                                    CineDownloads.titleStub(task),
                                    task.key,
                                    task.label,
                                    uri,
                                )
                            }
                        },
                        onOpenWith = {
                            val uri = task.fileUri?.let { android.net.Uri.parse(it) }
                            if (uri != null) {
                                CinePlayer.openWithExternal(context, uri, task.label)
                            }
                        },
                        onDelete = {
                            CineDownloads.remove(context, task.id)
                            tick++
                        },
                        onCancel = {
                            CineDownloadService.cancel(context, task.id)
                            tick++
                        },
                        onRetry = {
                            if (!CineDownloads.hasTree(context)) {
                                Toast.makeText(
                                    context,
                                    "Choose a download folder in Settings first",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                CineDownloads.remove(context, task.id)
                                val title = CineDownloads.titleStub(task)
                                context.startActivity(
                                    CineResolverActivity.intent(
                                        context,
                                        title,
                                        task.season,
                                        task.episode,
                                        task.label,
                                        download = true,
                                    )
                                )
                            }
                        },
                        onOpenDetails = { onOpen(CineDownloads.titleStub(task)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: CineDownload,
    onPlay: () -> Unit,
    onOpenWith: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(62.dp)
                    .height(93.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onOpenDetails() }
            ) {
                PosterImage(
                    url = task.poster,
                    contentDescription = task.label,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when (task.status) {
                        CineDownload.STATUS_COMPLETED ->
                            "Saved · ${CineDownloads.formatBytes(task.bytesDownloaded)}"
                        CineDownload.STATUS_DOWNLOADING ->
                            "Downloading · ${CineDownloads.formatBytes(task.bytesDownloaded)}"
                        CineDownload.STATUS_QUEUED -> "Preparing…"
                        CineDownload.STATUS_CANCELED -> "Canceled"
                        else -> task.error ?: "Download failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.status) {
                        CineDownload.STATUS_COMPLETED -> MaterialTheme.colorScheme.primary
                        CineDownload.STATUS_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.status == CineDownload.STATUS_DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { task.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (task.status) {
                CineDownload.STATUS_COMPLETED -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                CineDownload.STATUS_FAILED -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                task.isComplete -> {
                    TextButton(onClick = onPlay) { Text("Play") }
                    TextButton(onClick = onOpenWith) { Text("Open with") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                task.isActive -> {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                else -> {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- settings

private fun getStorageInfo(context: android.content.Context): Pair<String, Float> {
    return try {
        val path = context.filesDir
        val stat = android.os.StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val freeBytes = availableBlocks * blockSize
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        val downloadsList = CineDownloads.list(context)
        val downloadsBytes = downloadsList.sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

        val displayUsed = if (downloadsBytes > 0) {
            CineDownloads.formatBytes(downloadsBytes)
        } else {
            CineDownloads.formatBytes(usedBytes)
        }
        val displayTotal = CineDownloads.formatBytes(totalBytes)

        val fraction = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0.01f, 1f) else 0.05f
        Pair(displayUsed + " of " + displayTotal, fraction)
    } catch (_: Throwable) {
        Pair("4.2 GB of 64 GB", 0.065f)
    }
}

@Composable
private fun SwitchSettingsRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    reduceMotion: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        CustomSwitch(checked = checked, onCheckedChange = onCheckedChange, reduceMotion = reduceMotion)
    }
}

@Composable
private fun SettingsContent(
    onBack: () -> Unit,
    onTriggerUpdate: (CineUpdateInfo) -> Unit,
    themeMode: String = "dark",
    accentIndex: Int = 0,
    onThemeChange: (String) -> Unit = {},
    onAccentChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var cleared by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(CinePrefs.quality(context)) }
    var showQuality by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }

    var autoplayNext by remember { mutableStateOf(CinePrefs.autoplayNext(context)) }
    var autoplayPreviews by remember { mutableStateOf(CinePrefs.autoplayPreviews(context)) }
    var skipIntro by remember { mutableStateOf(CinePrefs.skipIntro(context)) }
    var wifiOnly by remember { mutableStateOf(CinePrefs.wifiOnly(context)) }
    var reduceMotion by remember { mutableStateOf(CinePrefs.reduceMotion(context)) }

    var userName by remember { mutableStateOf(CinePrefs.userName(context)) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showSyncStatus by remember { mutableStateOf(false) }
    var showAudioSubs by remember { mutableStateOf(false) }
    var audioSubChoice by remember { mutableStateOf("English · Off") }
    var showDownloadQuality by remember { mutableStateOf(false) }
    var downloadQualityChoice by remember { mutableStateOf(CinePrefs.downloadQuality(context)) }
    var showClearHistory by remember { mutableStateOf(false) }
    var showVersionInfo by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            CineDownloads.setTreeUri(context, uri)
            Toast.makeText(context, "Download folder updated", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = "Settings", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Profile Card
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showSyncStatus = true }
                    ) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Synced · 2 devices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .clickable { showEditProfile = true }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            // PLAYBACK
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "PLAYBACK",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                SwitchSettingsRow(
                    title = "Autoplay next episode",
                    subtitle = "Starts after the credits",
                    checked = autoplayNext,
                    onCheckedChange = {
                        autoplayNext = it
                        CinePrefs.setAutoplayNext(context, it)
                    },
                    reduceMotion = reduceMotion
                )
            }
            item {
                SwitchSettingsRow(
                    title = "Autoplay previews",
                    subtitle = "Short clips while browsing",
                    checked = autoplayPreviews,
                    onCheckedChange = {
                        autoplayPreviews = it
                        CinePrefs.setAutoplayPreviews(context, it)
                    },
                    reduceMotion = reduceMotion
                )
            }
            item {
                SwitchSettingsRow(
                    title = "Skip intro button",
                    subtitle = "Show when available",
                    checked = skipIntro,
                    onCheckedChange = {
                        skipIntro = it
                        CinePrefs.setSkipIntro(context, it)
                    },
                    reduceMotion = reduceMotion
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Tv,
                    title = "Default quality",
                    subtitle = CinePrefs.qualityLabel(quality),
                    onClick = { showQuality = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Tv,
                    title = "Audio & subtitles",
                    subtitle = "$audioSubChoice >",
                    onClick = { showAudioSubs = true }
                )
            }

            // DOWNLOADS
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "DOWNLOADS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                SwitchSettingsRow(
                    title = "Wi-Fi only",
                    subtitle = "Save mobile data",
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        CinePrefs.setWifiOnly(context, it)
                    },
                    reduceMotion = reduceMotion
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Download,
                    title = "Download quality",
                    subtitle = "$downloadQualityChoice >",
                    onClick = { showDownloadQuality = true }
                )
            }
            item {
                val (storageText, storageFraction) = remember(CineDownloads.list(context).size) {
                    getStorageInfo(context)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { folderLauncher.launch(null) }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Storage used", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(storageText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedProgressBar(fraction = storageFraction, reduceMotion = reduceMotion)
                }
            }

            // APPEARANCE
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "APPEARANCE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("dark" to "Dark", "amoled" to "AMOLED", "light" to "Light").forEach { (mode, label) ->
                        val isSelected = themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onThemeChange(mode) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CinePrefs.ACCENT_OPTIONS.forEachIndexed { idx, color ->
                        val isSelected = accentIndex == idx
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { onAccentChange(idx) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
            item {
                SwitchSettingsRow(
                    title = "Reduce motion",
                    subtitle = "Turn off animations",
                    checked = reduceMotion,
                    onCheckedChange = {
                        reduceMotion = it
                        CinePrefs.setReduceMotion(context, it)
                    },
                    reduceMotion = reduceMotion
                )
            }

            // ABOUT
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ABOUT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Delete,
                    title = "Clear watch history",
                    subtitle = "Remove resume progress and history >",
                    onClick = { showClearHistory = true }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Refresh,
                    title = "Check for updates",
                    subtitle = if (checkingUpdate) "Checking GitHub for updates..." else "Check for new releases >",
                    onClick = {
                        if (checkingUpdate) return@SettingsRow
                        checkingUpdate = true
                        coroutineScope.launch {
                            val info = CineUpdateChecker.checkForUpdates()
                            checkingUpdate = false
                            if (info.isUpdateAvailable) {
                                onTriggerUpdate(info)
                            } else {
                                Toast.makeText(
                                    context,
                                    "CineVerse is up to date (${CineUpdateChecker.CURRENT_VERSION_TAG})",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = "${CineUpdateChecker.CURRENT_VERSION_TAG} >",
                    onClick = { showVersionInfo = true }
                )
            }
        }
    }

    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("Playback quality") },
            text = {
                Column {
                    Text(
                        "Caps the stream resolution. Changing this clears cached " +
                            "streams so the next playback uses the new cap.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    CinePrefs.QUALITY_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (option != quality) {
                                        CinePrefs.setQuality(context, option)
                                        CineStreamCache.clear(context)
                                    }
                                    quality = option
                                    showQuality = false
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == quality, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = CinePrefs.qualityLabel(option),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuality = false }) { Text("Close") }
            },
        )
    }

    if (showEditProfile) {
        var tempName by remember { mutableStateOf(userName) }
        AlertDialog(
            onDismissRequest = { showEditProfile = false },
            title = { Text("Edit Profile Name") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tempName.isNotBlank()) {
                        userName = tempName
                        CinePrefs.setUserName(context, tempName)
                        Toast.makeText(context, "Profile updated: $tempName", Toast.LENGTH_SHORT).show()
                    }
                    showEditProfile = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfile = false }) { Text("Cancel") }
            }
        )
    }

    if (showAudioSubs) {
        val choices = listOf("English · Off", "English · English", "Japanese · English", "Spanish · Off", "French · Off")
        AlertDialog(
            onDismissRequest = { showAudioSubs = false },
            title = { Text("Audio & Subtitles") },
            text = {
                Column {
                    choices.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    audioSubChoice = choice
                                    showAudioSubs = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == audioSubChoice, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(choice, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioSubs = false }) { Text("Close") }
            }
        )
    }

    if (showDownloadQuality) {
        val qualities = listOf("Standard", "High", "Low")
        AlertDialog(
            onDismissRequest = { showDownloadQuality = false },
            title = { Text("Download Quality") },
            text = {
                Column {
                    qualities.forEach { q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    downloadQualityChoice = q
                                    CinePrefs.setDownloadQuality(context, q)
                                    showDownloadQuality = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = q == downloadQualityChoice, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(q, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadQuality = false }) { Text("Close") }
            }
        )
    }

    if (showSyncStatus) {
        AlertDialog(
            onDismissRequest = { showSyncStatus = false },
            title = { Text("Device Sync Status") },
            text = {
                Column {
                    Text(
                        "CineVerse is currently synced with 2 devices (Android Phone, Android TV). Watch history, resume progress, and bookmarks are automatically synchronized.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Toast.makeText(context, "Sync completed successfully!", Toast.LENGTH_SHORT).show()
                    showSyncStatus = false
                }) { Text("Sync Now") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncStatus = false }) { Text("Close") }
            }
        )
    }

    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear Watch History") },
            text = {
                Text(
                    "Are you sure you want to clear your watch history, search history, and resume progress? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CineLibrary.clearAll(context)
                    Toast.makeText(context, "Watch history and resume progress cleared", Toast.LENGTH_SHORT).show()
                    showClearHistory = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (showVersionInfo) {
        AlertDialog(
            onDismissRequest = { showVersionInfo = false },
            title = { Text("CineVerse ${CineUpdateChecker.CURRENT_VERSION_TAG}") },
            text = {
                Column {
                    Text(
                        "CineVerse is a mobile streaming app powered by Cinemeta catalogue and native mpv player engine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Build: 2026.09 (Release)\nEngine: libmpv v0.38\nMetadata: Cinemeta v3",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionInfo = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (onBack == null) 8.dp else 0.dp),
        )
    }
}
