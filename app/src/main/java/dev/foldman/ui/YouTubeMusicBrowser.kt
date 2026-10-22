package dev.foldman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.ui.auth.YouTubeAuthDialog
import dev.foldman.deck.YouTubeMusicUi

@Composable
fun YouTubeMusicBrowser(ui: YouTubeMusicUi, actions: YouTubeMusicActions) {
    var showSignIn by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.openPlaylist?.name ?: "YouTube Music",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ui.signedIn) {
                Text(
                    ui.accountName ?: "SIGNED IN",
                    modifier = Modifier.weight(0.7f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = actions.refresh) { Text("Refresh") }
            }
        }

        if (!ui.signedIn) {
            Text(
                "Sign in to browse your YouTube Music library and play songs right in Foldman.",
                fontSize = 12.sp,
            )
            Button(onClick = { showSignIn = true }) {
                Text("Sign in to YouTube Music")
            }
        } else {
            if (ui.openPlaylist != null) {
                TextButton(onClick = actions.closePlaylist) { Text("← Your library") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = actions.queryChanged,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("Search songs") },
                )
                Spacer(Modifier.size(6.dp))
                Button(onClick = actions.search, enabled = ui.query.isNotBlank() && !ui.loading) {
                    Text("Search")
                }
            }

            if (ui.searched && ui.openPlaylist == null) {
                Text("Search results", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                SongList(ui.searchResults, actions.playSongs)
            } else if (ui.openPlaylist != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tracks", modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (ui.playlistTracks.isNotEmpty()) {
                        TextButton(onClick = { actions.playSongs(ui.playlistTracks, 0) }) {
                            Text("Play all")
                        }
                    }
                }
                SongList(ui.playlistTracks, actions.playSongs)
            } else {
                Text("Your library", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                PlaylistList(ui.playlists, actions.openPlaylist)
            }
        }

        if (ui.loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        ui.message?.let { Text(it, fontSize = 11.sp) }
    }

    if (showSignIn) {
        YouTubeAuthDialog(
            onDismiss = { showSignIn = false },
            onAuthSuccess = {
                showSignIn = false
                actions.refresh()
            },
        )
    }
}

@Composable
private fun PlaylistList(
    playlists: List<PlaylistDisplayItem>,
    onOpen: (PlaylistDisplayItem) -> Unit,
) {
    if (playlists.isEmpty()) return
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(playlist) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UrlThumb(playlist.thumbnailUrl, Modifier.size(38.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(playlist.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playlist.uploaderName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    onPlay: (List<Song>, Int) -> Unit,
) {
    if (songs.isEmpty()) return
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPlay(songs, index) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UrlThumb(song.thumbnailUrl, Modifier.size(38.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(song.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("▶", fontSize = 11.sp)
            }
        }
    }
}
