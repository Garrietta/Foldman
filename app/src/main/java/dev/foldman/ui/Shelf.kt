package dev.foldman.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.Source
import dev.foldman.deck.SourceStatus
import dev.foldman.deck.Transport
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.PodcastEpisode
import com.ivor.ivormusic.data.PodcastFeed
import com.ivor.ivormusic.data.Song

class ShelfActions(
    val selectSource: (Source) -> Unit,
    val pickFolder: () -> Unit,
    val playTrack: (Int) -> Unit,
    val playQueueItem: (Long) -> Unit,
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val youtubeMusic: YouTubeMusicActions,
    val podcasts: PodcastActions,
)

class YouTubeMusicActions(
    val queryChanged: (String) -> Unit,
    val search: () -> Unit,
    val searchArtist: (com.ivor.ivormusic.data.ArtistItem) -> Unit,
    val openPlaylist: (PlaylistDisplayItem) -> Unit,
    val closePlaylist: () -> Unit,
    val playSongs: (List<Song>, Int) -> Unit,
    val shufflePlaylist: (PlaylistDisplayItem) -> Unit,
    val refresh: () -> Unit,
)

class PodcastActions(
    val openFeed: (PodcastFeed) -> Unit,
    val closeFeed: () -> Unit,
    val playEpisode: (PodcastEpisode) -> Unit,
    val shuffleFeed: (PodcastFeed) -> Unit,
    val refreshFeed: (PodcastFeed) -> Unit,
    val removeFeed: (PodcastFeed) -> Unit,
    val addUrlChanged: (String) -> Unit,
    val addFeed: () -> Unit,
    val showAddDialog: (Boolean) -> Unit,
)

/**
 * Now-playing bar pinned at the top of the TAPES sheet's cream panel, above
 * the tab switcher and visible on both tabs: what's playing plus play/pause,
 * next, previous, a shuffle toggle, and a seekbar. Cream/brown sheet
 * language, default font.
 */
@Composable
private fun NowPlayingBar(ui: DeckUi, actions: ShelfActions) {
    val cream = Color(0xFFE8DCC8)
    val darkBrown = Color(0xFF2A1F17)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(darkBrown)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            ui.title.ifBlank { "Nothing playing" },
            style = TextStyle(
                fontFamily = FontFamily.Default,
                color = cream,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ui.artist.ifBlank { ui.album },
            style = TextStyle(
                fontFamily = FontFamily.Default,
                color = cream.copy(alpha = 0.65f),
                fontSize = 12.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        // Seekbar: the drag position leads while scrubbing, the live position
        // follows otherwise; the seek fires once on release.
        var dragPos by remember { mutableStateOf<Long?>(null) }
        val dur = ui.durationMs.coerceAtLeast(0L)
        val shown = (dragPos ?: ui.positionMs).coerceIn(0L, dur.coerceAtLeast(1L))
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragPos = it.toLong() },
            onValueChangeFinished = {
                dragPos?.let { actions.seekTo(it) }
                dragPos = null
            },
            valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
            enabled = dur > 0,
            colors = SliderDefaults.colors(
                thumbColor = cream,
                activeTrackColor = cream,
                inactiveTrackColor = cream.copy(alpha = 0.25f),
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatSheetTime(shown),
                style = TextStyle(fontFamily = FontFamily.Default, color = cream.copy(alpha = 0.65f), fontSize = 11.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatSheetTime(dur),
                style = TextStyle(fontFamily = FontFamily.Default, color = cream.copy(alpha = 0.65f), fontSize = 11.sp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTransportButton(KeySymbol.PREV, onClick = actions.previous)
            SheetTransportButton(
                if (ui.transport == Transport.PLAYING) KeySymbol.PAUSE else KeySymbol.PLAY,
                onClick = actions.togglePlay,
            )
            SheetTransportButton(KeySymbol.NEXT, onClick = actions.next)
            SheetTransportButton(KeySymbol.SHUFFLE, onClick = actions.toggleShuffle, active = ui.shuffleOn)
        }
    }
}

/** A small round transport button in the sheet's cream/brown language. */
@Composable
private fun SheetTransportButton(symbol: KeySymbol, onClick: () -> Unit, active: Boolean = false) {
    val cream = Color(0xFFE8DCC8)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF5A422C) else Color(0xFF3A2C20))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawSymbol(
                symbol,
                Offset(size.width / 2f, size.height / 2f),
                size.minDimension * 0.16f,
                if (active) Color(0xFFF2B33D) else cream,
            )
        }
    }
}

private fun formatSheetTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The tape shelf: a spine for each source (tap one to load it into the deck) above the J-card of
 * the tape that's in, with its cover and track list.
 *
 * One continuous scroll: the source spines scroll up and hide under the fixed TAPES header,
 * the now-playing card pins as a sticky header once reached, and the browser list scrolls
 * beneath it.
 */
