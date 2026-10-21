package dev.foldman.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Taps the local player's PCM so the VU needles follow the actual music. Levels are RMS in 0..1
 * per channel; [read] returns silence once buffers stop arriving (paused, stopped).
 */
@OptIn(UnstableApi::class)
object AudioLevels : TeeAudioProcessor.AudioBufferSink {
    @Volatile private var left = 0f
    @Volatile private var right = 0f
    @Volatile private var updatedAt = 0L
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT || channels < 1) return
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumL = 0.0
        var sumR = 0.0
        var frames = 0
        while (samples.remaining() >= channels) {
            val l = samples.get() / 32768.0
            val r = if (channels > 1) samples.get() / 32768.0 else l
            repeat(channels - 2) { samples.get() }
            sumL += l * l
            sumR += r * r
            frames++
        }
        if (frames == 0) return
        left = sqrt(sumL / frames).toFloat()
        right = sqrt(sumR / frames).toFloat()
        updatedAt = SystemClock.elapsedRealtime()
    }

    fun read(): Pair<Float, Float> =
        if (SystemClock.elapsedRealtime() - updatedAt > 250) 0f to 0f else left to right
}
