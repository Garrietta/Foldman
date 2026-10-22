package dev.foldman.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.Source
import dev.foldman.deck.SourceStatus
import dev.foldman.playback.Artwork

/** Smoked dark glass the library and now-playing sit behind on the unfolded screen. */
fun Modifier.glassPanel(corner: Dp = 18.dp): Modifier = drawWithCache {
    val r = CornerRadius(corner.toPx())
    val fill = Brush.verticalGradient(listOf(Color(0xFF2E2622), Color(0xFF1E1916)))
    onDrawBehind {
        drawRoundRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, 3f), cornerRadius = r)
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = r, style = Stroke(1.5f))
    }
}

/** Library band: sources, then albums (folders) or the streaming app's state, then the queue. */
@Composable
fun LibraryBand(ui: DeckUi, actions: ShelfActions, onPlayQueue: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.glassPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Scrolls if the panel is ever too short for every source.
        Column(Modifier.weight(0.3f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text("Sources", style = Header)
            Spacer(Modifier.height(6.dp))
            Source.entries.forEach { s ->
                val selected = s == ui.source
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.09f) else Color.Transparent)
                        .clickable { actions.selectSource(s) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceTile(s, Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = Title, maxLines = 1)
                        Text(if (selected) statusLine(ui) else "Tap to load", style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(Modifier.weight(0.36f).fillMaxHeight()) {
            when (ui.source) {
                Source.LOCAL -> Albums(ui, actions)
                Source.YT_MUSIC -> YouTubeMusicBrowser(ui.youtubeMusic, actions.youtubeMusic, Modifier.fillMaxSize())
                Source.PODCASTS -> PodcastsBrowser(ui.podcasts, actions.podcasts, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(0.34f).fillMaxHeight()) {
            Text("Queue", style = Header)
            Spacer(Modifier.height(8.dp))
            QueueList(ui, onPlayQueue)
        }
    }
}

private fun statusLine(ui: DeckUi): String = when {
    ui.source == Source.YT_MUSIC -> if (ui.youtubeMusic.signedIn) "Signed in" else "Sign in"
    ui.source == Source.PODCASTS -> {
        val n = ui.podcasts.feeds.size
        if (n == 0) "No feeds" else if (n == 1) "1 feed" else "$n feeds"
    }
    else -> when (val s = ui.status) {
        SourceStatus.Ready -> if (ui.source == Source.LOCAL) "${ui.tracks.size} songs" else "Connected"
        SourceStatus.NeedsFolder -> "Choose a folder"
        is SourceStatus.Scanning -> "Scanning… ${s.found}"
        SourceStatus.EmptyFolder -> "No music found"
        SourceStatus.AppNotRunning -> "Not playing"
    }
}

@Composable
private fun SourceTile(source: Source, modifier: Modifier) {
    val shell = DeckColors.shell(source)
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(shell, Color.White, 0.25f), shell))),
        contentAlignment = Alignment.Center,
    ) {
        // A tiny cassette window.
        Box(Modifier.fillMaxWidth(0.66f).height(9.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1A1714)))
    }
}

