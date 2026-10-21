package dev.foldman.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesised mechanism noises — the clack of a key latching, the softer tick of it releasing and
 * the thunk of the auto-stop — so there are no sound files to ship.
 */
class MechSounds {
    private val rate = 44100
    private val latch = track(synth(0.055f, thump = 95f, ring = 2300f, click = 0.9f, gain = 0.55f))
    private val release = track(synth(0.03f, thump = 180f, ring = 3400f, click = 0.5f, gain = 0.3f))
    private val thunk = track(synth(0.09f, thump = 70f, ring = 1500f, click = 0.6f, gain = 0.7f))

    fun keyDown() = play(latch)
    fun keyUp() = play(release)
    fun autoStop() = play(thunk)

    private fun play(t: AudioTrack?) {
        t ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun synth(seconds: Float, thump: Float, ring: Float, click: Float, gain: Float): ShortArray {
        val n = (rate * seconds).toInt()
        val rnd = Random(3)
        var prevNoise = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val white = rnd.nextDouble(-1.0, 1.0)
            // First difference of white noise: a crude high-pass for a crisp plastic transient.
            val noise = (white - prevNoise) * 0.5
            prevNoise = white
            val s = click * noise * exp(-t / 0.0022) +
                0.9 * sin(2 * PI * thump * t) * exp(-t / 0.014) +
                0.25 * sin(2 * PI * ring * t) * exp(-t / 0.005)
            (s * gain * 32767 * 0.6).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun track(pcm: ShortArray): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.getOrNull()

    fun release() {
        listOf(latch, release, thunk).forEach { it?.release() }
    }
}

val LocalMechSounds = staticCompositionLocalOf<MechSounds?> { null }
