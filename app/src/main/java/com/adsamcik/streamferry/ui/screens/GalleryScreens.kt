package com.adsamcik.streamferry.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.adsamcik.streamferry.core.resume.ResumePolicy
import com.adsamcik.streamferry.data.download.DownloadFormat
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(state: AppUiState, viewModel: MainViewModel, compact: Boolean = false) {
    val searchActive = state.searchQuery.isNotBlank()
    val atRoot = state.folderStack.isEmpty()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val entries = when {
        searchActive -> state.searchResults
        atRoot -> state.libraries
        else -> state.items
    }

    val gridState = rememberSaveable(
        state.activeSourceId,
        state.currentFolder?.id,
        state.searchQuery,
        saver = LazyGridState.Saver,
    ) { LazyGridState() }
    val scope = rememberCoroutineScope()
    // First grid index for each A–Z/# section, in display (server SortName) order.
    val firstIndexBySection = remember(entries) {
        val map = LinkedHashMap<String, Int>()
        entries.forEachIndexed { i, item -> map.getOrPut(sectionKey(item.title)) { i } }
        map
    }
    val sections = remember(firstIndexBySection) {
        firstIndexBySection.keys.sortedWith(compareBy({ it != "#" }, { it })) // "#" first, then A–Z
    }
    var indexedSection by remember(entries) { mutableStateOf<String?>(null) }
    var indexScrubbing by remember(entries) { mutableStateOf(false) }
    val isLocalSource = state.activeSourceId == MediaSourceIds.LOCAL
    val sources = viewModel.sources
    val activeSourceName = sources.firstOrNull { it.first == state.activeSourceId }?.second ?: "Library"
    val searchLabel = if (isLocalSource) "Search this device" else "Search $activeSourceName"
    val needsJellyfinLogin = !isLocalSource && !state.loggedIn
    val showingSeasons = !searchActive && !atRoot && (
        state.currentFolder?.type.equals("Series", ignoreCase = true) ||
            (entries.isNotEmpty() && entries.all { it.type.equals("Season", ignoreCase = true) })
        )

    if (atRoot && !searchActive) {
        LibraryHome(
            state = state,
            viewModel = viewModel,
            entries = entries,
            isLocalSource = isLocalSource,
            needsJellyfinLogin = needsJellyfinLogin,
            compact = compact,
            searchOpen = searchOpen,
            onOpenSearch = { searchOpen = true },
            onCloseSearch = {
                viewModel.clearSearch()
                searchOpen = false
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (compact) {
            PhoneGalleryHeader(
                title = if (searchActive) "Search results" else (state.currentFolder?.title ?: "Library"),
                contextLabel = activeSourceName,
                onBack = {
                    if (searchActive) viewModel.clearSearch() else viewModel.popFolder()
                    searchOpen = false
                },
                onSearch = { searchOpen = true },
                searchVisible = searchActive || searchOpen,
            )
            if (searchActive || searchOpen) {
                LibrarySearchField(
                    state = state,
                    viewModel = viewModel,
                    compact = true,
                    label = searchLabel,
                    onClose = {
                        viewModel.clearSearch()
                        searchOpen = false
                    },
                )
            }
        } else {
            LibrarySearchField(state, viewModel, label = searchLabel)
            LibrarySectionTitle(
                if (searchActive) "Search results" else (state.currentFolder?.title ?: "Library"),
            )
        }
        when {
            needsJellyfinLogin -> JellyfinLoginPrompt(viewModel)
            state.galleryLoading || state.searching -> CenteredProgress("Loading…")
            entries.isEmpty() && searchActive -> Text("No results for \"${state.searchQuery}\".")
            entries.isEmpty() -> EmptyLibraryPrompt(state, viewModel, isLocalSource)
            showingSeasons -> SeasonList(
                seasons = entries,
                compact = compact,
                posterUrlFor = { viewModel.posterUrl(it, POSTER_CARD_WIDTH_PX) },
                onSeasonClick = { viewModel.onItemClicked(it) },
            )
            else -> if (compact) {
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(entries, key = { it.id }) { item ->
                            MediaCard(
                                item = item,
                                posterUrl = viewModel.posterUrl(item, POSTER_CARD_WIDTH_PX),
                                localThumbnailUri = item.localThumbnailUri(),
                                compact = true,
                                highlighted = indexScrubbing && indexedSection == sectionKey(item.title),
                                onClick = { viewModel.onItemClicked(item) },
                            )
                        }
                    }
                    if (entries.size >= INDEX_MIN_ITEMS && sections.size > 1) {
                        AlphabetIndexBar(
                            sections = sections,
                            compact = true,
                            selectedSection = indexedSection,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            onScrubbingChange = { indexScrubbing = it },
                            onSection = { section ->
                                indexedSection = section
                                firstIndexBySection[section]?.let { index ->
                                    scope.launch { gridState.scrollToItem(index) }
                                }
                            },
                        )
                        if (indexScrubbing) {
                            indexedSection?.let { section ->
                                SectionScrubberBubble(
                                    section = section,
                                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 40.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        items(entries, key = { it.id }) { item ->
                            MediaCard(
                                item = item,
                                posterUrl = viewModel.posterUrl(item, POSTER_CARD_WIDTH_PX),
                                localThumbnailUri = item.localThumbnailUri(),
                                highlighted = indexScrubbing && indexedSection == sectionKey(item.title),
                                onClick = { viewModel.onItemClicked(item) },
                            )
                        }
                    }
                    if (entries.size >= INDEX_MIN_ITEMS && sections.size > 1) {
                        AlphabetIndexBar(
                            sections = sections,
                            selectedSection = indexedSection,
                            onScrubbingChange = { indexScrubbing = it },
                            onSection = { section ->
                                indexedSection = section
                                firstIndexBySection[section]?.let { scope.launch { gridState.scrollToItem(it) } }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonList(
    seasons: List<MediaItem>,
    compact: Boolean,
    posterUrlFor: (MediaItem) -> String?,
    onSeasonClick: (MediaItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
    ) {
        item(key = "season-list-heading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "Seasons",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${seasons.size} ${if (seasons.size == 1) "season" else "seasons"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(seasons, key = { _, season -> season.id }) { index, season ->
            AnimatedSeasonEntry(
                season = season,
                index = index,
                compact = compact,
                posterUrl = posterUrlFor(season),
                onClick = { onSeasonClick(season) },
            )
        }
    }
}

@Composable
private fun AnimatedSeasonEntry(
    season: MediaItem,
    index: Int,
    compact: Boolean,
    posterUrl: String?,
    onClick: () -> Unit,
) {
    var visible by remember(season.id) { mutableStateOf(false) }
    LaunchedEffect(season.id) {
        delay(index.coerceAtMost(8) * SEASON_STAGGER_MS)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(SEASON_FADE_MS)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it / 3 },
            ),
    ) {
        SeasonListCard(season, posterUrl, compact, onClick)
    }
}

@Composable
private fun SeasonListCard(
    season: MediaItem,
    posterUrl: String?,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "season-card-press",
    )
    val unwatched = season.unplayedItemCount ?: 0
    val status = when {
        season.played -> "All watched"
        unwatched > 0 -> "$unwatched unwatched"
        else -> "Browse episodes"
    }

    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(if (compact) 24.dp else 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (season.played) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (pressed) 1.dp else 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Surface(
                modifier = Modifier.width(if (compact) 72.dp else 88.dp).aspectRatio(2f / 3f),
                shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        season.title.take(2).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (posterUrl != null) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    WatchStateOverlay(season)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    season.title,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (season.played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (season.played || unwatched > 0) FontWeight.SemiBold else FontWeight.Normal,
                )
                season.year?.let { year ->
                    Text(
                        year.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Browse ${season.title}")
                }
            }
        }
    }
}

private const val SEASON_STAGGER_MS = 45L
private const val SEASON_FADE_MS = 220

/**
 * Root library surface. Everything participates in one vertical scroll, with the actual libraries
 * first. Secondary modules no longer reserve most of the viewport above a separately scrolling grid.
 */
@Composable
private fun LibraryHome(
    state: AppUiState,
    viewModel: MainViewModel,
    entries: List<MediaItem>,
    isLocalSource: Boolean,
    needsJellyfinLogin: Boolean,
    compact: Boolean,
    searchOpen: Boolean,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    LazyVerticalGrid(
        columns = if (compact) GridCells.Fixed(2) else GridCells.Adaptive(minSize = 150.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compact) {
                    PhoneGalleryHeader(
                        title = "Library",
                        onBack = null,
                        onSearch = onOpenSearch,
                        searchVisible = searchOpen,
                    )
                }
                SourceSwitcher(viewModel.sources, state.activeSourceId, viewModel::selectSource)
                if (!compact || searchOpen) {
                    LibrarySearchField(
                        state = state,
                        viewModel = viewModel,
                        compact = compact,
                        label = if (isLocalSource) {
                            "Search this device"
                        } else {
                            val sourceName = viewModel.sources
                                .firstOrNull { it.first == state.activeSourceId }
                                ?.second
                                ?: "library"
                            "Search $sourceName"
                        },
                        onClose = if (compact) onCloseSearch else null,
                    )
                }
                if (isLocalSource) {
                    LocalAccessActions(viewModel)
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibrarySectionTitle(if (isLocalSource) "On this device" else "Your libraries")
        }

        when {
            needsJellyfinLogin -> item(span = { GridItemSpan(maxLineSpan) }) {
                JellyfinLoginPrompt(viewModel)
            }
            state.galleryLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                CenteredProgress("Loading…")
            }
            entries.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyLibraryPrompt(state, viewModel, isLocalSource)
            }
            else -> items(entries, key = { it.id }) { item ->
                if (compact && item.isFolder) {
                    LibraryTile(
                        item = item,
                        posterUrl = viewModel.posterUrl(item, POSTER_CARD_WIDTH_PX),
                        onClick = { viewModel.onItemClicked(item) },
                    )
                } else {
                    MediaCard(
                        item = item,
                        posterUrl = viewModel.posterUrl(item, POSTER_CARD_WIDTH_PX),
                        localThumbnailUri = item.localThumbnailUri(),
                        compact = compact,
                        onClick = { viewModel.onItemClicked(item) },
                    )
                }
            }
        }

        state.smartResume?.let { smartResume ->
            item(key = "smart-resume", span = { GridItemSpan(maxLineSpan) }) {
                SmartResumeCard(
                    smartResume,
                    viewModel::resumeSmartResume,
                    viewModel::dismissSmartResume,
                )
            }
        }
        if (state.continueWatching.isNotEmpty()) {
            item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                ContinueWatchingRow(
                    items = state.continueWatching,
                    posterUrlFor = { viewModel.posterUrl(it, POSTER_CARD_WIDTH_PX) },
                    onClick = { viewModel.onItemClicked(it) },
                )
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    state: AppUiState,
    viewModel: MainViewModel,
    compact: Boolean = false,
    label: String = "Search library",
    onClose: (() -> Unit)? = null,
) {
    val searchActive = state.searchQuery.isNotBlank()
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = viewModel::onSearchQueryChanged,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        trailingIcon = {
            when {
                compact && onClose != null -> IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close search")
                }
                searchActive -> TextButton(onClick = viewModel::clearSearch) { Text("Clear") }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = if (compact) 8.dp else 0.dp),
    )
}

@Composable
private fun PhoneGalleryHeader(
    title: String,
    contextLabel: String? = null,
    onBack: (() -> Unit)?,
    onSearch: () -> Unit,
    searchVisible: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            contextLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!searchVisible) {
            FilledTonalIconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = "Search library")
            }
        }
    }
}

@Composable
private fun LibrarySectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun JellyfinLoginPrompt(viewModel: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect to your Jellyfin server to browse it here, or switch to \"On this device\" above.")
        Button(onClick = { viewModel.navigate(Route.SERVER_SETUP) }) { Text("Connect to Jellyfin") }
    }
}

@Composable
private fun EmptyLibraryPrompt(state: AppUiState, viewModel: MainViewModel, isLocalSource: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLocalSource) {
            Text("No on-device videos yet. Add a folder or files above, or allow access to all your videos.")
        } else {
            Text(if (state.errorMessage != null) "Couldn't load this view." else "Nothing to show here yet.")
            Button(onClick = { viewModel.refreshGallery() }) { Text("Retry") }
        }
    }
}

