package com.capsopasme.assistant.call

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File

/**
 * Speaks through the system TTS engine (MOSS-TTS-Nano when it is the preferred engine, any
 * engine otherwise). Pieces of an answer are queued as separate utterances, the engine streams
 * each one: the first sentence plays while the model is still writing the next.
 *
 * Main thread only. The engine plays the audio in its own process with the [AudioAttributes]
 * set here, which decide the route (speaker / headphones vs. earpiece / headset).
 */
class Speaker(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** the engine is bound (or failed: [available] false, answers are then only shown) */
        fun onSpeakerReady(available: Boolean)

        /** an utterance started playing */
        fun onSpeechStarted()

        /** everything queued has been played (or failed / timed out) */
        fun onSpeechDrained()

        /** an utterance failed ([TextToSpeech.ERROR_NOT_INSTALLED_YET]: the engine has no model) */
        fun onSpeechError(code: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initDone = false

    var available = false
        private set

    /** pieces queued before the engine was bound */
    private val waiting = ArrayList<String>()

    /** utterances sent and not finished yet */
    private val pending = HashSet<String>()

    /** bumped by [stop]: progress callbacks of older utterances are dropped */
    private var generation = 0
    private var seq = 0
    private var firstUtterance = true
    private var attributes: AudioAttributes = defaultAttributes()

    val busy: Boolean
        get() = pending.isNotEmpty() || (!initDone && waiting.isNotEmpty())

    fun init() {
        if (tts != null) return
        // the user's preferred engine; MOSS loads its model on the first request
        tts = TextToSpeech(context) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (initDone) return
        initDone = true
        available = status == TextToSpeech.SUCCESS
        if (!available) {
            Log.w(TAG, "TTS init failed: $status")
            waiting.clear()
            listener.onSpeakerReady(false)
            listener.onSpeechDrained()
            return
        }
        t.setOnUtteranceProgressListener(progress)
        t.setAudioAttributes(attributes)
        listener.onSpeakerReady(true)
        warmUp()
        val queued = ArrayList(waiting)
        waiting.clear()
        queued.forEach { speak(it) }
        if (queued.isEmpty()) checkDrained()
    }

    /** applies to utterances queued from now on */
    fun setAttributes(attrs: AudioAttributes) {
        attributes = attrs
        if (available) tts?.setAudioAttributes(attrs)
    }

    /** queue [text] (already cut into a piece); no-op without an engine */
    fun speak(text: String) {
        val clean = SpeechText.clean(text)
        if (!SpeechText.hasWords(clean)) return
        if (!initDone) {
            waiting.add(clean)
            return
        }
        val t = tts
        if (!available || t == null) return
        val id = "u${generation}_${++seq}"
        pending.add(id)
        if (t.speak(clean, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            Log.w(TAG, "speak() refused")
            checkDrained()
            return
        }
        armWatchdog()
    }

    /** Silences everything queued; no [Listener.onSpeechDrained] for it */
    fun stop() {
        generation++
        waiting.clear()
        val had = pending.isNotEmpty()
        pending.clear()
        main.removeCallbacks(watchdog)
        if (had) tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        available = false
    }

    /**
     * MOSS frees its model after being idle and needs a few seconds to load it again. Synthesizing
     * one character into a file (not played) while the user is still talking hides that.
     */
    private fun warmUp() {
        val t = tts ?: return
        val file = File(context.cacheDir, WARMUP_FILE)
        try {
            t.synthesizeToFile("好", Bundle(), file, WARMUP_ID)
        } catch (e: Exception) {
            Log.w(TAG, "warm-up failed", e)
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                if (utteranceId in pending) {
                    firstUtterance = false
                    armWatchdog()
                    listener.onSpeechStarted()
                }
            }
        }

        override fun onDone(utteranceId: String) = finished(utteranceId)

        @Deprecated("deprecated in the platform API")
        override fun onError(utteranceId: String) = finished(utteranceId)

        override fun onError(utteranceId: String, errorCode: Int) {
            main.post { if (utteranceId in pending) listener.onSpeechError(errorCode) }
            finished(utteranceId)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
    }

    private fun finished(id: String) {
        main.post {
            if (id == WARMUP_ID) {
                File(context.cacheDir, WARMUP_FILE).delete()
                return@post
            }
            if (pending.remove(id)) {
                armWatchdog()
                checkDrained()
            }
        }
    }

    private fun checkDrained() {
        if (busy) return
        main.removeCallbacks(watchdog)
        listener.onSpeechDrained()
    }

    /** an engine that never reports back must not leave the call stuck in "speaking" */
    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        if (pending.isEmpty()) return
        main.postDelayed(watchdog, if (firstUtterance) FIRST_TIMEOUT_MS else PROGRESS_TIMEOUT_MS)
    }

    private val watchdog = Runnable {
        if (pending.isEmpty()) return@Runnable
        Log.w(TAG, "TTS made no progress, giving up on ${pending.size} utterance(s)")
        stop()
        listener.onSpeechDrained()
    }

    companion object {
        private const val TAG = "Speaker"
        private const val WARMUP_ID = "warmup"
        private const val WARMUP_FILE = "tts-warmup.wav"

        /** the first utterance may wait for the engine to load its model */
        private const val FIRST_TIMEOUT_MS = 30_000L

        /** no start / done for this long: the engine is stuck */
        private const val PROGRESS_TIMEOUT_MS = 20_000L

        fun defaultAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
