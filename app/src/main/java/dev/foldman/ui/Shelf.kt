package dev.foldman.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.PodcastEpisode
import com.ivor.ivormusic.data.PodcastFeed
import com.ivor.ivormusic.data.Song

class ShelfActions(
    val selectSource: (Source) -> Unit,
    val pickFolder: () -> Unit,
    val playTrack: (Int) -> Unit,
    val toggleShuffle: () -> Unit,
    val youtubeMusic: YouTubeMusicActions,
    val podcasts: PodcastActions,
)

class YouTubeMusicActions(
    val queryChanged: (String) -> Unit,
    val search: () -> Unit,
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
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF2A1F17), Color(0xFF1C1510))))
            .padding(16.dp),
    ) {
        // Fixed header — the ModalBottomSheet's drag handle sits above this.
        Text("TAPES", style = EngravedCaps.copy(color = Color(0xFFB59B80), fontSize = 10.sp, letterSpacing = 3.sp))
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            // (1) The source rows scroll up and hide under the fixed header.
            Source.entries.forEach { s ->
                item(key = "source-${s.name}") {
                    TapeSpine(s, inDeck = s == ui.source, onClick = { actions.selectSource(s) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            // (2) The now-playing card locks in place as the new header once reached.
            stickyHeader(key = "now-playing") {
                NowPlayingCard(ui)
            }
            // (3) The browser list scrolls beneath it, on the card's paper.
            when (ui.source) {
                Source.LOCAL -> localItems(ui, actions, paperWrap)
                Source.YT_MUSIC -> youtubeMusicItems(ui.youtubeMusic, actions.youtubeMusic, onPaper = true, wrap = paperWrap)
                Source.PODCASTS -> podcastItems(ui.podcasts, actions.podcasts, paperWrap)
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
        if (ui.podcasts.showAddDialog) {
            AddFeedDialog(ui.podcasts, actions.podcasts)
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
            Text(source.label, style = Hand.copy(fontSize = 18.sp), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (inDeck) "IN DECK" else "LOAD",
            style = EngravedCaps.copy(color = if (inDeck) DeckColors.Accent else Color.White.copy(alpha = 0.7f), fontSize = 8.sp),
        )
    }
}

/**
 * The top of the old J-card — cover art, title block, progress and the accent rules —
 * now the sticky header of the sheet's scroll.
 */
@Composable
private fun NowPlayingCard(ui: DeckUi, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(DeckColors.Paper)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CoverArt(ui, Modifier.size(104.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.title.ifBlank { "—" }, style = Hand.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ui.artist.isNotBlank()) Text(ui.artist, style = Hand.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.album.isNotBlank()) Text(ui.album, style = Typed.copy(fontSize = 11.sp, color = DeckColors.Ink.copy(alpha = 0.6f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}", style = Typed.copy(fontSize = 11.sp, color = DeckColors.Ink.copy(alpha = 0.6f)))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(DeckColors.Accent))
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF2B33D)))
        Spacer(Modifier.height(10.dp))
    }
}

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
            wrap { Text("Winding on… ${status.found} songs found", style = Hand) }
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
                            style = EngravedCaps.copy(color = if (ui.shuffleOn) DeckColors.AccentDeep else DeckColors.Ink.copy(alpha = 0.55f)),
                            modifier = Modifier.clickable(onClick = actions.toggleShuffle).padding(6.dp),
                        )
                        Text("CHANGE", style = EngravedCaps.copy(color = DeckColors.AccentDeep), modifier = Modifier.clickable(onClick = actions.pickFolder).padding(6.dp))
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
            Text(track.title, style = Hand.copy(fontSize = 19.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(text.uppercase(), style = EngravedCaps.copy(color = DeckColors.Ink, fontSize = 11.sp))
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
