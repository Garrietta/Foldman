package dev.foldman.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.ui.auth.YouTubeAuthDialog
import dev.foldman.deck.YouTubeMusicUi

/**
 * The YouTube Music shelf: sign-in, search, your playlists and their tracks.
 * One scrollable list that fills whatever space the layout gives it, so nothing
 * is ever clipped below the fold.
 *
 * [onPaper] selects the ink: false for the unfolded dark-glass library (light text),
 * true for the folded TAPES sheet's J-card paper (dark ink, like the local library).
 */
@Composable
fun YouTubeMusicBrowser(
    ui: YouTubeMusicUi,
    actions: YouTubeMusicActions,
    modifier: Modifier = Modifier,
    onPaper: Boolean = false,
    hideSearch: Boolean = false,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        youtubeMusicItems(ui, actions, onPaper = onPaper, hideSearch = hideSearch)
    }
}

/**
 * The YouTube Music browser as items of a lazy list, so the TAPES sheet can share one scroll
 * with a sticky now-playing header. [wrap] lets the sheet put each row on the J-card's paper.
 */
fun LazyListScope.youtubeMusicItems(
    ui: YouTubeMusicUi,
    actions: YouTubeMusicActions,
    onPaper: Boolean = false,
    hideSearch: Boolean = false,
    /** Stage-3 collapse: the search field folds away, leaving the locked Tracks header. */
    searchCollapsed: Boolean = false,
    /** The sheet pins its own locked Tracks header above the list; skip the in-list one. */
    hideTracksHeader: Boolean = false,
    wrap: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    val openPlaylist = ui.openPlaylist

    // The app's text library, picked per surface — the same tokens the local-files
    // library uses, so YouTube Music reads as the same instrument.
    val titleStyle = if (onPaper) Typed.copy(fontWeight = FontWeight.Bold) else Header
    val accountStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub
    val sectionStyle = if (onPaper) Typed.copy(fontWeight = FontWeight.Bold) else Header
    val linkStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep)
        else Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    val bodyStyle = if (onPaper) Typed.copy(fontSize = 12.sp) else Sub
    val accent = if (onPaper) DeckColors.AccentDeep else DeckColors.Accent
    val ink = if (onPaper) DeckColors.Ink else Title.color

    item(key = "yt-title") {
        wrap {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        openPlaylist?.name ?: "YouTube Music",
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ui.signedIn) {
                        ui.accountName?.let { name ->
                            Text(name, style = accountStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (ui.signedIn) {
                    Text(
                        "Refresh",
                        style = linkStyle,
                        modifier = Modifier.clickable(onClick = actions.refresh).padding(4.dp),
                    )
                }
            }
        }
    }

    if (!ui.signedIn) {
        item(key = "yt-signin") {
            wrap {
                var showSignIn by remember { mutableStateOf(false) }
                Column {
                    Text(
                        "Sign in to browse your YouTube Music library and play songs right in Foldman.",
                        style = bodyStyle,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showSignIn = true },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    ) {
                        Text("Sign in to YouTube Music")
                    }
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
        }
    } else {
        if (openPlaylist != null) {
            item(key = "yt-back") {
                wrap {
                    Text(
                        "← Your library",
                        style = linkStyle,
                        modifier = Modifier.clickable(onClick = actions.closePlaylist).padding(vertical = 4.dp),
                    )
                }
            }
        }

        // The right column's tab header owns the search field; hide the in-list one there.
        if (!hideSearch) {
            item(key = "yt-search") {
            wrap {
                // Stage 3 of the sheet's collapse: the search field folds away.
                AnimatedVisibility(
                    visible = !searchCollapsed,
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(animationSpec = tween(150)),
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = actions.queryChanged,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Search songs", style = accountStyle) },
                        textStyle = TextStyle(color = ink, fontSize = 14.sp),
                        // The soft keyboard's Search key must run the query too —
                        // without this, tapping it does nothing at all.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { actions.search() },
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ink,
                            unfocusedTextColor = ink,
                            focusedBorderColor = if (onPaper) DeckColors.Ink.copy(alpha = 0.7f) else Sub.color,
                            unfocusedBorderColor = if (onPaper) DeckColors.Ink.copy(alpha = 0.4f) else Sub.color.copy(alpha = 0.5f),
                            cursorColor = accent,
                            focusedLabelColor = accountStyle.color,
                            unfocusedLabelColor = accountStyle.color,
                        ),
                    )
                    Spacer(Modifier.size(6.dp))
                    TextButton(
                        onClick = actions.search,
                        enabled = ui.query.isNotBlank() && !ui.loading,
                    ) {
                        Text("Search", style = linkStyle)
                    }
                }
                }
            }
        }
            // Search state lives directly under the field — never at the list
            // end — so a spinner, "no results", or an error is always visible.
            // (It used to sit off-screen below the playlists, so a failure
            // looked exactly like "nothing happens".)
            if (ui.loading) {
                item(key = "yt-loading") {
                    wrap {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
                            Spacer(Modifier.size(8.dp))
                            Text("Searching…", style = bodyStyle)
                        }
                    }
                }
            }
            ui.message?.let { message ->
                item(key = "yt-message") {
                    wrap { Text(message, style = bodyStyle.copy(fontSize = 11.sp)) }
                }
            }
        }

        if (ui.searched && openPlaylist == null) {
            if (ui.searchArtists.isNotEmpty()) {
                item(key = "yt-results-artists-header") {
                    wrap { Text("Artists", style = sectionStyle) }
                }
                itemsIndexed(ui.searchArtists, key = { _, artist -> artist.id }) { _, artist ->
                    wrap { ArtistRow(artist, onPaper = onPaper, onOpen = { actions.searchArtist(artist) }) }
                }
            }
            if (ui.searchAlbums.isNotEmpty()) {
                item(key = "yt-results-albums-header") {
                    wrap { Text("Albums", style = sectionStyle) }
                }
                itemsIndexed(ui.searchAlbums, key = { _, album -> album.id }) { _, album ->
                    wrap {
                        PlaylistRow(
                            album,
                            onPaper = onPaper,
                            onOpen = { actions.openPlaylist(album) },
                            onShuffle = { actions.shufflePlaylist(album) },
                        )
                    }
                }
            }
            if (ui.searchResults.isNotEmpty()) {
                item(key = "yt-results-header") {
                    wrap { Text("Songs", style = sectionStyle) }
                }
                itemsIndexed(ui.searchResults, key = { index, song -> "${song.id}-$index" }) { index, song ->
                    wrap { SongRow(song, onPaper = onPaper, onPlay = { actions.playSongs(ui.searchResults, index) }) }
                }
            }
        } else if (openPlaylist != null) {
            if (!hideTracksHeader) {
                item(key = "yt-tracks-header") {
                    wrap {
                        TracksHeaderRow(ui, actions, onPaper)
                    }
                }
            }
            itemsIndexed(ui.playlistTracks, key = { index, song -> "${song.id}-$index" }) { index, song ->
                wrap { SongRow(song, onPaper = onPaper, onPlay = { actions.playSongs(ui.playlistTracks, index) }) }
            }
        } else {
            item(key = "yt-library-header") {
                wrap { Text("Your library", style = sectionStyle) }
            }
            itemsIndexed(ui.playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                wrap {
                    PlaylistRow(
                        playlist,
                        onPaper = onPaper,
                        onOpen = { actions.openPlaylist(playlist) },
                        onShuffle = { actions.shufflePlaylist(playlist) },
                    )
                }
            }
        }
    }
}

