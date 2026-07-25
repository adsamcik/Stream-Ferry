package com.adsamcik.streamferry.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
fun GalleryScreen(state: AppUiState, viewModel: MainViewModel) {
    val searchActive = state.searchQuery.isNotBlank()
    val atRoot = state.folderStack.isEmpty()
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

    Column(Modifier.fillMaxSize()) {
        val isLocalSource = state.activeSourceId == MediaSourceIds.LOCAL
        val needsJellyfinLogin = !isLocalSource && !state.loggedIn
        if (!searchActive) {
            SourceSwitcher(viewModel.sources, state.activeSourceId, viewModel::selectSource)
        }
        if (atRoot && !searchActive) {
            state.smartResume?.let { SmartResumeCard(it, viewModel::resumeSmartResume, viewModel::dismissSmartResume) }
        }
        if (isLocalSource && atRoot && !searchActive) {
            LocalAccessActions(viewModel)
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChanged,
            label = { Text("Search library") },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            trailingIcon = {
                if (searchActive) TextButton(onClick = viewModel::clearSearch) { Text("Clear") }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (atRoot && !searchActive && state.continueWatching.isNotEmpty()) {
            ContinueWatchingRow(
                items = state.continueWatching,
                posterUrlFor = { viewModel.posterUrl(it, POSTER_CARD_WIDTH_PX) },
                onClick = { viewModel.onItemClicked(it) },
            )
        }
        Text(
            text = if (searchActive) "Search results"
                else (state.currentFolder?.title ?: if (isLocalSource) "On this device" else "Your libraries"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            needsJellyfinLogin -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect to your Jellyfin server to browse it here, or switch to \"On this device\" above.")
                Button(onClick = { viewModel.navigate(Route.SERVER_SETUP) }) { Text("Connect to Jellyfin") }
            }
            state.galleryLoading || state.searching -> CenteredProgress("Loading…")
            entries.isEmpty() && searchActive -> Text("No results for \"${state.searchQuery}\".")
            entries.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLocalSource) {
                    Text("No on-device videos yet. Add a folder or files above, or allow access to all your videos.")
                } else {
                    Text(if (state.errorMessage != null) "Couldn't load this view." else "Nothing to show here yet.")
                    Button(onClick = { viewModel.refreshGallery() }) { Text("Retry") }
                }
            }
            else -> Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    items(entries, key = { it.id }) { item ->
                        val localThumb = if (item.sourceId == MediaSourceIds.LOCAL && !item.isFolder) item.id else null
                        MediaCard(item, viewModel.posterUrl(item, POSTER_CARD_WIDTH_PX), localThumb) { viewModel.onItemClicked(item) }
                    }
                }
                if (entries.size >= INDEX_MIN_ITEMS && sections.size > 1) {
                    AlphabetIndexBar(
                        sections = sections,
                        onSection = { s -> firstIndexBySection[s]?.let { idx -> scope.launch { gridState.scrollToItem(idx) } } },
                    )
                }
            }
        }
    }
}

