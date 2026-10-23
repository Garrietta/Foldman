package dev.foldman.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.foldman.deck.DeckEvent
import dev.foldman.deck.DeckUi
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.Source
import dev.foldman.deck.Transport
import dev.foldman.theme.ThemeMode
import dev.foldman.theme.resolveDark
import dev.foldman.theme.toDarkPrimary
import com.ivor.ivormusic.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Queue-only panel for the unfolded portrait layout: the live Up Next list,
 * full width, no library browser (that's in the TAPES sheet).
 */
@Composable
private fun QueuePanel(ui: DeckUi, actions: ShelfActions, onPlayQueue: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.glassPanel().padding(14.dp)) {
        Text(
            "Up Next",
            style = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, color = Color.White),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        QueueList(ui, onPlayQueue, showArt = true, modifier = Modifier.fillMaxSize())
    }
}

private enum class Posture { Cover, Unfolded, UnfoldedPortrait, Tabletop }

/**
 * Picks a layout from the hinge:
 *  - folded (cover screen): the portable player stood upright, tape on end behind its window,
 *    keys and volume down a silver side strip;
 *  - unfolded: the same player laid down as a deck, with the library above and now-playing below;
 *  - half-open on a table (Flex mode): the deck on the upper half, the library on the lower.
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun FoldmanScreen(
    vm: DeckViewModel,
    windowLayout: WindowLayoutInfo?,
    onPickFolder: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val applicationContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Theme: the persisted override (System / Light / Dark) resolved against
    // the system setting. DeckColors.dark is the single switch the whole UI
    // re-tones on; the enamel is the dynamic theme primary, animated so a new
    // extraction cross-fades instead of flashing.
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dark = resolveDark(themeMode, isSystemInDarkTheme())
    LaunchedEffect(dark) { DeckColors.dark = dark }
    val enamelTarget = if (dark) ui.primaryAccent.toDarkPrimary() else ui.primaryAccent
    val enamel by animateColorAsState(enamelTarget, label = "enamel")
    val youtubePlayer = if (ui.source == Source.YT_MUSIC || ui.source == Source.PODCASTS) {
        viewModel<PlayerViewModel>(factory = remember(applicationContext) { YouTubePlayerViewModelFactory(applicationContext) })
    } else {
        null
    }
    // The deck key's shuffle reshuffles the session's queue, so the deck needs
    // the player even when the tap doesn't come through a browser action.
    LaunchedEffect(youtubePlayer) { vm.kodaPlayer = youtubePlayer }
    val anim = rememberDeckAnimations(ui.transport, ui.progress)
    val sounds = remember { MechSounds() }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var shelfOpen by rememberSaveable { mutableStateOf(false) }
    // Hidden diagnostics screen: five taps on the FOLDMAN wordmark.
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    // Settings (theme override): the gear on the deck.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val feel by rememberUpdatedState(ui)

    // A new tape drops into the deck on every track change (and when the app opens):
    // the same loading animation replays for NEXT/PREVIOUS, key press or swipe.
    // currentQueueId is the stable per-track identity (unlike the index, it does
    // not move when the queue is shuffled), and it changes with the source too,
    // so source switches still fire exactly once.
    // Directional: NEXT brings the new tape from the top, PREVIOUS from the bottom.
    var lastDirSource by remember { mutableStateOf<Source?>(null) }
    var lastQueuePos by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.source, ui.currentQueueId) {
        val pos = ui.queue.indexOfFirst { it.id == ui.currentQueueId }
        val n = ui.queue.size
        anim.insertDir = if (ui.source != lastDirSource || lastQueuePos == null || pos < 0 || n <= 1) {
            1f // First load or source switch: the default drop from the top.
        } else {
            val prev = lastQueuePos!!
            when {
                prev == n - 1 && pos == 0 -> 1f // Wrapped NEXT (last -> first).
                prev == 0 && pos == n - 1 -> -1f // Wrapped PREVIOUS (first -> last).
                pos >= prev -> 1f // NEXT: queue index grew.
                else -> -1f // PREVIOUS: queue index shrank.
            }
        }
        lastDirSource = ui.source
        lastQueuePos = pos
        anim.insert.snapTo(1f)
        anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
        if (feel.keySounds) sounds.autoStop()
    }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            if (feel.keySounds) sounds.autoStop()
            when (e) {
                DeckEvent.TapeEnd -> if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                DeckEvent.Eject -> {
                    if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    scope.launch {
                        anim.pop.animateTo(1f, tween(90))
                        anim.pop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                    shelfOpen = true
                }
            }
        }
    }

    val youtubeMusicActions = remember(vm, youtubePlayer) {
        YouTubeMusicActions(
            queryChanged = vm::setYouTubeMusicQuery,
            search = vm::searchYouTubeMusic,
            searchArtist = vm::searchArtist,
            openPlaylist = vm::openYouTubePlaylist,
            closePlaylist = vm::closeYouTubePlaylist,
            playSongs = { songs, index ->
                youtubePlayer?.let { vm.playYouTubeMusic(songs, index, it) }
            },
            shufflePlaylist = { playlist ->
                youtubePlayer?.let { vm.playYouTubePlaylistShuffled(playlist, it) }
            },
            refresh = vm::refreshYouTubeMusic,
        )
    }
    val podcastActions = remember(vm, youtubePlayer) {
        PodcastActions(
            openFeed = vm::openPodcastFeed,
            closeFeed = { vm.openPodcastFeed(null) },
            playEpisode = { episode ->
                youtubePlayer?.let { vm.playPodcastEpisode(episode, it) }
            },
            shuffleFeed = { feed ->
                youtubePlayer?.let { vm.playPodcastFeedShuffled(feed, it) }
            },
            refreshFeed = vm::refreshPodcastFeed,
            removeFeed = vm::removePodcastFeed,
            addUrlChanged = vm::setPodcastAddUrl,
            addFeed = vm::addPodcastFeed,
            showAddDialog = vm::showAddPodcastDialog,
        )
    }
    val shelfActions = remember(vm, youtubeMusicActions, podcastActions) {
        ShelfActions(
            selectSource = vm::selectSource,
            pickFolder = onPickFolder,
            playTrack = vm::playTrack,
            playQueueItem = vm::playQueueItem,
            togglePlay = vm::pauseKey,
            next = vm::next,
            previous = vm::previous,
            seekTo = vm::seekTo,
            toggleShuffle = vm::toggleShuffle,
            youtubeMusic = youtubeMusicActions,
            podcasts = podcastActions,
        )
    }
    val deck = remember(vm) { DeckControls(vm) }
    val fold = windowLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()

    CompositionLocalProvider(
        LocalMechSounds provides sounds.takeIf { ui.keySounds },
        LocalHaptics provides ui.haptics,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(DeckColors.BackdropHi, DeckColors.Backdrop), radius = 1800f)),
        ) {
            val density = LocalDensity.current
            // Posture: UNFOLDED phone (any orientation) always gets the
            // unfolded two-pane UI. FOLDED phone always gets the single-column
            // Cover UI. Keyed on the FoldingFeature state, not orientation.
            //
            // Unfolded = FoldingFeature is FLAT (fully open, any orientation).
            // The Tabletop case (HALF_OPENED + HORIZONTAL) is handled separately.
            // Fallback: no folding feature but large screen (>= 600dp) = unfolded
            // (covers emulators/tablets).
            val isTabletop = fold != null &&
                fold.state == FoldingFeature.State.HALF_OPENED &&
                fold.orientation == FoldingFeature.Orientation.HORIZONTAL
            val isUnfolded = !isTabletop && (
                (fold != null && fold.state == FoldingFeature.State.FLAT) ||
                (fold == null && maxWidth >= 600.dp)
            )
            val posture = when {
                isTabletop -> Posture.Tabletop
                isUnfolded -> {
                    // Unfolded: landscape gets the two-pane UI; portrait gets
                    // the vertical layout (deck full-width on top, queue below,
                    // no liner notes) — the "good unfolded UI" from before.
                    if (maxWidth > maxHeight) Posture.Unfolded else Posture.UnfoldedPortrait
                }
                // Folded: always the single-column Cover UI, regardless of
                // rotation. The orientation lock below keeps it portrait.
                else -> Posture.Cover
            }
            // Folded orientation lock: the folded screen stays portrait.
            // Unfolded allows rotation (two-pane works in both orientations).
            val activity = view.context as? android.app.Activity
            if (posture == Posture.Cover) {
                activity?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                activity?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            // Full screen: the system bars stay visible as transparent overlays over the
            // deck in every posture — no swipe needed to see them.
            LaunchedEffect(Unit) { view.showSystemBars(true) }
            when (posture) {
                // The cover screen is the player itself: edge to edge, system bars shown
                // as transparent overlays.
                Posture.Cover -> CoverWalkman(
                    ui, anim, deck,
                    onTapes = { shelfOpen = true },
                    onShowDiagnostics = { showDiagnostics = true },
                    enamel = enamel,
                    onShowSettings = { showSettings = true },
                    modifier = Modifier.fillMaxSize(),
                )
                // Unfolded: the left two-thirds hold the source row, deck and liner
                // notes; the right third is the dual-tab library panel (Up Next /
                // Your Library) with its own search.
                Posture.Unfolded -> Row(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceRow(ui, shelfActions, Modifier.fillMaxWidth().height(78.dp))
                        DeckBand(
                            ui, anim, deck, vm,
                            onShowDiagnostics = { showDiagnostics = true },
                            enamel = enamel,
                            onShowSettings = { showSettings = true },
                            Modifier.fillMaxWidth().weight(0.58f),
                        )
                        LinerNotesBand(ui, Modifier.fillMaxWidth().weight(0.42f))
                    }
                    LibraryPanel(ui, shelfActions, vm::playQueueItem, Modifier.weight(1f).fillMaxHeight())
                }
                // Unfolded portrait: vertical layout — deck full-width on top,
                // queue (Up Next) full-width below, no liner notes. This is the
                // "good unfolded UI" from before the posture-lock regression.
                Posture.UnfoldedPortrait -> Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SourceRow(ui, shelfActions, Modifier.fillMaxWidth().height(78.dp))
                    DeckBand(
                        ui, anim, deck, vm,
                        onShowDiagnostics = { showDiagnostics = true },
                        enamel = enamel,
                        onShowSettings = { showSettings = true },
                        Modifier.fillMaxWidth().weight(0.55f),
                    )
                    QueuePanel(ui, shelfActions, vm::playQueueItem, Modifier.fillMaxWidth().weight(0.45f))
                }
                Posture.Tabletop -> {
                    val (topH, gap) = with(density) {
                        fold!!.bounds.top.toDp() to (fold.bounds.bottom - fold.bounds.top).toDp()
                    }
                    Column(Modifier.fillMaxSize()) {
                        DeckBand(
                            ui, anim, deck, vm,
                            onShowDiagnostics = { showDiagnostics = true },
                            enamel = enamel,
                            onShowSettings = { showSettings = true },
                            Modifier
                                .height(topH)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        )
                        Spacer(Modifier.height(gap))
                        LibraryBand(
                            ui, shelfActions, vm::playQueueItem,
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            if (shelfOpen && posture == Posture.Cover) {
                // Hoisted: the sheet's content lambda is a ColumnScope, which loses the
                // BoxWithConstraints receiver that maxHeight belongs to — and inside the
                // sheet, WindowInsets.statusBars reads as 0 because ModalBottomSheet
                // pre-emptively consumes the top insets for its own contentWindowInsets
                // (BottomSheetDefaults.windowInsets). Captured here, where insets are reliable.
                val screenH = maxHeight
                val statusBarPx = WindowInsets.statusBars.getTop(density)
                val navBarPx = WindowInsets.navigationBars.getBottom(density)
                ModalBottomSheet(
                    onDismissRequest = { shelfOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    // Unified with the sheet's own top: the drag-handle zone shares
                    // the brown tone the sheet background starts with.
                    containerColor = if (dark) Color(0xFF100D0B) else Color(0xFF2A1F17),
                    // We own inset handling: M3's default contentWindowInsets (safeDrawing
                    // top+bottom) pads the sheet chrome, which would push the surface
                    // behind the status bar at full expansion. The nav bar keeps its
                    // padding on the content below.
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    // The sheet grows with upward scroll, stopping right before the
                    // status bar, and shrinks back on downward scroll at the top.
                    val shelfListState = rememberLazyListState()
                    // M3's default drag handle (4.dp bar + 2x22.dp padding = 48.dp, per
                    // material3 1.4.0 tokens) sits above our content inside the sheet
                    // surface; the cap accounts for it so the surface stops exactly at
                    // the status bar's bottom edge.
                    val handlePx = with(density) { 48.dp.toPx() }
                    val minHpx = with(density) { (screenH * 0.8f).toPx() }
                    val maxHpx = (with(density) { screenH.toPx() } - statusBarPx - handlePx - navBarPx)
                        .coerceAtLeast(minHpx)
                    var sheetHpx by remember { mutableStateOf<Float?>(null) }
                    val growConnection = remember(minHpx, maxHpx) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                val cur = sheetHpx ?: minHpx
                                return when {
                                    // Drag up: the window grows with the gesture.
                                    available.y < 0 && cur < maxHpx -> {
                                        val take = minOf(-available.y, maxHpx - cur)
                                        sheetHpx = cur + take
                                        Offset(0f, -take)
                                    }
                                    // Drag down at the very top of the list: shrink back down.
                                    available.y > 0 && cur > minHpx &&
                                        shelfListState.firstVisibleItemIndex == 0 &&
                                        shelfListState.firstVisibleItemScrollOffset == 0 -> {
                                        val take = minOf(available.y, cur - minHpx)
                                        sheetHpx = cur - take
                                        Offset(0f, take)
                                    }
                                    else -> Offset.Zero
                                }
                            }
                        }
                    }
                    TapeShelf(
                        ui,
                        ShelfActions(
                            selectSource = { vm.selectSource(it) },
                            pickFolder = onPickFolder,
                            playTrack = { vm.playTrack(it); shelfOpen = false },
                            playQueueItem = { vm.playQueueItem(it) },
                            togglePlay = vm::pauseKey,
                            next = vm::next,
                            previous = vm::previous,
                            seekTo = vm::seekTo,
                            toggleShuffle = vm::toggleShuffle,
                            youtubeMusic = youtubeMusicActions,
                            podcasts = podcastActions,
                        ),
                        Modifier.fillMaxWidth()
                            .height(with(density) { (sheetHpx ?: minHpx).toDp() })
                            .nestedScroll(growConnection)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                        listState = shelfListState,
                    )
                }
            }
            // Hidden diagnostics screen: five taps on the FOLDMAN wordmark.
            // The gesture is the only entry point; nothing advertises it.
            if (showDiagnostics) {
                ModalBottomSheet(
                    onDismissRequest = { showDiagnostics = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color(0xFF14181D),
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    DiagnosticsScreen()
                }
            }
            // Settings: the theme override (System / Light / Dark), opened from
            // the gear on the deck.
            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = if (dark) Color(0xFF141110) else Color(0xFF2A1F17),
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) {
                    SettingsSheet(
                        themeMode = themeMode,
                        onThemeChange = { vm.setThemeMode(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private class YouTubePlayerViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass != PlayerViewModel::class.java) {
            throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return PlayerViewModel(context) as T
    }
}

/** Hides the status and navigation bars (a swipe from the edge brings them back briefly), or restores them. */
private fun android.view.View.showSystemBars(show: Boolean) {
    var ctx = context
    while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
    val window = (ctx as? android.app.Activity)?.window ?: return
    // Dark surfaces: force light status-bar icons/text and a dark nav bar
    // with light buttons (never white).
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = 0xFF141210.toInt() // DeckColors.Backdrop
    WindowCompat.getInsetsController(window, this).run {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Key actions, bundled so the layouts don't each need the view model. */
private class DeckControls(vm: DeckViewModel) {
    val play = vm::play
    val pause = vm::pauseKey
    val stop = vm::stop
    val rewind = vm::rewind
    val fastForward = vm::fastForward
    val next = vm::next
    val previous = vm::previous
    val volume = vm::setVolume
    val toggleShuffle = vm::toggleShuffle
}

private val Serif = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, color = Color(0xFFF1F3F6), letterSpacing = 2.sp)
/** Light engraved lettering for the enamel deck; readable on both enamel roles. */
private val OnEnamel: TextStyle
    get() = EngravedCaps.copy(color = Color(0xFFDCE6F2))

/** The folded phone: an upright portable player. */
@Composable
private fun CoverWalkman(
    ui: DeckUi,
    anim: DeckAnimations,
    deck: DeckControls,
    onTapes: () -> Unit,
    onShowDiagnostics: () -> Unit,
    enamel: Color,
    onShowSettings: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val capH = maxHeight * 0.12f
        Row(Modifier.fillMaxSize().paintedBody(enamel, capHeight = capH, corner = 0.dp, framed = false)) {
            Column(
                Modifier
                    .weight(0.73f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                    .padding(start = 20.dp, end = 14.dp)
                    // Blue-face swipe up: opens the TAPES sheet. The cassette's own
                    // gesture detector consumes touches starting inside the tape, so
                    // this only fires for swipes beginning on the blue face.
                    .pointerInput(onTapes) {
                        var totalY = 0f
                        var triggered = false
                        detectDragGestures(
                            onDragStart = { totalY = 0f; triggered = false },
                            onDragEnd = { totalY = 0f; triggered = false },
                            onDragCancel = { totalY = 0f; triggered = false },
                            onDrag = { change, dragAmount ->
                                if (!triggered) {
                                    totalY += dragAmount.y
                                    // Upward swipe of 60px: open tapes.
                                    if (totalY < -60f) {
                                        triggered = true
                                        change.consume()
                                        onTapes()
                                    }
                                }
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(capH)) {
                    // Inset from the corner so the display's rounded corner doesn't clip it.
                    HotlineButton(onTapes, Modifier.align(Alignment.TopStart).padding(top = 22.dp, start = 8.dp).size(44.dp, 22.dp))
                    Text("TAPES", style = OnEnamel.copy(fontSize = 7.sp), modifier = Modifier.align(Alignment.TopStart).padding(top = 48.dp, start = 14.dp))
                    // Settings gear, top-end of the cap.
                    SettingsGearButton(onShowSettings, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 10.dp).size(30.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("STEREO", style = Serif.copy(fontSize = 30.sp))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // Cassette gestures: tap = play/pause, swipe up = next,
                            // swipe down = previous. Consumes the gesture so the
                            // blue-face swipe doesn't fire.
                            .pointerInput(ui.transport == Transport.PLAYING, deck) {
                                detectTapGestures(
                                    onTap = {
                                        if (ui.transport == Transport.PLAYING) deck.pause() else deck.play()
                                    }
                                )
                            }
                            .pointerInput(deck) {
                                var totalY = 0f
                                var triggered = false
                                detectDragGestures(
                                    onDragStart = { totalY = 0f; triggered = false },
                                    onDragEnd = { totalY = 0f; triggered = false },
                                    onDragCancel = { totalY = 0f; triggered = false },
                                    onDrag = { change, dragAmount ->
                                        if (!triggered) {
                                            totalY += dragAmount.y
                                            if (totalY < -60f) {
                                                triggered = true
                                                change.consume()
                                                deck.next()
                                            } else if (totalY > 60f) {
                                                triggered = true
                                                change.consume()
                                                deck.previous()
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        TapeWindow(ui, anim, vertical = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    EjectArrow(onTapes, Modifier.size(30.dp, 58.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    listOf(ui.title, ui.artist).filter { it.isNotBlank() }.joinToString("  —  ").ifBlank { "NO TAPE" }.uppercase(),
                    style = OnEnamel.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                DiagnosticsWordmark("FOLDMAN", 40.sp, onFiveTap = onShowDiagnostics)
                Spacer(Modifier.height(20.dp))
            }
            SideStrip(ui, deck, Modifier.weight(0.27f).fillMaxHeight())
        }
    }
}

/** The silver strip down the side of the case: mic, LED, skip, keys, volume, phones. */
@Composable
private fun SideStrip(ui: DeckUi, deck: DeckControls, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val keyH = ((maxHeight - 400.dp) / 5 - 18.dp).coerceIn(34.dp, 64.dp)
        // Runs the full height of the screen; the padding keeps controls clear of the rounded corners.
        Column(
            Modifier
                .fillMaxSize()
                .chassis(corner = 0.dp, screws = false)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                // The mic grille sits a smidge higher; the key column below
                // shifts up with it, and the freed space flows into the
                // volume slider's weight, making it taller.
                .padding(start = 10.dp, end = 12.dp, top = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicGrille(Modifier.fillMaxWidth().height(26.dp))
            Text("MIC", style = EngravedCaps.copy(fontSize = 7.sp))
            Spacer(Modifier.height(8.dp))
            StatusLed(ui.transport)
            Spacer(Modifier.height(10.dp))
            // PREV | PLAY | NEXT | PAUSE | STOP·EJECT | SHUFFLE: the track-skip keys
            // take the REW/FF slots (the circular AMS buttons are gone); the freed
            // space flows into the volume slider's weight below, making it taller.
            TransportKeys(
                ui, deck.rewind, deck.play, deck.fastForward, deck.pause,
                onShuffle = { deck.toggleShuffle() },
                onPrevTrack = deck.previous, onNextTrack = deck.next,
                modifier = Modifier.fillMaxWidth(), keyHeight = keyH, vertical = true,
            )
            Spacer(Modifier.height(12.dp))
            Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
            VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
            PhonesJack(Modifier.size(18.dp))
            Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
        }
    }
}

/** The unfolded phone: the player laid down as a deck. */
@Composable
private fun DeckBand(
    ui: DeckUi,
    anim: DeckAnimations,
    deck: DeckControls,
    vm: DeckViewModel,
    onShowDiagnostics: () -> Unit,
    enamel: Color,
    onShowSettings: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.paintedBody(enamel, corner = 22.dp)) {
        // De-cramped: the blue deck takes a wider share from the silver end strip
        // so the dials and the cassette window can breathe. Top padding reduced
        // to lift the STEREO / FOLDMAN header slightly higher.
        Column(Modifier.weight(0.80f).fillMaxHeight().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("STEREO", style = Serif.copy(fontSize = 24.sp))
                Spacer(Modifier.weight(1f))
                DiagnosticsWordmark("FOLDMAN", 26.sp, onFiveTap = onShowDiagnostics)
                Spacer(Modifier.width(8.dp))
                SettingsGearButton(onShowSettings, Modifier.size(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Dial compartment narrowed a touch: the freed width goes to the cassette window.
                Column(Modifier.width(162.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Row(Modifier.fillMaxWidth().weight(1f).bezel().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 0)
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 1)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.bezel().padding(5.dp)) { TapeCounter(anim.reels, digitWidth = 16.dp) }
                        Spacer(Modifier.width(14.dp))
                        MiniButton("RESET", { anim.reels.resetCounter() }, diameter = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        StatusLed(ui.transport)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TapeWindow(ui, anim)
                }
                Spacer(Modifier.width(14.dp))
                // Nudged right so the enlarged tape window keeps its clearance.
                EjectArrow({ deck.stop() }, Modifier.size(26.dp, 50.dp).offset(x = 6.dp))
            }
            // Pushed down: opens up the visual interior of the cassette module.
            Spacer(Modifier.height(24.dp))
            // PREV | PLAY | NEXT | PAUSE | STOP·EJECT: the track-skip keys take the
            // FF/REW slots (the small circular AMS buttons are gone on the unfolded deck).
            Row(verticalAlignment = Alignment.Bottom) {
                TransportKeys(
                    ui, deck.rewind, deck.play, deck.fastForward, deck.pause,
                    onPrevTrack = deck.previous, onNextTrack = deck.next,
                    modifier = Modifier.weight(1f), keyHeight = 58.dp,
                )
            }
        }
        // Silver end strip: mic, switches, volume and phones (slimmed to fund the deck).
        Row(
            Modifier
                .weight(0.20f)
                .fillMaxHeight()
                .padding(4.dp)
                .chassis(corner = 18.dp, screws = false)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                MicGrille(Modifier.fillMaxWidth().height(30.dp))
                Text("MIC", style = EngravedCaps.copy(fontSize = 7.sp))
                Spacer(Modifier.weight(1f))
                SlideSwitch(ui.keySounds, vm::setKeySounds, "CLICK", height = 54.dp)
                Spacer(Modifier.height(8.dp))
                SlideSwitch(ui.haptics, vm::setHaptics, "HAPTIC", height = 54.dp)
                Spacer(Modifier.weight(1f))
                PhonesJack(Modifier.size(18.dp))
                Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                // VOLUME sits at the very top, level with the mic grille; the
                // slider track extends upward into the freed space.
                Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
                VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
