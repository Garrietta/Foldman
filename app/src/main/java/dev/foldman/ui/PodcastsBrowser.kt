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
fun PodcastsBrowser(ui: PodcastsUi, actions: PodcastActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        podcastItems(ui, actions)
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
    wrap: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    val openFeed = ui.openFeed
    val episodes = openFeed?.let { ui.episodesByFeed[it.id] } ?: emptyList()

    item(key = "pod-title") {
        wrap {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    openFeed?.title ?: "Podcasts",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (openFeed == null) {
                    Text(
                        if (ui.feeds.isEmpty()) "NO FEEDS" else "${ui.feeds.size} FEEDS",
                        fontSize = 9.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    TextButton(onClick = { actions.showAddDialog(true) }) { Text("Add") }
                }
            }
        }
    }

    if (openFeed != null) {
        item(key = "pod-back") {
            wrap { TextButton(onClick = actions.closeFeed) { Text("← Podcasts") } }
        }
        item(key = "pod-epheader") {
            wrap {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${episodes.size} episodes",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (episodes.isNotEmpty()) {
                        ShuffleButton(onClick = { actions.shuffleFeed(openFeed) })
                    }
                    TextButton(onClick = { actions.refreshFeed(openFeed) }) { Text("Refresh") }
                    TextButton(onClick = { actions.removeFeed(openFeed) }) { Text("Remove") }
                }
            }
        }
        items(episodes, key = { it.id }) { episode ->
            wrap { EpisodeRow(episode, onPlay = { actions.playEpisode(episode) }) }
        }
    } else {
        items(ui.feeds, key = { it.id }) { feed ->
            val count = ui.episodesByFeed[feed.id]?.size ?: 0
            wrap {
                FeedRow(
                    feed = feed,
                    episodeCount = count,
                    onOpen = { actions.openFeed(feed) },
                    onShuffle = { actions.shuffleFeed(feed) },
                    onRefresh = { actions.refreshFeed(feed) },
                    onRemove = { actions.removeFeed(feed) },
                )
            }
        }
        if (ui.feeds.isEmpty() && !ui.loading) {
            item(key = "pod-empty") {
                wrap {
                    Text(
                        "No podcasts yet. Add an RSS feed to load it as a tape.",
                        fontSize = 12.sp,
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
            wrap { Text(message, fontSize = 11.sp) }
        }
    }
}

@Composable
fun AddFeedDialog(ui: PodcastsUi, actions: PodcastActions) {
    AlertDialog(
        onDismissRequest = { actions.showAddDialog(false) },
        title = { Text("Add podcast", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the show's RSS feed URL.", fontSize = 12.sp)
                OutlinedTextField(
                    value = ui.addUrl,
                    onValueChange = actions.addUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("Feed URL") },
                    isError = ui.addError != null,
                )
                ui.addError?.let { Text(it, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.addFeed,
                enabled = ui.addUrl.isNotBlank() && !ui.adding,
            ) { Text(if (ui.adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = { actions.showAddDialog(false) }) { Text("Cancel") }
        },
    )
}

@Composable
private fun FeedRow(
    feed: PodcastFeed,
    episodeCount: Int,
    onOpen: () -> Unit,
    onShuffle: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
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
            Text(feed.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (episodeCount == 1) "1 episode" else "$episodeCount episodes",
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (episodeCount > 0) ShuffleButton(onClick = onShuffle)
        TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("↻", fontSize = 14.sp) }
        TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("×", fontSize = 16.sp) }
        Text("›", fontSize = 20.sp)
    }
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, onPlay: () -> Unit) {
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
            Text(episode.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                episode.pubDate.takeIf { it > 0L }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                episode.durationMs.takeIf { it > 0L }?.let { formatTime(it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", fontSize = 11.sp)
    }
}
