package dev.foldman.apple

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock

/**
 * Hosts MusicKit's player in its own process (":apple" in the manifest). Apple's 2021 native
 * library can crash, and in here a crash only stops the music: the app sees the connection drop,
 * the system restarts this service, and [AppleKitBackend] puts the song back where it was.
 *
 * The app drives it with [Messenger] messages and gets a [ApplePlayerProtocol.MSG_STATE] snapshot
 * four times a second while a player exists.
 */
class ApplePlayerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var client: Messenger? = null
    private var developerToken: String? = null
    private var userToken: String? = null
    private var player: KitPlayer? = null
    /** Position to restore once the first song of a re-created queue has loaded. */
    private var pendingSeek = -1L

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val data = msg.data
            runCatching {
                when (msg.what) {
                    ApplePlayerProtocol.MSG_REGISTER -> client = msg.replyTo
                    ApplePlayerProtocol.MSG_TOKENS -> {
                        developerToken = data.getString(ApplePlayerProtocol.DEV)
                        userToken = data.getString(ApplePlayerProtocol.USER)
                    }
                    ApplePlayerProtocol.MSG_PLAY_SONGS -> playSongs(data)
                    ApplePlayerProtocol.MSG_PLAY -> player?.play()
                    ApplePlayerProtocol.MSG_PAUSE -> player?.pause()
                    ApplePlayerProtocol.MSG_SEEK -> player?.seekTo(data.getLong(ApplePlayerProtocol.POSITION))
                    ApplePlayerProtocol.MSG_NEXT -> player?.skipToNext()
                    ApplePlayerProtocol.MSG_PREVIOUS -> player?.skipToPrevious()
                    ApplePlayerProtocol.MSG_SKIP_TO -> player?.skipTo(data.getLong(ApplePlayerProtocol.ID))
                }
            }.onFailure { report(it.message ?: it.javaClass.simpleName) }
        }
    })

    private val poll = object : Runnable {
        override fun run() {
            player?.let { send(snapshot(it)) }
            main.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onCreate() {
        super.onCreate()
        main.post(poll)
    }

    private fun playSongs(data: Bundle) {
        val ids = data.getStringArray(ApplePlayerProtocol.IDS)?.toList().orEmpty()
        if (ids.isEmpty()) return
        val p = player ?: run {
            val dev = developerToken ?: return report("No developer token")
            MusicKit.createPlayer(this, dev) { userToken.orEmpty() }?.also { player = it }
                ?: return report("MusicKit's player didn't start")
        }
        pendingSeek = data.getLong(ApplePlayerProtocol.POSITION, -1L)
        p.playSongs(ids, data.getInt(ApplePlayerProtocol.INDEX), data.getBoolean(ApplePlayerProtocol.PLAY, true))
    }

    private fun snapshot(p: KitPlayer): Bundle {
        val queue = p.queue
        val index = p.queueIndex
        val current = queue.getOrNull(index) ?: p.current
        val duration = p.durationMs.takeIf { it > 0 } ?: (current?.durationMs ?: 0L)
        if (pendingSeek > 0 && duration > 0) {
            p.seekTo(pendingSeek)
            pendingSeek = -1L
        }
        return Bundle().apply {
            putString(ApplePlayerProtocol.TITLE, current?.title)
            putString(ApplePlayerProtocol.ARTIST, current?.artist)
            putString(ApplePlayerProtocol.ALBUM, current?.album)
            putString(ApplePlayerProtocol.ART, current?.artworkUrl)
            putLong(ApplePlayerProtocol.DURATION, duration)
            putLong(ApplePlayerProtocol.POSITION, p.positionMs)
            putLong(ApplePlayerProtocol.AT, SystemClock.elapsedRealtime())
            putBoolean(ApplePlayerProtocol.PLAYING, p.isPlaying)
            putBoolean(ApplePlayerProtocol.BUFFERING, p.isBuffering)
            putBoolean(ApplePlayerProtocol.HAS_NEXT, p.canSkipNext)
            putBoolean(ApplePlayerProtocol.HAS_PREVIOUS, p.canSkipPrevious)
            putInt(ApplePlayerProtocol.INDEX, index)
            putLongArray(ApplePlayerProtocol.QUEUE_IDS, queue.map { it.queueId }.toLongArray())
            putStringArray(ApplePlayerProtocol.QUEUE_TITLES, queue.map { it.title.orEmpty() }.toTypedArray())
            putStringArray(ApplePlayerProtocol.QUEUE_ARTISTS, queue.map { it.artist.orEmpty() }.toTypedArray())
            putStringArray(ApplePlayerProtocol.QUEUE_ART, queue.map { it.artworkUrl.orEmpty() }.toTypedArray())
        }
    }

    private fun report(error: String) =
        send(Bundle().apply { putString(ApplePlayerProtocol.ERROR, error) }, ApplePlayerProtocol.MSG_ERROR)

    private fun send(data: Bundle, what: Int = ApplePlayerProtocol.MSG_STATE) {
        val c = client ?: return
        try {
            c.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            client = null
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.release() } }
        player = null
        super.onDestroy()
    }
}

/** Message codes and bundle keys shared by [ApplePlayerService] and [AppleKitBackend]. */
internal object ApplePlayerProtocol {
    const val MSG_REGISTER = 1
    const val MSG_TOKENS = 2
    const val MSG_PLAY_SONGS = 3
    const val MSG_PLAY = 4
    const val MSG_PAUSE = 5
    const val MSG_SEEK = 6
    const val MSG_NEXT = 7
    const val MSG_PREVIOUS = 8
    const val MSG_SKIP_TO = 9
    const val MSG_STATE = 100
    const val MSG_ERROR = 101

    const val DEV = "dev"
    const val USER = "user"
    const val IDS = "ids"
    const val INDEX = "index"
    const val PLAY = "play"
    const val POSITION = "position"
    const val ID = "id"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val ART = "art"
    const val DURATION = "duration"
    const val AT = "at"
    const val PLAYING = "playing"
    const val BUFFERING = "buffering"
    const val HAS_NEXT = "hasNext"
    const val HAS_PREVIOUS = "hasPrevious"
    const val QUEUE_IDS = "queueIds"
    const val QUEUE_TITLES = "queueTitles"
    const val QUEUE_ARTISTS = "queueArtists"
    const val QUEUE_ART = "queueArt"
    const val ERROR = "error"
}
