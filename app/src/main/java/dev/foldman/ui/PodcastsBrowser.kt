package dev.foldman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivor.ivormusic.data.PodcastEpisode
import com.ivor.ivormusic.data.PodcastFeed
import dev.foldman.deck.PodcastsUi
import java.text.DateFormat
import java.util.Date

/**
 * The podcasts shelf: RSS feeds, their episodes, and an add-by-URL dialog.
 * One scrollable list that fills whatever space the layout gives it.
 */
@Composable
fun PodcastsBrowser(
    ui: PodcastsUi,
    actions: PodcastActions,
    modifier: Modifier = Modifier,
    onPaper: Boolean = false,
    filter: String = "",
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        podcastItems(ui, actions, onPaper = onPaper, filter = filter)
    }

    if (ui.showAddDialog) {
        AddFeedDialog(ui, actions)
    }
}

/**
 * The podcasts browser as items of a lazy list, so the TAPES sheet can share one scroll
 * with a sticky now-playing header. [wrap] lets the sheet put each row on the J-card's paper.
 */
fun LazyListScope.podcastItems(
    ui: PodcastsUi,
    actions: PodcastActions,
    onPaper: Boolean = false,
    filter: String = "",
    wrap: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    val openFeed = ui.openFeed
    val q = filter.trim()
    val episodes = (openFeed?.let { ui.episodesByFeed[it.id] } ?: emptyList())
        .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
    val feeds = ui.feeds.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }

    // The app's text library, picked per surface — the same tokens the local-files
    // library and the YouTube Music browser use.
    val titleStyle = if (onPaper) Typed.copy(fontWeight = FontWeight.Bold) else Header
    val subStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val sectionStyle = if (onPaper) Typed.copy(fontWeight = FontWeight.Bold) else Header
    val linkStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep)
        else Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    val bodyStyle = if (onPaper) Typed.copy(fontSize = 12.sp) else Sub
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp) else Title.copy(fontSize = 12.sp)
    val itemSubStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val glyphStyle = if (onPaper) Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)) else Sub

    item(key = "pod-title") {
        wrap {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    openFeed?.title ?: "Podcasts",
                    style = titleStyle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (openFeed == null) {
                    Text(
                        if (feeds.isEmpty() && q.isEmpty()) "NO FEEDS" else "${feeds.size} FEEDS",
                        style = subStyle,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    TextButton(onClick = { actions.showAddDialog(true) }) { Text("Add", style = linkStyle) }
                }
            }
        }
    }

    if (openFeed != null) {
        item(key = "pod-back") {
            wrap { TextButton(onClick = actions.closeFeed) { Text("← Podcasts", style = linkStyle) } }
        }
        item(key = "pod-epheader") {
            wrap {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${episodes.size} episodes",
                        style = sectionStyle,
                        modifier = Modifier.weight(1f),
                    )
                    if (episodes.isNotEmpty()) {
                        ShuffleButton(onClick = { actions.shuffleFeed(openFeed) }, onPaper = onPaper)
                    }
                    TextButton(onClick = { actions.refreshFeed(openFeed) }) { Text("Refresh", style = linkStyle) }
                    TextButton(onClick = { actions.removeFeed(openFeed) }) { Text("Remove", style = linkStyle) }
                }
            }
        }
        items(episodes, key = { it.id }) { episode ->
            wrap { EpisodeRow(episode, onPaper = onPaper, onPlay = { actions.playEpisode(episode) }) }
        }
    } else {
        items(feeds, key = { it.id }) { feed ->
            val count = ui.episodesByFeed[feed.id]?.size ?: 0
            wrap {
                FeedRow(
                    feed = feed,
                    episodeCount = count,
                    onPaper = onPaper,
                    onOpen = { actions.openFeed(feed) },
                    onShuffle = { actions.shuffleFeed(feed) },
                    onRefresh = { actions.refreshFeed(feed) },
                    onRemove = { actions.removeFeed(feed) },
                )
            }
        }
        if (feeds.isEmpty() && !ui.loading) {
            item(key = "pod-empty") {
                wrap {
                    Text(
                        if (ui.feeds.isEmpty()) "No podcasts yet. Add an RSS feed to load it as a tape."
                        else "No podcasts match \"$q\".",
                        style = bodyStyle,
                    )
                }
            }
        }
    }

    if (ui.loading) {
        item(key = "pod-loading") {
            wrap { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
    }
    ui.message?.let { message ->
        item(key = "pod-message") {
            wrap { Text(message, style = bodyStyle.copy(fontSize = 11.sp)) }
        }
    }
}

@Composable
fun AddFeedDialog(ui: PodcastsUi, actions: PodcastActions) {
    // The dialog is always dark (the deck hardcodes a dark scheme), so it always
    // takes the light ink — regardless of which surface opened it.
    val dialogLink = Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    AlertDialog(
        onDismissRequest = { actions.showAddDialog(false) },
        title = { Text("Add podcast", style = Header) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the show's RSS feed URL.", style = Sub)
                OutlinedTextField(
                    value = ui.addUrl,
                    onValueChange = actions.addUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("Feed URL", style = Sub) },
                    textStyle = Sub.copy(fontSize = 14.sp),
                    isError = ui.addError != null,
                )
                ui.addError?.let { Text(it, style = Sub.copy(fontSize = 11.sp, color = Color(0xFFCF6679))) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.addFeed,
                enabled = ui.addUrl.isNotBlank() && !ui.adding,
            ) { Text(if (ui.adding) "Adding…" else "Add", style = dialogLink) }
        },
        dismissButton = {
            TextButton(onClick = { actions.showAddDialog(false) }) { Text("Cancel", style = dialogLink) }
        },
    )
}

@Composable
private fun FeedRow(
    feed: PodcastFeed,
    episodeCount: Int,
    onPaper: Boolean,
    onOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp) else Title.copy(fontSize = 12.sp)
    val itemSubStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val glyphStyle = if (onPaper) Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)) else Sub
    val linkStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep)
        else Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UrlThumb(feed.artworkUrl, Modifier.size(38.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(feed.title, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (episodeCount == 1) "1 episode" else "$episodeCount episodes",
                style = itemSubStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (episodeCount > 0) ShuffleButton(onClick = onShuffle, onPaper = onPaper)
        TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("↻", style = linkStyle.copy(fontSize = 14.sp))
        }
        TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("×", style = linkStyle.copy(fontSize = 16.sp))
        }
        Text("›", style = glyphStyle.copy(fontSize = 20.sp))
    }
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, onPaper: Boolean, onPlay: () -> Unit) {
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp) else Title.copy(fontSize = 12.sp)
    val itemSubStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val glyphStyle = if (onPaper) Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)) else Sub
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UrlThumb(episode.artworkUrl, Modifier.size(38.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(episode.title, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                episode.pubDate.takeIf { it > 0L }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                episode.durationMs.takeIf { it > 0L }?.let { formatTime(it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = itemSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", style = glyphStyle.copy(fontSize = 11.sp))
    }
}