private fun MediaItem.localThumbnailUri(): String? =
    if (sourceId == MediaSourceIds.LOCAL && !isFolder) id else null

/** Expressive root-level source selector. Source changes stay out of nested folder navigation. */
@Composable
private fun SourceSwitcher(sources: List<Pair<String, String>>, activeId: String, onSelect: (String) -> Unit) {
    if (sources.size < 2) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Browse from",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Choose where your library and search results come from",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sources.size <= 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEach { (id, name) ->
                    SourceOption(
                        id = id,
                        name = name,
                        selected = id == activeId,
                        onClick = { onSelect(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.first }) { (id, name) ->
                    SourceOption(
                        id = id,
                        name = name,
                        selected = id == activeId,
                        onClick = { onSelect(id) },
                        modifier = Modifier.width(220.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceOption(
    id: String,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "source-option-press",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(220),
        label = "source-option-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "source-option-content",
    )
    val description = when (id) {
        MediaSourceIds.JELLYFIN -> "Stream from your server"
        MediaSourceIds.LOCAL -> "Videos stored on this phone"
        else -> "Browse this media source"
    }
    val icon = when (id) {
        MediaSourceIds.JELLYFIN -> Icons.Rounded.Dns
        MediaSourceIds.LOCAL -> Icons.Rounded.PhoneAndroid
        else -> Icons.Rounded.VideoLibrary
    }

    Card(
        onClick = { if (!selected) onClick() },
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 104.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.weight(1f))
                AnimatedVisibility(visible = selected) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Elective local-access actions: pick a folder or files via SAF (no permission), or grant the media
 * permission for the full "all device videos" gallery. The picked URIs / grant are handed to the
 * ViewModel which persists them and refreshes.
 */
@Composable
private fun LocalAccessActions(viewModel: MainViewModel) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onLocalFolderPicked(it)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.onLocalFilesPicked(it)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onMediaPermissionResult(it)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
        ) { Text("Add a folder") }
        OutlinedButton(
            onClick = { filesPicker.launch(arrayOf("video/*")) },
        ) { Text("Add individual videos") }
        if (!viewModel.mediaPermissionGranted()) {
            OutlinedButton(
                onClick = { permissionLauncher.launch(viewModel.readMediaVideoPermission) },
            ) { Text("Allow access to all videos") }
        }
    }
}

/** Vertical A–Z/# fast-scroll scrubber: tap or drag a letter to jump the grid to that section. */
@Composable
private fun AlphabetIndexBar(
    sections: List<String>,
    onSection: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectedSection: String? = null,
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    if (sections.isEmpty()) return
    val currentOnSection by rememberUpdatedState(onSection)
    val currentOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    fun sectionAt(y: Float, height: Int): String {
        if (height <= 0) return sections.first()
        val idx = ((y / height) * sections.size).toInt().coerceIn(0, sections.lastIndex)
        return sections[idx]
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (compact) 28.dp else 48.dp)
            .semantics {
                customActions = sections.map { section ->
                    CustomAccessibilityAction("Jump to $section") {
                        currentOnSection(section)
                        true
                    }
                }
            }
            .pointerInput(sections) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnScrubbingChange(true)
                    currentOnSection(sectionAt(down.position.y, size.height))
                    try {
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            pressed = change.pressed
                            if (pressed) {
                                currentOnSection(sectionAt(change.position.y, size.height))
                                change.consume()
                            }
                        }
                    } finally {
                        currentOnScrubbingChange(false)
                    }
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sections.forEach { s ->
            val selected = s == selectedSection
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                modifier = Modifier.size(if (compact) 16.dp else 22.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        s,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionScrubberBubble(section: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(64.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(section, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
}

private val SECTION_ARTICLES = listOf("The ", "A ", "An ")
private const val INDEX_MIN_ITEMS = 15

/** A–Z section key for a title (leading article stripped to match Jellyfin's SortName); digits/other -> "#". */
private fun sectionKey(title: String): String {
    var t = title.trimStart()
    for (article in SECTION_ARTICLES) {
        if (t.length > article.length && t.startsWith(article, ignoreCase = true)) {
            t = t.substring(article.length)
            break
        }
    }
    val c = t.firstOrNull()?.uppercaseChar() ?: return "#"
    return when {
        c in 'A'..'Z' -> c.toString()
        else -> "#"
    }
}

private const val THUMB_SIZE_PX = 512

/** Bounded, access-ordered LRU of decoded on-device video thumbnails, keyed by content URI. */
private object LocalThumbnailCache {
    private const val MAX_ENTRIES = 64
    private val lru = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > MAX_ENTRIES
    }
    @Synchronized fun get(key: String): Bitmap? = lru[key]
    @Synchronized fun put(key: String, value: Bitmap) { lru[key] = value }
}

/**
 * A thumbnail frame for an on-device video, loaded off-thread via `ContentResolver.loadThumbnail` and
 * cached. Renders nothing while loading / on failure, leaving the tonal initials slot visible behind it.
 */
@Composable
private fun LocalThumbnail(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(LocalThumbnailCache.get(uriString), uriString) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(uriString), Size(THUMB_SIZE_PX, THUMB_SIZE_PX), null)
            }.getOrNull()?.also { LocalThumbnailCache.put(uriString, it) }
        }
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<MediaItem>,
    posterUrlFor: (MediaItem) -> String?,
    onClick: (MediaItem) -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Continue watching",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                ContinueWatchingCard(item, posterUrlFor(item)) { onClick(item) }
            }
        }
    }
}

