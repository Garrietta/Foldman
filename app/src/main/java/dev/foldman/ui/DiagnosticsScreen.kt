package dev.foldman.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivor.ivormusic.diagnostics.PlaybackDiagnostics
import dev.foldman.BuildConfig

/**
 * Hidden diagnostics screen (five taps on the FOLDMAN wordmark): app version
 * + head commit, the last podcast play attempt with its failure verbatim, and
 * the rolling playback-event ring buffer. Everything is read honestly from the
 * in-memory [PlaybackDiagnostics] — "no attempt yet" states are shown as such,
 * never invented.
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(PlaybackDiagnostics.lastPodcastAttempt) }
    var events by remember { mutableStateOf(PlaybackDiagnostics.snapshot()) }
    fun refresh() {
        attempt = PlaybackDiagnostics.lastPodcastAttempt
        events = PlaybackDiagnostics.snapshot()
    }

    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("Diagnostics", style = Header)
        Text(
            "Hidden screen — five taps on the FOLDMAN wordmark. " +
                "Record what a failing podcast does, then Share it.",
            style = Sub,
        )
        Spacer(Modifier.height(12.dp))

        DiagSection("App") {
            DiagRow("Version", BuildConfig.VERSION_NAME)
            DiagRow("Commit", BuildConfig.GIT_COMMIT)
        }

        DiagSection("Last podcast attempt") {
            val a = attempt
            if (a == null || !a.hasAttempt) {
                Text("No podcast attempt recorded yet.", style = Sub)
            } else {
                DiagRow("Started", PlaybackDiagnostics.formatTime(a.startedAtMs))
                DiagRow("Feed", a.feedTitle.ifEmpty { "—" })
                DiagRow("Feed URL", a.feedUrl.ifEmpty { "—" })
                DiagRow("Episode", a.episodeTitle.ifEmpty { "—" })
                DiagRow("Enclosure", a.enclosureUrl.ifEmpty { "—" })
                DiagRow("Resolved URI", a.resolvedUri.ifEmpty { "(none yet)" })
                if (a.hasError) {
                    DiagRow("Error", a.errorCode.ifEmpty { "—" })
                    DiagRow("Message", a.errorMessage.ifEmpty { "—" })
                } else {
                    Text("No error recorded for this attempt.", style = Sub)
                }
            }
        }

        DiagSection("Event log (${events.size})") {
            if (events.isEmpty()) {
                Text("Log is empty.", style = Sub)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(events.asReversed()) { line ->
                        Text(
                            line,
                            style = Sub.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                            maxLines = 4,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                refresh()
                val text = buildDiagnosticsText()
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        "Share diagnostics",
                    )
                )
            }) { Text("Share") }
            TextButton(onClick = { refresh() }) { Text("Refresh") }
            TextButton(onClick = {
                PlaybackDiagnostics.clear()
                refresh()
            }) { Text("Clear") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DiagSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = Header.copy(fontSize = 13.sp))
    Spacer(Modifier.height(4.dp))
    content()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Sub, modifier = Modifier.weight(0.32f))
        Text(value, style = Sub.copy(color = Title.color), modifier = Modifier.weight(0.68f))
    }
    Spacer(Modifier.height(2.dp))
}

/** The exact text the Share button sends. */
fun buildDiagnosticsText(): String {
    val a = PlaybackDiagnostics.lastPodcastAttempt
    return buildString {
        appendLine("Foldman diagnostics")
        appendLine("App: ${BuildConfig.VERSION_NAME} (commit ${BuildConfig.GIT_COMMIT})")
        appendLine()
        appendLine("Last podcast attempt:")
        if (a == null || !a.hasAttempt) {
            appendLine("  (none recorded)")
        } else {
            appendLine("  started: ${PlaybackDiagnostics.formatTime(a.startedAtMs)}")
            appendLine("  feed: ${a.feedTitle} <${a.feedUrl}>")
            appendLine("  episode: ${a.episodeTitle}")
            appendLine("  enclosure: ${a.enclosureUrl}")
            appendLine("  resolved URI: ${a.resolvedUri.ifEmpty { "(none)" }}")
            appendLine("  error: ${a.errorCode} ${a.errorMessage}")
        }
        appendLine()
        appendLine("Event log:")
        PlaybackDiagnostics.snapshot().forEach { appendLine("  $it") }
    }
}
