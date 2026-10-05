package com.capsopasme.assistant.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short, soft tones synthesized on the fly (no audio assets): "your turn" while the screen is
 * off, and the hang-up sound. Each one is a static AudioTrack released right after it played.
 */
object Earcon {

    private const val RATE = 24_000
    private val main = Handler(Looper.getMainLooper())

    /** two quick rising notes; @return its length in ms */
    fun yourTurn(attrs: AudioAttributes): Long = play(attrs, listOf(784f to 70, 1046f to 90), 0.22f)

    /** two falling notes */
    fun hangUp(attrs: AudioAttributes): Long = play(attrs, listOf(880f to 110, 587f to 170), 0.25f)

    private fun play(attrs: AudioAttributes, notes: List<Pair<Float, Int>>, volume: Float): Long {
        val gapMs = 25
        val totalMs = notes.sumOf { it.second } + gapMs * (notes.size - 1)
        val pcm = ShortArray(RATE * totalMs / 1000)
        var pos = 0
        for ((index, note) in notes.withIndex()) {
            val (freq, ms) = note
            val n = RATE * ms / 1000
            val fade = min(n / 4, RATE * 12 / 1000)
            for (i in 0 until n) {
                if (pos >= pcm.size) break
                // short fade in / out: no clicks
                val env = when {
                    i < fade -> i.toFloat() / fade
                    i > n - fade -> (n - i).toFloat() / fade
                    else -> 1f
                }
                val v = sin(2.0 * PI * freq * i / RATE) * env * volume
                pcm[pos++] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            if (index < notes.size - 1) pos += RATE * gapMs / 1000
        }
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ track.release() }, totalMs + 300L)
            totalMs.toLong()
        } catch (e: Exception) {
            Log.w("Earcon", "tone failed", e)
            0L
        }
    }
}
