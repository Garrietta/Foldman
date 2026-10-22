package com.ivor.ivormusic.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Hidden on-device diagnostics for Foldman's playback pipeline, built for the
 * podcast-playback investigation: the device is the only place the failure
 * reproduces, so the pipeline records what actually happened there.
 *
 * In-memory only (nothing is persisted or uploaded): the last podcast play
 * attempt with its failure verbatim, plus a rolling ring buffer of the most
 * recent playback/pipeline events. Foldman's hidden diagnostics screen
 * (five taps on the FOLDMAN wordmark) renders both and can share them as text.
 */
object PlaybackDiagnostics {

    /** One user-initiated podcast play, from tap to outcome. */
    data class PodcastAttempt(
        val startedAtMs: Long = 0L,
        val feedTitle: String = "",
        val feedUrl: String = "",
        val episodeTitle: String = "",
        val enclosureUrl: String = "",
        /** Media id of the tapped episode ("podcast:<id>"), for matching pipeline events. */
        val episodeMediaId: String = "",
        /** The URI the player was actually handed (empty until resolved). */
        val resolvedUri: String = "",
        val errorCode: String = "",
        val errorMessage: String = "",
    ) {
        val hasAttempt: Boolean get() = startedAtMs > 0L
        val hasError: Boolean get() = errorCode.isNotEmpty() || errorMessage.isNotEmpty()
    }

    @Volatile
    var lastPodcastAttempt: PodcastAttempt? = null
        private set

    fun recordPodcastAttempt(attempt: PodcastAttempt) {
        lastPodcastAttempt = attempt
    }

    fun recordPodcastResolvedUri(episodeMediaId: String, uri: String) {
        val cur = lastPodcastAttempt
        if (cur != null && cur.episodeMediaId == episodeMediaId && cur.resolvedUri.isEmpty()) {
            lastPodcastAttempt = cur.copy(resolvedUri = uri)
        }
    }

    fun recordPodcastError(errorCode: String, errorMessage: String) {
        val cur = lastPodcastAttempt
        if (cur != null && !cur.hasError) {
            lastPodcastAttempt = cur.copy(errorCode = errorCode, errorMessage = errorMessage)
        }
    }

    private const val MAX_ENTRIES = 200
    private val ring = ArrayDeque<String>(MAX_ENTRIES + 8)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Appends a timestamped event to the ring buffer (and logcat). Thread-safe. */
    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        ring.addLast(line)
        while (ring.size > MAX_ENTRIES) ring.removeFirst()
        Log.i("FoldmanDiag", line)
    }

    /** Newest-last snapshot of the ring buffer. */
    @Synchronized
    fun snapshot(): List<String> = ring.toList()

    @Synchronized
    fun clear() {
        ring.clear()
        lastPodcastAttempt = null
    }

    fun formatTime(ms: Long): String =
        if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms)) else "—"
}