/** Compact poster card for the "Continue watching" row: poster + resume progress bar + resume time. */
@Composable
private fun ContinueWatchingCard(item: MediaItem, posterUrl: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(CONTINUE_CARD_WIDTH.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = item.title.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (posterUrl != null) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                watchProgressFraction(item)?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                item.subtitle ?: item.resumePositionSeconds?.let { "Resume ${formatClock(it)}" } ?: " ",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * In-progress watch fraction (0..1): the server's PlayedPercentage when present, else the resume
 * position over the runtime. Null when the item isn't partially watched or the runtime is unknown.
 */
private fun watchProgressFraction(item: MediaItem): Float? =
    ResumePolicy.watchedFraction(item.playedPercentage, item.resumePositionSeconds, item.runtimeSeconds)

/** A short watch-state line for the detail hero: watched, N unwatched (folders), or null. */
private fun watchStateLabel(item: MediaItem): String? {
    val unwatched = item.unplayedItemCount ?: 0
    return when {
        item.played -> "\u2713 Watched"
        unwatched > 0 -> "$unwatched unwatched"
        else -> null
    }
}

@Composable
private fun LibraryTile(item: MediaItem, posterUrl: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (posterUrl != null) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    WatchStateOverlay(item)
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    posterUrl: String?,
    localThumbnailUri: String?,
    compact: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "media-card-container",
    )
    val primaryTextColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 4.dp else 0.dp),
    ) {
        Column(
            Modifier.padding(if (compact) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            // Tonal poster slot: the title initials show behind, and the real poster (if the item has art)
            // crossfades in over them, falling back to the initials while loading / on error / when absent.
            Surface(
                shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (posterUrl != null) {
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (localThumbnailUri != null) {
                        LocalThumbnail(localThumbnailUri, Modifier.fillMaxSize())
                    }
                    // Jellyfin watch state: a watched check, an unwatched-count badge, or a progress bar.
                    WatchStateOverlay(item)
                }
            }
            Text(
                item.title,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = primaryTextColor,
            )
            val secondary = item.subtitle ?: item.year?.toString()
            if (secondary != null) {
                Text(
                    secondary,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = secondaryTextColor,
                )
            }
        }
    }
}