@Composable
private fun Albums(ui: DeckUi, actions: ShelfActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Albums", style = Header, modifier = Modifier.weight(1f))
        Text(
            if (ui.status == SourceStatus.NeedsFolder) "Choose folder" else "Change folder",
            style = Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickable(onClick = actions.pickFolder).padding(4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    when (val s = ui.status) {
        SourceStatus.NeedsFolder -> Text("Pick a folder of music on this phone. Covers come from the files or a cover.jpg beside them.", style = Sub)
        is SourceStatus.Scanning -> Text("Scanning… ${s.found} songs", style = Sub)
        SourceStatus.EmptyFolder -> Text("No music in that folder.", style = Sub)
        else -> {
            // Each folder is an album: its first track's index, cover and size.
            val albums = remember(ui.tracks) {
                ui.tracks.withIndex().groupBy { it.value.folder }.map { (folder, items) ->
                    Triple(folder, items.first(), items.size)
                }
            }
            LazyColumn {
                items(albums, key = { it.first }) { (folder, first, count) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { actions.playTrack(first.index) }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(null, first.value.coverUri, Modifier.size(38.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder, style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${first.value.artist ?: "Album"} · $count songs", style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueList(ui: DeckUi, onPlay: (Long) -> Unit) {
    if (ui.queue.isEmpty()) {
        val empty = when {
            ui.source == Source.LOCAL -> "Nothing loaded"
            ui.source == Source.YT_MUSIC -> "Find songs in YouTube Music"
            ui.source == Source.PODCASTS -> "Pick a podcast to load the queue"
            else -> "This app doesn't share its queue"
        }
        Text(empty, style = Sub)
        return
    }
    val list = rememberLazyListState()
    val currentIndex = ui.queue.indexOfFirst { it.id == ui.currentQueueId }
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) list.animateScrollToItem(currentIndex) }
    LazyColumn(state = list) {
        itemsIndexed(ui.queue, key = { _, q -> q.id }) { _, q ->
            QueueRow(q, current = q.id == ui.currentQueueId, onClick = { onPlay(q.id) })
        }
    }
}

@Composable
private fun QueueRow(q: QueueEntry, current: Boolean, onClick: () -> Unit, showArt: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawWithCache {
                onDrawBehind {
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Thumb(q.artwork, q.artworkUri, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(q.title, style = Title.copy(color = if (current) DeckColors.Accent else Title.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            q.subtitle?.let { Text(it, style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (current) Text("▶", style = Sub.copy(color = DeckColors.Accent))
    }
}

@Composable
fun UrlThumb(url: String?, modifier: Modifier) = Thumb(null, url?.let(Uri::parse), modifier)

/** Cover thumbnail from a bitmap the source handed us, or loaded (and cached) from a URI. */
@Composable
private fun Thumb(bitmap: Bitmap?, uri: Uri?, modifier: Modifier) {
    val context = LocalContext.current
    // produceState keeps its last value when the keys change, so always replace it: a reused row
    // would otherwise go on showing the previous song's cover.
    val image by produceState<ImageBitmap?>(bitmap?.asImageBitmap(), bitmap, uri) {
        value = bitmap?.asImageBitmap() ?: uri?.let { Artwork.thumbnail(context, it)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A302A)), contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text("♪", style = Sub.copy(fontSize = 16.sp))
    }
}

/** Now-playing band: the cover big, what's up next, and liner notes. */
@Composable
fun NowPlayingBand(ui: DeckUi, onPlayQueue: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.glassPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(ui.label.stripe, ui.label.stripe2))),
            contentAlignment = Alignment.Center,
        ) {
            ui.artwork?.let { Image(it, "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text("A", style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.85f)))
        }
        Column(Modifier.weight(0.5f).fillMaxHeight()) {
            Text("Up next", style = Header)
            Spacer(Modifier.height(6.dp))
            val i = ui.queue.indexOfFirst { it.id == ui.currentQueueId }
            val next = if (i >= 0) ui.queue.drop(i + 1).take(4) else emptyList()
            if (next.isEmpty()) Text("End of side A", style = Sub)
            next.forEach { q -> QueueRow(q, current = false, onClick = { onPlayQueue(q.id) }, showArt = true) }
        }
        Column(Modifier.weight(0.5f).fillMaxHeight()) {
            Text("Liner notes", style = Header)
            Spacer(Modifier.height(8.dp))
            Text(ui.title.ifBlank { "No tape playing" }, style = Title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ui.artist.isNotBlank()) Text(ui.artist, style = Sub.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.album.isNotBlank()) Text(ui.album, style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            val details = buildList {
                add(ui.source.label)
                if (ui.trackCount > 0) add("Track ${ui.trackIndex + 1} of ${ui.trackCount}")
                add("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}")
            }
            details.forEach { Text(it, style = Sub) }
            Spacer(Modifier.weight(1f))
            // Tape-position hairline.
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(ui.progress).fillMaxHeight().background(DeckColors.Accent))
            }
        }
    }
}
