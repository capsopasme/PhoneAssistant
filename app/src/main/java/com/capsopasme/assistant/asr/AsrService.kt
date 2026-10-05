/*
 * Adapted from the fcitx5-android voice input (VoiceRecognitionService.kt)
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package com.capsopasme.assistant.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import java.io.File
import com.capsopasme.assistant.asr.AsrProtocol as P

/**
 * Runs VAD + offline ASR in the isolated `:asr` process.
 *
 * Single-utterance mode: audio streams in from the main process, VAD finds the end of the
 * command, that segment is decoded once and sent back as [P.EVT_FINAL], immediately followed by
 * [P.EVT_DONE]. While the user is still speaking, the current sentence is re-decoded now and then
 * and sent as [P.EVT_PARTIAL] for the live preview.
 *
 * Nothing here runs unless a client is bound and streaming: no timers, no polling.
 */
class AsrService : Service() {

    private lateinit var worker: Handler

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        // copy the message, the original is recycled after handleMessage returns
        worker.sendMessage(Message.obtain(msg))
        true
    })

    override fun onCreate() {
        super.onCreate()
        worker = Handler(workerLooper) { msg ->
            try {
                handleWork(msg)
            } catch (e: Throwable) {
                Log.e(TAG, "worker error", e)
                reply(msg.replyTo ?: session?.replyTo, P.EVT_ERROR, msg.arg1) {
                    putString(P.KEY_MESSAGE, e.localizedMessage ?: e.javaClass.simpleName)
                }
                resetSession()
            }
            true
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        worker.post { resetSession() }
        return false
    }

    override fun onDestroy() {
        worker.post {
            resetSession()
            // Default: keep the engine. Once unbound this process is cached and frozen by the
            // system, so the loaded model costs no CPU and no power, and lmkd reclaims it when
            // memory is actually needed. With "release after use" on, free it right away.
            if (!keepLoaded) {
                releaseEngine()
                vad?.release()
                vad = null
                vadKey = null
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // worker thread state

    private class Session(
        val id: Int,
        val replyTo: Messenger?,
        val partial: Boolean,
        /** force-cut speech longer than this, see [AsrEngine.createVad] */
        val maxSegmentSamples: Int,
        /** call mode, see [P.KEY_CONTINUOUS] */
        val continuous: Boolean,
        /**
         * a segment at least this long was cut by the VAD's speech length limit (it then splits
         * at the first short pause), not ended by a real pause
         */
        val softCutSamples: Int,
        /** the pause that ends the turn, see [P.KEY_SILENCE_MS] */
        val silenceSamples: Int,
    ) {
        val buffer = FloatRingBuffer()

        /** samples in [buffer] already fed to VAD */
        var fed = 0
        var speechStarted = false
        var speechStart = 0
        var lastPartialAt = 0L
        var lastPartialText = ""
        var lastPartialFed = 0
        var lastPartialCost = 0L

        // continuous mode
        /** text of the pieces cut off so far */
        var committed = ""

        /** the last piece was cut, not ended: waiting for more speech or for the pause */
        var awaitingMore = false

        /** silence fed to the VAD since the last cut piece */
        var silenceAfterCut = 0

        /** the current piece is being force-cut by this service (model window full) */
        var forcedCut = false
    }

    private var session: Session? = null

    private fun handleWork(msg: Message) {
        when (msg.what) {
            P.MSG_START -> start(msg)
            P.MSG_AUDIO -> audio(msg)
            P.MSG_STOP -> stop(msg.arg1, cancel = false)
            P.MSG_CANCEL -> stop(msg.arg1, cancel = true)
            P.MSG_SELF_TEST -> selfTest(msg)
        }
    }

    private fun ensureEngine(model: SpeechModel, replyTo: Messenger?, id: Int): AsrEngine {
        cachedEngine?.let { if (it.model == model) return it }
        reply(replyTo, P.EVT_LOADING, id)
        releaseEngine()
        return AsrEngine.create(this, model).also { cachedEngine = it }
    }

    private fun ensureVad(model: SpeechModel, silenceMs: Int): Vad {
        val k = model to silenceMs
        vad?.let { if (vadKey == k) return it }
        vad?.release()
        vad = null
        return AsrEngine.createVad(this, model, silenceMs).also {
            vad = it
            vadKey = k
        }
    }

    private fun start(msg: Message) {
        val data = msg.data
        val model = SpeechModel.fromName(data.getString(P.KEY_MODEL))
        val silenceMs = data.getInt(P.KEY_SILENCE_MS, 700)
        keepLoaded = data.getBoolean(P.KEY_KEEP_LOADED, true)
        resetSession()
        val engine = ensureEngine(model, msg.replyTo, msg.arg1)
        ensureVad(model, silenceMs).reset()
        session = Session(
            msg.arg1, msg.replyTo,
            partial = data.getBoolean(P.KEY_PARTIAL, true),
            maxSegmentSamples = (model.maxSegmentSeconds * P.SAMPLE_RATE).toInt(),
            continuous = data.getBoolean(P.KEY_CONTINUOUS, false),
            softCutSamples = ((AsrEngine.vadMaxSpeechSeconds(model) - 1f) * P.SAMPLE_RATE).toInt(),
            silenceSamples = silenceMs * P.SAMPLE_RATE / 1000,
        )
        reply(msg.replyTo, P.EVT_READY, msg.arg1) {
            putString(P.KEY_BACKEND, engine.backendName)
            putLong(P.KEY_LOAD_MS, engine.loadMillis)
        }
    }

    private fun audio(msg: Message) {
        val s = session ?: return
        if (s.id != msg.arg1) return
        val pcm = msg.data.getFloatArray(P.KEY_PCM) ?: return
        val engine = cachedEngine ?: return
        val vad = vad ?: return
        s.buffer.append(pcm)

        while (s.fed + AsrEngine.VAD_WINDOW <= s.buffer.size) {
            vad.acceptWaveform(s.buffer.copyOfRange(s.fed, s.fed + AsrEngine.VAD_WINDOW))
            s.fed += AsrEngine.VAD_WINDOW
            if (!s.speechStarted && vad.isSpeechDetected()) {
                s.speechStarted = true
                s.awaitingMore = false
                // include ~0.3s before the detected onset
                s.speechStart = (s.fed - PRE_ROLL).coerceAtLeast(0)
                s.lastPartialAt = 0L
                s.lastPartialFed = s.speechStart
                reply(s.replyTo, P.EVT_SPEECH_START, s.id)
            }
            if (s.speechStarted && s.fed - s.speechStart >= s.maxSegmentSamples) {
                // QNN models have a fixed input length and silently truncate anything longer
                s.forcedCut = true
                vad.flush()
            }
            if (!vad.empty()) {
                if (!s.continuous) {
                    finishWithSegment(s, engine, vad)
                    return
                }
                if (!continueWithSegment(s, engine, vad)) return
                continue
            }
            if (s.awaitingMore && !s.speechStarted) {
                s.silenceAfterCut += AsrEngine.VAD_WINDOW
                if (s.silenceAfterCut >= s.silenceSamples) {
                    // the cut piece was the end after all
                    finishWithText(s, s.committed, 0L)
                    return
                }
            }
        }

        if (s.speechStarted && s.partial) {
            val now = SystemClock.elapsedRealtime()
            // every partial re-decodes the whole sentence so far (on QNN always a full 20s graph):
            // wait at least 3x the last decode time, require new audio, skip while falling behind
            val interval = maxOf(PARTIAL_INTERVAL_MS, s.lastPartialCost * 3)
            if (now - s.lastPartialAt >= interval &&
                s.fed - s.lastPartialFed >= PARTIAL_MIN_NEW_AUDIO &&
                !worker.hasMessages(P.MSG_AUDIO)
            ) {
                s.lastPartialFed = s.fed
                val text = engine.recognize(s.buffer.copyOfRange(s.speechStart, s.fed))
                s.lastPartialAt = SystemClock.elapsedRealtime()
                s.lastPartialCost = s.lastPartialAt - now
                if (text.isNotEmpty() && text != s.lastPartialText) {
                    s.lastPartialText = text
                    val shown = joinText(s.committed, text)
                    reply(s.replyTo, P.EVT_PARTIAL, s.id) { putString(P.KEY_TEXT, shown) }
                }
            }
        } else if (!s.speechStarted && s.fed > KEEP_WHEN_SILENT * 2) {
            // nobody is talking: drop old audio, keep a short tail for pre-roll
            val drop = s.fed - KEEP_WHEN_SILENT
            s.buffer.dropFront(drop)
            s.fed -= drop
        }
    }

    /** Decode the first finished segment and end the session: one command per session */
    private fun finishWithSegment(s: Session, engine: AsrEngine, vad: Vad) {
        val segment = vad.front()
        vad.pop()
        val t0 = SystemClock.elapsedRealtime()
        val text = engine.recognize(segment.samples)
        val cost = SystemClock.elapsedRealtime() - t0
        // never log the recognized text itself
        Log.d(TAG, "segment ${segment.samples.size} samples -> ${text.length} chars in ${cost}ms")
        finishWithText(s, joinText(s.committed, text), cost)
    }

    private fun finishWithText(s: Session, text: String, decodeMs: Long) {
        reply(s.replyTo, P.EVT_FINAL, s.id) {
            putString(P.KEY_TEXT, text)
            putLong(P.KEY_DECODE_MS, decodeMs)
        }
        reply(s.replyTo, P.EVT_DONE, s.id)
        resetSession()
    }

    /**
     * Continuous mode: decode the finished segment. A piece cut because it got too long is kept
     * and the session goes on (the speaker hasn't paused); a piece ended by a real pause finishes
     * the turn with everything said.
     *
     * @return true if the session goes on
     */
    private fun continueWithSegment(s: Session, engine: AsrEngine, vad: Vad): Boolean {
        val segment = vad.front()
        vad.pop()
        val cut = s.forcedCut || segment.samples.size >= s.softCutSamples
        s.forcedCut = false
        val t0 = SystemClock.elapsedRealtime()
        val text = engine.recognize(segment.samples)
        val cost = SystemClock.elapsedRealtime() - t0
        Log.d(TAG, "piece ${segment.samples.size} samples (cut=$cut) -> ${text.length} chars in ${cost}ms")
        if (!cut) {
            finishWithText(s, joinText(s.committed, text), cost)
            return false
        }
        s.committed = joinText(s.committed, text)
        // the piece is decoded: its audio is no longer needed for partials
        s.buffer.dropFront(s.fed)
        s.fed = 0
        s.speechStarted = false
        s.speechStart = 0
        s.lastPartialAt = 0L
        s.lastPartialFed = 0
        s.lastPartialText = ""
        s.awaitingMore = true
        s.silenceAfterCut = 0
        if (s.partial && s.committed.isNotEmpty()) {
            val shown = s.committed
            reply(s.replyTo, P.EVT_PARTIAL, s.id) { putString(P.KEY_TEXT, shown) }
        }
        return true
    }

    private fun stop(id: Int, cancel: Boolean) {
        val s = session ?: return
        if (s.id != id) return
        val vad = vad
        val engine = cachedEngine
        if (!cancel && vad != null && engine != null) {
            // manual stop: feed the incomplete last window, then force the trailing speech out
            if (s.fed < s.buffer.size) {
                vad.acceptWaveform(s.buffer.copyOfRange(s.fed, s.buffer.size))
                s.fed = s.buffer.size
            }
            vad.flush()
            if (!vad.empty()) {
                finishWithSegment(s, engine, vad)
                return
            }
            if (s.committed.isNotEmpty()) {
                finishWithText(s, s.committed, 0L)
                return
            }
        }
        reply(s.replyTo, P.EVT_DONE, s.id)
        resetSession()
    }

    private fun resetSession() {
        session = null
        vad?.reset()
    }

    private fun selfTest(msg: Message) {
        val model = SpeechModel.fromName(msg.data.getString(P.KEY_MODEL))
        resetSession()
        val engine = ensureEngine(model, msg.replyTo, msg.arg1)
        val wav = File(ModelManager.modelDir(this, model), model.testWav)
        val samples = if (wav.isFile) WavReader.readMono16k(wav) else null
        if (samples == null) {
            reply(msg.replyTo, P.EVT_ERROR, msg.arg1) {
                putString(P.KEY_MESSAGE, "模型目录里没有测试音频")
            }
            return
        }
        // first run warms up, second one is measured
        engine.recognize(samples)
        val t0 = SystemClock.elapsedRealtime()
        val text = engine.recognize(samples)
        val cost = SystemClock.elapsedRealtime() - t0
        reply(msg.replyTo, P.EVT_TEST_RESULT, msg.arg1) {
            putString(P.KEY_TEXT, text)
            putString(P.KEY_BACKEND, engine.backendName)
            putLong(P.KEY_LOAD_MS, engine.loadMillis)
            putLong(P.KEY_DECODE_MS, cost)
            putLong(P.KEY_AUDIO_MS, samples.size * 1000L / P.SAMPLE_RATE)
        }
    }

    private inline fun reply(to: Messenger?, what: Int, id: Int, fill: Bundle.() -> Unit = {}) {
        to ?: return
        val m = Message.obtain(null, what, id, 0)
        m.data = Bundle().apply(fill)
        try {
            to.send(m)
        } catch (e: RemoteException) {
            Log.w(TAG, "client gone", e)
            if (session?.replyTo == to) session = null
        }
    }

    companion object {
        private const val TAG = "AsrService"
        private const val PRE_ROLL = P.SAMPLE_RATE * 3 / 10
        private const val KEEP_WHEN_SILENT = P.SAMPLE_RATE / 2
        private const val PARTIAL_INTERVAL_MS = 400L
        private const val PARTIAL_MIN_NEW_AUDIO = P.SAMPLE_RATE * 3 / 10

        /**
         * One worker thread for the whole process, never quit: the engine outlives service
         * instances, and serializing every engine access on one thread rules out a release on
         * one thread while another is decoding (native use-after-free).
         */
        private val workerLooper: Looper by lazy {
            HandlerThread("asr-worker").apply { start() }.looper
        }

        // only touched on the worker thread
        private var cachedEngine: AsrEngine? = null
        private var vad: Vad? = null
        private var vadKey: Pair<SpeechModel, Int>? = null
        private var keepLoaded = true

        private fun releaseEngine() {
            cachedEngine?.release()
            cachedEngine = null
        }
    }
}

/** Pieces of one utterance: Chinese runs together, Latin words get a space */
internal fun joinText(a: String, b: String): String {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val l = a.last()
    val r = b.first()
    return if (l.isLetterOrDigit() && l.code < 128 && r.isLetterOrDigit() && r.code < 128) "$a $b" else a + b
}

/** Growable float buffer with cheap append and drop-from-front */
internal class FloatRingBuffer {
    private var data = FloatArray(P.SAMPLE_RATE * 4)
    private var start = 0
    var size = 0
        private set

    fun append(src: FloatArray) {
        if (start + size + src.size > data.size) {
            if (size + src.size <= data.size / 2) {
                System.arraycopy(data, start, data, 0, size)
            } else {
                val newData = FloatArray(maxOf(data.size * 2, size + src.size))
                System.arraycopy(data, start, newData, 0, size)
                data = newData
            }
            start = 0
        }
        System.arraycopy(src, 0, data, start + size, src.size)
        size += src.size
    }

    fun copyOfRange(from: Int, to: Int): FloatArray {
        require(from in 0..to && to <= size)
        return data.copyOfRange(start + from, start + to)
    }

    fun dropFront(n: Int) {
        val k = n.coerceIn(0, size)
        start += k
        size -= k
        if (size == 0) start = 0
    }
}