@Composable
fun TapeShelf(
    ui: DeckUi,
    actions: ShelfActions,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
) {
    // Hoisted so the sheet container can watch the scroll position for the
    // grow-with-scroll behavior; defaults to a private state as before.
    val list = listState ?: rememberLazyListState()
    var lastSource by remember { mutableStateOf(ui.source) }
    // Now-playing bar collapse: the bar hides on upward scroll (browsing the
    // library) and returns on downward scroll. Observed through nested scroll
    // so only touch scrolling collapses it — programmatic list jumps (track
    // tap auto-scroll, source switches) never do.
    var tab by remember { mutableStateOf(0) }
    var barVisible by remember { mutableStateOf(true) }
    val collapseConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -0.5f) barVisible = false
                else if (available.y > 0.5f) barVisible = true
                return Offset.Zero
            }
        }
    }
    // Switching tabs is a fresh context: bring the bar back.
    LaunchedEffect(tab) { barVisible = true }
    LaunchedEffect(ui.source, ui.trackIndex) {
        val switched = ui.source != lastSource
        lastSource = ui.source
        if (ui.source == Source.LOCAL && ui.trackIndex >= 0) {
            // Item indices: 0-2 source spines, 3 local header row, 4+i track i
            // (the stickyHeader takes no index). Mirrors the old inner track list,
            // which kept the current track visible.
            list.animateScrollToItem((ui.trackIndex + 2).coerceAtLeast(0))
        } else if (switched) {
            // Switching tapes used to swap in a fresh list; reset scroll the same way.
            list.scrollToItem(0)
        }
    }
    // In the sheet the browser rows sit on the J-card's paper, exactly as before.
    val paperWrap: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(DeckColors.Paper)
                .padding(horizontal = 14.dp)
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
    // Outer: dark brown sheet background (drag handle zone stays dark).
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1F17), Color(0xFF1C1510)))),
    ) {
        // Breathing room below the drag handle, then the inset cream panel.
        Spacer(Modifier.height(12.dp))
        // Inner: cream inset panel containing everything below the drag handle.
        // Symmetrical inset on left/right/bottom; top corners rounded concentric
        // with the sheet's own radii. The collapse connection watches touch
        // scrolling in either tab's list to hide/show the now-playing bar.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(DeckColors.Paper)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .nestedScroll(collapseConnection),
        ) {
        // Now-playing bar: below the drag handle, above the tab switcher —
        // visible on both tabs; collapses away on upward scroll.
        AnimatedVisibility(
            visible = barVisible,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = tween(150)),
        ) {
            Column {
                NowPlayingBar(ui, actions)
                Spacer(Modifier.height(10.dp))
            }
        }
        // Fixed tab switcher: Tapes (sources + browser) or Queue (live queue).
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.12f))
                .padding(4.dp),
        ) {
            listOf("Tapes", "Queue").forEachIndexed { i, label ->
                val selected = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) Color(0xFF2A1F17) else Color.Transparent)
                        .clickable { tab = i }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(fontFamily = FontFamily.Default, 
                            color = if (selected) Color(0xFFE8DCC8) else Color(0xFF6B5A4A),
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (tab == 0) {
            // Tape dropdown header: shows the selected tape, no shadow, no buttons.
            // Tap to cascade the other options; tap an option to select it.
            var dropdownOpen by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                TapeDropdownHeader(
                    selected = ui.source,
                    open = dropdownOpen,
                    onToggle = { dropdownOpen = !dropdownOpen },
                    onSelect = { s ->
                        actions.selectSource(s)
                        dropdownOpen = false
                    },
                )
                if (dropdownOpen) {
                    Spacer(Modifier.height(4.dp))
                }
                // The browser content for the selected source, below the header.
                // (The per-source "tap to load" state lives here, not in the header.)
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    when (ui.source) {
                        Source.LOCAL -> localItems(ui, actions, paperWrap)
                        Source.YT_MUSIC -> youtubeMusicItems(ui.youtubeMusic, actions.youtubeMusic, onPaper = true, wrap = paperWrap)
                        Source.PODCASTS -> podcastItems(ui.podcasts, actions.podcasts, onPaper = true, wrap = paperWrap)
                    }
                    // Rounded foot of the paper card.
                    item(key = "paper-foot") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(DeckColors.Paper, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                        )
                    }
                }
            }
        } else {
            // Queue tab: the live playing queue, same data as Up Next.
            QueueList(
                ui, onPlay = actions.playQueueItem, showArt = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onPaper = true,
            )
        }
        if (ui.podcasts.showAddDialog) {
            AddFeedDialog(ui.podcasts, actions.podcasts)
        }
        } // inner cream panel
    }
}

/**
 * Tape dropdown header for the Tapes tab: shows ONLY the selected tape,
 * centered label, no shadow, no buttons. Tap to cascade the other options;
 * tap an option to select it (menu closes, content below switches).
 * Tap the header while open to close without changing.
 */