/** Jellyfin watch-state overlay on a poster: a watched check, an unwatched-count badge, or a progress bar. */
@Composable
private fun BoxScope.WatchStateOverlay(item: MediaItem) {
    val unwatched = item.unplayedItemCount ?: 0
    when {
        item.played -> WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        unwatched > 0 -> CountBadge(unwatched, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
    if (!item.played) {
        watchProgressFraction(item)?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/** Small circular "watched" check badge. */
@Composable
private fun WatchedBadge(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Watched",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Small pill showing the number of unwatched items in a folder (series/season). */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun MediaDetailScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    onChooseTv: () -> Unit,
    compact: Boolean = false,
) {
    val media = state.selectedItem
    if (media == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No item selected.")
        }
        return
    }
    val download = state.downloadFor(media.id)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            item(key = "detail-hero") {
                DetailHero(
                    item = media,
                    posterUrl = viewModel.posterUrl(media, POSTER_DETAIL_WIDTH_PX),
                    compact = compact,
                )
            }
            media.overview?.takeIf(String::isNotBlank)?.let { overview ->
                item(key = "detail-about") {
                    DetailAboutCard(media.id, overview)
                }
            }
            if (media.sourceId != MediaSourceIds.LOCAL) {
                item(key = "detail-watch-state") {
                    WatchStateAction(
                        item = media,
                        onToggle = { viewModel.markWatched(media, !media.played) },
                    )
                }
                item(key = "detail-offline") {
                    OfflineDetailCard(
                        item = media,
                        download = download,
                        viewModel = viewModel,
                    )
                }
            }
        }

        DetailPlaybackDock(
            item = media,
            downloadCompleted = download?.completed == true,
            onPlay = {
                viewModel.clearDownloadSelection()
                onChooseTv()
            },
            onPlayOffline = {
                viewModel.prepareCastDownload(media.id)
                onChooseTv()
            },
        )
    }
}

@Composable
private fun DetailHero(item: MediaItem, posterUrl: String?, compact: Boolean) {
    ElevatedCard(
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 16.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DetailPoster(
                    posterUrl = posterUrl,
                    title = item.title,
                    modifier = Modifier.width(if (compact) 108.dp else 144.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (item.sourceId == MediaSourceIds.LOCAL) {
                                    Icons.Rounded.PhoneAndroid
                                } else {
                                    Icons.Rounded.Dns
                                },
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                if (item.sourceId == MediaSourceIds.LOCAL) "On this device" else "Jellyfin",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        item.title,
                        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = if (compact) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    watchStateLabel(item)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            DetailMetadataRow(item)

            watchProgressFraction(item)?.let { fraction ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Your progress",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        item.resumePositionSeconds?.let {
                            Text(
                                formatClock(it),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMetadataRow(item: MediaItem) {
    val labels = listOfNotNull(
        item.type?.takeIf(String::isNotBlank),
        item.year?.toString(),
        item.runtimeSeconds?.let(::formatRuntime),
    ).distinct()
    if (labels.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        labels.forEach { label ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailAboutCard(itemId: String, overview: String) {
    var expanded by rememberSaveable(itemId) { mutableStateOf(false) }
    val canExpand = overview.length > 220
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp).animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (canExpand) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Read more")
                }
            }
        }
    }
}

@Composable
private fun WatchStateAction(item: MediaItem, onToggle: () -> Unit) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.played) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (item.played) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (item.played) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (item.played) Icons.Rounded.RemoveDone else Icons.Rounded.Check,
                        contentDescription = null,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (item.played) "Watched" else "Mark as watched",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (item.played) "Tap to mark this as unwatched" else "Keep your Jellyfin history up to date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun OfflineDetailCard(
    item: MediaItem,
    download: com.adsamcik.streamferry.ui.state.DownloadUiItem?,
    viewModel: MainViewModel,
) {
    var selectedFormat by remember(item.id) { mutableStateOf<DownloadFormat>(DownloadFormat.Original) }
    var formatMenuExpanded by remember(item.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(21.dp))
                    }
                }
                Column {
                    Text(
                        "Offline copy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        if (download?.completed == true) "Ready when your server isn't" else "Take it with you",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    )
                }
            }

            when {
                download == null -> {
                    Text(
                        "Save a copy that can be played on a TV without reaching your Jellyfin server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    DownloadFormatPicker(
                        selectedFormat = selectedFormat,
                        expanded = formatMenuExpanded,
                        onExpandedChange = { formatMenuExpanded = it },
                        onFormatSelected = { selectedFormat = it },
                    )
                    Button(
                        onClick = { viewModel.downloadSelected(selectedFormat) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(19.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Download for offline")
                    }
                }
                download.completed -> {
                    Text(
                        "Downloaded and ready to play offline.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(
                        onClick = { viewModel.deleteDownload(item.id) },
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Delete offline copy") }
                }
                download.failed -> {
                    Text(download.statusText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    DownloadFormatPicker(
                        selectedFormat = selectedFormat,
                        expanded = formatMenuExpanded,
                        onExpandedChange = { formatMenuExpanded = it },
                        onFormatSelected = { selectedFormat = it },
                    )
                    OutlinedButton(
                        onClick = { viewModel.downloadSelected(selectedFormat) },
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Retry download") }
                }
                else -> {
                    Text(
                        download.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    download.fraction?.let {
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(7.dp))
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth().height(7.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancelDownload(item.id) },
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Cancel download") }
                }
            }
        }
    }
}

@Composable
private fun DownloadFormatPicker(
    selectedFormat: DownloadFormat,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFormatSelected: (DownloadFormat) -> Unit,
) {
    Box {
        OutlinedButton(onClick = { onExpandedChange(true) }, shape = RoundedCornerShape(18.dp)) {
            Text("Quality: ${selectedFormat.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DownloadFormat.PRESETS.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label) },
                    onClick = {
                        onFormatSelected(format)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailPlaybackDock(
    item: MediaItem,
    downloadCompleted: Boolean,
    onPlay: () -> Unit,
    onPlayOffline: () -> Unit,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "detail-play-press",
    )
    val resumeAt = item.resumePositionSeconds?.takeIf { it > 0L }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 5.dp,
        shadowElevation = 7.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            resumeAt?.let { position ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Ready to resume",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatClock(position),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPlay,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Icon(
                        if (resumeAt != null) Icons.Rounded.PlayArrow else Icons.Rounded.Cast,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        if (resumeAt != null) "  Resume on TV" else "  Play on TV",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                if (downloadCompleted) {
                    OutlinedButton(
                        onClick = onPlayOffline,
                        modifier = Modifier.height(60.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(19.dp))
                        Text("  Offline", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPoster(posterUrl: String?, title: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.aspectRatio(2f / 3f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                title.take(2).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val POSTER_CARD_WIDTH_PX = 360
private const val POSTER_DETAIL_WIDTH_PX = 320
private const val CONTINUE_CARD_WIDTH = 132

@Composable
internal fun CenteredProgress(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatRuntime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

internal fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------------------------------------------------------------------------------------------------
// Design-time @Preview composables (no ViewModel required); R8 strips them from the release build.
// ---------------------------------------------------------------------------------------------------

@Preview(name = "Media card", showBackground = true, widthDp = 200)
@Preview(name = "Media card · dark", showBackground = true, widthDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MediaCardPreview() {
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(12.dp).width(180.dp)) {
                MediaCard(
                    item = MediaItem(
                        id = "1",
                        title = "The Expanse",
                        year = 2015,
                        runtimeSeconds = 3_000,
                        overview = "A thriller set across a colonized solar system.",
                        resumePositionSeconds = null,
                        isFolder = false,
                        type = "Series",
                        subtitle = "Series · 6 seasons",
                    ),
                    posterUrl = null, // no network in previews -> shows the tonal initials fallback
                    localThumbnailUri = null,
                    onClick = {},
                )
            }
        }
    }
}