/** Top-of-gallery source selector (Jellyfin / on-device). Hidden when there is only one source. */
@Composable
private fun SourceSwitcher(sources: List<Pair<String, String>>, activeId: String, onSelect: (String) -> Unit) {
    if (sources.size < 2) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        sources.forEach { (id, name) ->
            FilterChip(selected = id == activeId, onClick = { onSelect(id) }, label = { Text(name) })
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
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add a folder") }
        OutlinedButton(
            onClick = { filesPicker.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add individual videos") }
        if (!viewModel.mediaPermissionGranted()) {
            OutlinedButton(
                onClick = { permissionLauncher.launch(viewModel.readMediaVideoPermission) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow access to all videos") }
        }
    }
}

/** Vertical A–Z/# fast-scroll scrubber: tap or drag a letter to jump the grid to that section. */
@Composable
private fun AlphabetIndexBar(sections: List<String>, onSection: (String) -> Unit) {
    if (sections.isEmpty()) return
    val currentOnSection by rememberUpdatedState(onSection)
    fun sectionAt(y: Float, height: Int): String {
        if (height <= 0) return sections.first()
        val idx = ((y / height) * sections.size).toInt().coerceIn(0, sections.lastIndex)
        return sections[idx]
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(48.dp)
            .semantics {
                customActions = sections.map { section ->
                    CustomAccessibilityAction("Jump to $section") {
                        currentOnSection(section)
                        true
                    }
                }
            }
            .pointerInput(sections) {
                detectTapGestures { offset -> currentOnSection(sectionAt(offset.y, size.height)) }
            }
            .pointerInput(sections) {
                detectVerticalDragGestures { change, _ ->
                    currentOnSection(sectionAt(change.position.y, size.height))
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sections.forEach { s ->
            Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
private fun MediaCard(item: MediaItem, posterUrl: String?, localThumbnailUri: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Tonal poster slot: the title initials show behind, and the real poster (if the item has art)
            // crossfades in over them, falling back to the initials while loading / on error / when absent.
            Surface(
                shape = RoundedCornerShape(16.dp),
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val secondary = item.subtitle ?: item.year?.toString()
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun MediaDetailScreen(state: AppUiState, viewModel: MainViewModel, onChooseTv: () -> Unit) {
    val item = state.selectedItem
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (item == null) {
            Text("No item selected.")
            return
        }

        // Hero header
        ElevatedCard(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetailPoster(viewModel.posterUrl(item, POSTER_DETAIL_WIDTH_PX), item.title)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val meta = listOfNotNull(item.subtitle, item.year?.toString(), item.runtimeSeconds?.let { formatRuntime(it) })
                            .joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        item.resumePositionSeconds?.let {
                            Text(
                                "Resume from ${formatClock(it)}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        watchStateLabel(item)?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                item.overview?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        Button(
            onClick = { viewModel.clearDownloadSelection(); onChooseTv() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  Cast to a TV", style = MaterialTheme.typography.titleMedium)
        }

        // Jellyfin native watch state: mark this item (a series/season cascades to its episodes).
        if (item.sourceId != MediaSourceIds.LOCAL) {
            OutlinedButton(
                onClick = { viewModel.markWatched(item, !item.played) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    if (item.played) Icons.Rounded.RemoveDone else Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(if (item.played) "  Mark as unwatched" else "  Mark as watched")
            }
        }

        // ----- offline download (hidden for on-device local files — they are already on the phone) -----
        if (item.sourceId != MediaSourceIds.LOCAL) {
        val dl = state.downloadFor(item.id)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                // Format selector state lives outside the when-branches so a chosen format persists
                // if the download fails and the user wants to retry without re-selecting.
                var selectedFormat by remember(item.id) { mutableStateOf<DownloadFormat>(DownloadFormat.Original) }
                var formatMenuExpanded by remember(item.id) { mutableStateOf(false) }
                when {
                    dl == null -> {
                        Text(
                            "Download a copy to cast later without the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Box {
                            OutlinedButton(onClick = { formatMenuExpanded = true }) { Text(selectedFormat.label) }
                            DropdownMenu(expanded = formatMenuExpanded, onDismissRequest = { formatMenuExpanded = false }) {
                                DownloadFormat.PRESETS.forEach { fmt ->
                                    DropdownMenuItem(
                                        text = { Text(fmt.label) },
                                        onClick = { selectedFormat = fmt; formatMenuExpanded = false },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { viewModel.downloadSelected(selectedFormat) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Download for offline")
                        }
                    }
                    dl.completed -> {
                        Text(
                            "Downloaded \u2713",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.prepareCastDownload(item.id); onChooseTv() },
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  Cast offline")
                            }
                            OutlinedButton(
                                onClick = { viewModel.deleteDownload(item.id) },
                                shape = RoundedCornerShape(18.dp),
                            ) { Text("Delete") }
                        }
                    }
                    dl.failed -> {
                        Text(dl.statusText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Box {
                            OutlinedButton(onClick = { formatMenuExpanded = true }) { Text(selectedFormat.label) }
                            DropdownMenu(expanded = formatMenuExpanded, onDismissRequest = { formatMenuExpanded = false }) {
                                DownloadFormat.PRESETS.forEach { fmt ->
                                    DropdownMenuItem(
                                        text = { Text(fmt.label) },
                                        onClick = { selectedFormat = fmt; formatMenuExpanded = false },
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.downloadSelected(selectedFormat) },
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Retry download") }
                    }
                    else -> {
                        Text(
                            dl.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        dl.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = { viewModel.cancelDownload(item.id) },
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DetailPoster(posterUrl: String?, title: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.width(96.dp).aspectRatio(2f / 3f),
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