/**
 * The Tracks header row: section title plus the Shuffle / Play-all affordances.
 * Shared by the in-list header and the sheet's locked (pinned) header above the
 * scrollable track list.
 */
@Composable
fun TracksHeaderRow(ui: YouTubeMusicUi, actions: YouTubeMusicActions, onPaper: Boolean) {
    val sectionStyle = if (onPaper) Typed.copy(fontWeight = FontWeight.Bold) else Header
    val linkStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep)
        else Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tracks", modifier = Modifier.weight(1f), style = sectionStyle)
        if (ui.playlistTracks.isNotEmpty()) {
            ShuffleButton(onClick = { ui.openPlaylist?.let(actions.shufflePlaylist) }, onPaper = onPaper)
            Text(
                "Play all",
                style = linkStyle,
                modifier = Modifier.clickable(onClick = { actions.playSongs(ui.playlistTracks, 0) }).padding(4.dp),
            )
        }
    }
}

/** Quiet shuffle affordance in the Foldman idiom: small engraved caps, no chrome. */
@Composable
fun ShuffleButton(onClick: () -> Unit, modifier: Modifier = Modifier, onPaper: Boolean = false) {
    val style = if (onPaper) TextStyle(fontFamily = FontFamily.Default, color = DeckColors.AccentDeep)
        else Sub.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DeckColors.Accent)
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("SHUFFLE", style = style)
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistDisplayItem,
    onPaper: Boolean,
    onOpen: () -> Unit,
    onShuffle: () -> Unit,
) {
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp, color = DeckColors.Ink) else Title.copy(fontSize = 12.sp)
    val itemSubStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val glyphStyle = if (onPaper) Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)) else Sub
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UrlThumb(playlist.thumbnailUrl, Modifier.size(38.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(playlist.name, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.uploaderName, style = itemSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ShuffleButton(onClick = onShuffle, onPaper = onPaper)
        Text("›", style = glyphStyle.copy(fontSize = 20.sp))
    }
}

@Composable
private fun ArtistRow(artist: com.ivor.ivormusic.data.ArtistItem, onPaper: Boolean, onOpen: () -> Unit) {
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp, color = DeckColors.Ink) else Title.copy(fontSize = 12.sp)
    val itemSubStyle = if (onPaper) Typed.copy(fontSize = 10.sp, color = DeckColors.Ink.copy(alpha = 0.55f))
        else Sub.copy(fontSize = 10.sp)
    val glyphStyle = if (onPaper) Typed.copy(color = DeckColors.Ink.copy(alpha = 0.5f)) else Sub
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UrlThumb(artist.thumbnailUrl, Modifier.size(38.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(artist.name, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                "Artist",
                artist.subscriberCount?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            Text(sub, style = itemSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = glyphStyle.copy(fontSize = 20.sp))
    }
}

@Composable
private fun SongRow(song: Song, onPaper: Boolean, onPlay: () -> Unit) {
    val itemStyle = if (onPaper) TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp, color = DeckColors.Ink) else Title.copy(fontSize = 12.sp)
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
        UrlThumb(song.thumbnailUrl, Modifier.size(38.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(song.title, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = itemSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", style = glyphStyle.copy(fontSize = 11.sp))
    }
}