@Composable
private fun TapeDropdownHeader(
    selected: Source,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (Source) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Header: the selected tape, centered, no shadow.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DeckColors.Paper)
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    selected.label,
                    style = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                // Chevron indicates dropdown state.
                Text(
                    if (open) "▲" else "▼",
                    style = TextStyle(fontFamily = FontFamily.Default, fontSize = 10.sp, color = Color(0xFF6B5A4A)),
                )
            }
        }
        // Dropdown options: the other tapes, cascading down.
        if (open) {
            Spacer(Modifier.height(4.dp))
            Source.entries.filter { it != selected }.forEach { s ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.6f))
                        .clickable { onSelect(s) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.label,
                        style = TextStyle(fontFamily = FontFamily.Default, fontSize = 18.sp, color = Color(0xFF4A3F35)),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TapeSpine(source: Source, inDeck: Boolean, onClick: () -> Unit) {
    val shell = DeckColors.shell(source)
    Row(
        Modifier
            .fillMaxWidth()
            .offset(x = if (inDeck) 10.dp else 0.dp)
            .shadow(if (inDeck) 10.dp else 3.dp, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(listOf(lerp(shell, Color.White, 0.15f), shell, lerp(shell, Color.Black, 0.25f))))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(2.dp))
                .background(DeckColors.Paper)
                .drawBehind {
                    drawRect(DeckColors.Accent, topLeft = Offset(0f, size.height - 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                }
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(source.label, style = TextStyle(fontFamily = FontFamily.Default, fontSize = 18.sp), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (inDeck) "IN DECK" else "LOAD",
            style = TextStyle(fontFamily = FontFamily.Default, color = if (inDeck) DeckColors.Accent else Color.White.copy(alpha = 0.7f), fontSize = 8.sp),
        )
    }
}

/**
 * The top of the old J-card — cover art, title block, progress and the accent rules —
 * now the sticky header of the sheet's scroll.
 */
@Composable
private fun CoverArt(ui: DeckUi, modifier: Modifier) {
    val art = ui.artwork
    Box(modifier.shadow(4.dp).background(ui.label.stripe), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(art, contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.height / 7f
                for (i in 0 until 7) drawRect(if (i % 2 == 0) ui.label.stripe else ui.label.stripe2, Offset(0f, i * step), androidx.compose.ui.geometry.Size(size.width, step))
            }
            Text("A", style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, color = DeckColors.Paper))
        }
    }
}

/**
 * The local-folder browser as items of the sheet's shared scroll. The auto-scroll-to-current
 * behavior moved up to [TapeShelf], which owns the list state now.
 */
private fun LazyListScope.localItems(
    ui: DeckUi,
    actions: ShelfActions,
    wrap: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    when (val status = ui.status) {
        SourceStatus.NeedsFolder -> item(key = "local-prompt") {
            wrap {
                Prompt(
                    "Pick a folder of music on this phone — MP3, FLAC, M4A, OGG, WAV. Album art comes from the files or a cover.jpg beside them.",
                    "Choose music folder", actions.pickFolder,
                )
            }
        }
        is SourceStatus.Scanning -> item(key = "local-scanning") {
            wrap { Text("Winding on… ${status.found} songs found", style = TextStyle(fontFamily = FontFamily.Default, color = DeckColors.Ink, fontSize = 20.sp)) }
        }
        SourceStatus.EmptyFolder -> item(key = "local-empty") {
            wrap { Prompt("No music in that folder.", "Choose another folder", actions.pickFolder) }
        }
        else -> {
            item(key = "local-header") {
                wrap {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${ui.folderName ?: "Folder"} · ${ui.tracks.size} songs",
                            style = Typed.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ui.shuffleOn) "SHUFFLED" else "SHUFFLE",
                            style = TextStyle(fontFamily = FontFamily.Default, color = if (ui.shuffleOn) DeckColors.AccentDeep else DeckColors.Ink.copy(alpha = 0.55f)),
                            modifier = Modifier.clickable(onClick = actions.toggleShuffle).padding(6.dp),
                        )
                        Text("CHANGE", style = TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep), modifier = Modifier.clickable(onClick = actions.pickFolder).padding(6.dp))
                    }
                }
            }
            itemsIndexed(ui.tracks, key = { _, t -> t.uri.toString() }) { i, track ->
                wrap {
                    TrackRow(
                        index = i,
                        track = track,
                        current = i == ui.trackIndex,
                        stripe = ui.label.stripe,
                        folderName = ui.folderName,
                        onPlay = { actions.playTrack(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    track: LocalTrack,
    current: Boolean,
    stripe: Color,
    folderName: String?,
    onPlay: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .drawBehind {
                drawLine(DeckColors.PaperLine, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                if (current) drawRect(stripe.copy(alpha = 0.18f))
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%02d".format(index + 1), style = Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)), modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(track.artist, track.folder.takeIf { it != folderName }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Text("◀", style = Typed.copy(color = DeckColors.AccentDeep))
    }
}

@Composable
private fun Prompt(text: String, button: String, onClick: () -> Unit) {
    Column {
        Text(text, style = Typed.copy(fontSize = 12.sp, lineHeight = 17.sp))
        Spacer(Modifier.height(12.dp))
        PaperButton(button, onClick)
    }
}

@Composable
private fun PaperButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.5.dp, DeckColors.Ink, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text.uppercase(), style = TextStyle(fontFamily = FontFamily.Default, color = DeckColors.Ink, fontSize = 11.sp))
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
