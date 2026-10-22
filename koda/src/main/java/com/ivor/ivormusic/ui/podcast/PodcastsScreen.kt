package com.ivor.ivormusic.ui.podcast

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.PodcastEpisode
import com.ivor.ivormusic.data.PodcastFeed
import com.ivor.ivormusic.data.Song
import java.text.DateFormat
import java.util.Date

/**
 * Podcast library content: subscribed RSS feeds, their episodes, and
 * adding/removing feeds by URL. Episodes play through the normal music
 * player queue via [onPlayQueue].
 *
 * Embedded as a tab of the music Library; [showTitle] hides the redundant
 * "Podcasts" headline when the hosting tab strip already names the section.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastsScreen(
    onPlayQueue: (List<Song>, Song?) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyListState,
    showTitle: Boolean = true,
    viewModel: PodcastsViewModel = viewModel(),
) {
    val feeds by viewModel.feeds.collectAsState()
    val episodesByFeed by viewModel.episodesByFeed.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val addingState by viewModel.addingState.collectAsState()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var openFeedId by rememberSaveable { mutableStateOf<String?>(null) }
    var feedMenuFor by remember { mutableStateOf<String?>(null) }

    val openFeed = feeds.find { it.id == openFeedId }

    // A finished add lands on the new feed's episodes.
    LaunchedEffect(addingState) {
        val done = addingState as? PodcastsViewModel.AddFeedState.Done
        if (done != null) {
            openFeedId = done.feed.id
            showAddDialog = false
            viewModel.consumeAddFeedState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (openFeed != null) {
                IconButton(onClick = { openFeedId = null }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = openFeed.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (showTitle) {
                Text(
                    text = stringResource(R.string.tab_podcasts),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (openFeed == null) {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.podcast_add_feed),
                    )
                }
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            openFeed != null -> {
                val episodes = (episodesByFeed[openFeed.id].orEmpty())
                    .sortedByDescending { it.pubDate }
                if (episodes.isEmpty()) {
                    EmptyPodcastsMessage(
                        text = stringResource(R.string.podcast_no_episodes),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                feedArtwork = openFeed.artworkUrl,
                                onClick = {
                                    val songs = viewModel.episodeSongs(openFeed.id)
                                    val song = songs.find { it.id == "podcast:${episode.id}" }
                                    if (song != null) {
                                        onPlayQueue(songs, song)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            feeds.isEmpty() -> {
                EmptyPodcastsMessage(
                    text = stringResource(R.string.podcast_empty),
                    actionLabel = stringResource(R.string.podcast_add_feed),
                    onAction = { showAddDialog = true },
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(feeds, key = { it.id }) { feed ->
                        val count = episodesByFeed[feed.id]?.size ?: 0
                        FeedRow(
                            feed = feed,
                            episodeCount = count,
                            onClick = { openFeedId = feed.id },
                            onLongPress = { feedMenuFor = feed.id },
                        )
                        if (feedMenuFor == feed.id) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { feedMenuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.podcast_refresh)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Refresh, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.refreshFeed(feed.id)
                                        feedMenuFor = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.podcast_remove)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.removeFeed(feed.id)
                                        feedMenuFor = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFeedDialog(
            state = addingState,
            onDismiss = {
                showAddDialog = false
                viewModel.consumeAddFeedState()
            },
            onAdd = { url -> viewModel.addFeed(url) },
        )
    }
}

@Composable
private fun EmptyPodcastsMessage(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(
    feed: PodcastFeed,
    episodeCount: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = feed.artworkUrl,
            contentDescription = feed.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.podcast_episode_count, episodeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    feedArtwork: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = episode.artworkUrl ?: feedArtwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = episodeMeta(episode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun episodeMeta(episode: PodcastEpisode): String {
    val parts = mutableListOf<String>()
    if (episode.pubDate > 0L) {
        parts.add(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(episode.pubDate))
        )
    }
    if (episode.durationMs > 0L) {
        parts.add(formatDuration(episode.durationMs))
    }
    return parts.joinToString(" • ")
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun AddFeedDialog(
    state: PodcastsViewModel.AddFeedState,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val working = state is PodcastsViewModel.AddFeedState.Working
    val error = (state as? PodcastsViewModel.AddFeedState.Error)?.message

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.podcast_add_feed_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.podcast_add_feed_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.podcast_feed_url_label)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (working) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.podcast_add_feed_working),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(url) },
                enabled = !working && url.isNotBlank(),
            ) {
                Text(stringResource(R.string.podcast_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
