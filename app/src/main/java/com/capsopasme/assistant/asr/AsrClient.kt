/*
 * Adapted from the fcitx5-android voice input (VoiceClient.kt)
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package com.capsopasme.assistant.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicInteger
import com.capsopasme.assistant.asr.AsrProtocol as P

/**
 * Main-process side of the recognizer. Binds to [AsrService] in the `:asr` process.
 * All callbacks are delivered on the main thread.
 */
class AsrClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLoading() {}
        fun onReady(backend: String, loadMillis: Long) {}
        fun onSpeechStart() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String) {}
        fun onDone() {}
        fun onTestResult(text: String, backend: String, loadMillis: Long, decodeMillis: Long, audioMillis: Long) {}

        /** the recognizer process died, most likely a native crash during model init */
        fun onServiceCrashed() {}
    }

    @Volatile
    private var service: Messenger? = null

    @Volatile
    private var bound = false
    private val pending = ArrayDeque<Message>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSession = 0

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handleEvent(msg)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = Messenger(binder)
            while (pending.isNotEmpty()) send(pending.removeFirst())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pending.clear()
            if (activeSession != 0) {
                activeSession = 0
                listener.onServiceCrashed()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
            if (bound) {
                context.unbindService(this)
                bound = false
            }
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, AsrService::class.java),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )
    }

    fun unbind() {
        if (!bound) return
        activeSession = 0
        pending.clear()
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        bound = false
        service = null
    }

    private fun bindOrFail(): Boolean {
        bind()
        if (!bound) mainHandler.post { listener.onError("无法连接语音识别服务") }
        return bound
    }

    val isSessionActive: Boolean
        get() = activeSession != 0

    fun start(model: SpeechModel, partial: Boolean, silenceMs: Int, keepLoaded: Boolean) {
        if (!bindOrFail()) return
        activeSession = nextSessionId()
        post(P.MSG_START) {
            putString(P.KEY_MODEL, model.name)
            putBoolean(P.KEY_PARTIAL, partial)
            putInt(P.KEY_SILENCE_MS, silenceMs)
            putBoolean(P.KEY_KEEP_LOADED, keepLoaded)
        }
    }

    /** May be called from any thread (the capture thread) */
    fun sendAudio(pcm: FloatArray) {
        val id = activeSession
        if (id == 0 || !bound) return
        val m = Message.obtain(null, P.MSG_AUDIO, id, 0)
        m.data = Bundle().apply { putFloatArray(P.KEY_PCM, pcm) }
        val s = service
        if (s == null) {
            // still connecting: queue it on the main thread
            mainHandler.post { pendingOrSend(m) }
        } else {
            m.replyTo = incoming
            try {
                s.send(m)
            } catch (_: RemoteException) {
            }
        }
    }

    private fun pendingOrSend(m: Message) {
        if (service != null) {
            send(m)
            return
        }
        if (!bound) return
        // the service doesn't come up: keep the queue bounded (~60s of audio)
        if (m.what == P.MSG_AUDIO && pending.size >= MAX_PENDING_AUDIO) return
        pending.addLast(m)
    }

    /** Force the end of the utterance now (recognize what was said so far) */
    fun stop() {
        if (activeSession == 0) return
        post(P.MSG_STOP)
    }

    fun cancel() {
        if (activeSession == 0) return
        post(P.MSG_CANCEL)
        activeSession = 0
    }

    fun selfTest(model: SpeechModel) {
        if (!bindOrFail()) return
        activeSession = nextSessionId()
        post(P.MSG_SELF_TEST) { putString(P.KEY_MODEL, model.name) }
    }

    private inline fun post(what: Int, fill: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, what, activeSession, 0)
        m.data = Bundle().apply(fill)
        pendingOrSend(m)
    }

    private fun send(m: Message) {
        m.replyTo = incoming
        try {
            service?.send(m)
        } catch (_: RemoteException) {
        }
    }

    private fun handleEvent(msg: Message) {
        if (msg.arg1 != activeSession || activeSession == 0) return
        val d = msg.data
        when (msg.what) {
            P.EVT_LOADING -> listener.onLoading()
            P.EVT_READY -> listener.onReady(d.getString(P.KEY_BACKEND) ?: "", d.getLong(P.KEY_LOAD_MS))
            P.EVT_SPEECH_START -> listener.onSpeechStart()
            P.EVT_PARTIAL -> listener.onPartial(d.getString(P.KEY_TEXT) ?: "")
            P.EVT_FINAL -> listener.onFinal(d.getString(P.KEY_TEXT) ?: "")
            P.EVT_ERROR -> {
                activeSession = 0
                listener.onError(d.getString(P.KEY_MESSAGE) ?: "")
            }
            P.EVT_DONE -> {
                activeSession = 0
                listener.onDone()
            }
            P.EVT_TEST_RESULT -> {
                activeSession = 0
                listener.onTestResult(
                    d.getString(P.KEY_TEXT) ?: "",
                    d.getString(P.KEY_BACKEND) ?: "",
                    d.getLong(P.KEY_LOAD_MS),
                    d.getLong(P.KEY_DECODE_MS),
                    d.getLong(P.KEY_AUDIO_MS),
                )
            }
        }
    }

    companion object {
        private const val MAX_PENDING_AUDIO = 600
        private val sessionIds = AtomicInteger(0)

        private fun nextSessionId(): Int {
            while (true) {
                val id = sessionIds.incrementAndGet()
                if (id != 0) return id
            }
        }
    }
}
