package com.capsopasme.assistant.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.R
import com.capsopasme.assistant.agent.Agent
import com.capsopasme.assistant.agent.ToolLabels
import com.capsopasme.assistant.asr.AsrClient
import com.capsopasme.assistant.asr.AudioCapture
import com.capsopasme.assistant.asr.ModelManager
import com.capsopasme.assistant.asr.joinText
import com.capsopasme.assistant.ui.MediaSilencer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The voice call: a turn-taking (half-duplex) conversation. Listen until the user pauses, ask
 * the model, speak the answer through the system TTS engine sentence by sentence as it streams
 * in, then listen again. The microphone is closed while the answer plays, so the assistant never
 * hears itself; tapping interrupts it.
 *
 * A foreground service of type microphone, started from the visible call screen: the call goes
 * on with the screen off or another app on top. [CallActivity] only shows it ([Ui]).
 *
 * Everything (microphone, recognizer session, TTS binding, audio focus, wake lock) lives only
 * as long as the call: it ends on hang-up, after [Prefs.callIdleSeconds] of silence, when a phone
 * call or alarm takes the audio, or after [MAX_CALL_MS].
 *
 * Main thread only, except the agent's worker thread (marked).
 */
class CallService : Service() {

    enum class Phase { Starting, Listening, Thinking, Speaking, Confirming, Paused, Ended }

    /** a snapshot for the call screen */
    class State(
        val phase: Phase,
        val userText: String,
        val userPartial: Boolean,
        val answer: String,
        val answerIsError: Boolean,
        val status: String,
        val muted: Boolean,
        val route: AudioRoute.Kind,
        /** false = the earpiece is wanted */
        val speakerOn: Boolean,
        /** the loudspeaker / earpiece switch applies (no headphones, has an earpiece) */
        val canSwitchSpeaker: Boolean,
        val headphones: Boolean,
        /** show the yes / no buttons */
        val confirming: Boolean,
        /** the microphone is listening to the user (not just open for going on talking) */
        val micOpen: Boolean,
        /** elapsedRealtime of the call start, for the duration */
        val startedAt: Long,
    )

    interface Ui {
        fun render(state: State)

        /** each 100 ms microphone chunk while listening */
        fun onLevel(rms: Float)

        /**
         * The call ended. [launches] are apps the user asked for: the screen is in view, so it
         * starts them (unlocking first if needed), then closes.
         */
        fun onEnded(launches: List<Intent>)
    }

    inner class LocalBinder : Binder() {
        val service: CallService get() = this@CallService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var asr: AsrClient
    private lateinit var speaker: Speaker
    private lateinit var route: AudioRoute
    private lateinit var focus: CallFocus
    private lateinit var power: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityLock: PowerManager.WakeLock? = null

    private var ui: Ui? = null

    /** read on the capture thread */
    @Volatile
    private var uiVisible = false

    private var started = false
    private var ended = false
    private var released = false
    private var startedAt = 0L
    private var phase = Phase.Starting
    private var muted = false

    // what the screen shows
    private var userText = ""
    private var userPartial = false
    private var answer = ""
    private var answerIsError = false
    private var status = ""

    // ---------------------------------------------------------------------------------------------
    // listening state

    private enum class Listen {
        /** the user's turn */
        Normal,

        /** the model is thinking: the user may still go on talking ([tryContinue]) */
        Continuation,

        /** a yes / no for a confirmation */
        Confirm,
    }

    private var listenMode = Listen.Normal
    private var capturing = false
    private var heard = false

    /** an EVT_FINAL came: the DONE right after it is not "nothing was said" */
    private var finalSeen = false

    /** what the user said so far this turn (they went on talking after a pause) */
    private var prefix = ""

    private val capture = AudioCapture(
        onAudio = { pcm, rms ->
            asr.sendAudio(pcm)
            if (uiVisible) main.post { if (phase == Phase.Listening || phase == Phase.Confirming) ui?.onLevel(rms) }
        },
        onFailure = { e -> main.post { micFailed(e.message ?: e.javaClass.simpleName) } },
    )

    // ---------------------------------------------------------------------------------------------
    // the model

    /** one question and its answer */
    private class Turn(val id: Int, val question: String) {
        val chunker = SpeechChunker()

        /** the answer has started: too late to go on talking */
        var gotText = false
        var toolStarted = false
        var finished = false

        /** the user tapped the answer away: show, don't speak */
        var silent = false

        /** something of this answer was queued for speaking */
        var spoke = false
        var launches: List<Intent> = emptyList()
        var endCall = false
    }

    private var agent: Agent? = null
    private var worker: ExecutorService? = null
    private var turnSeq = 0
    private var turn: Turn? = null

    /** the turn the worker thread is running (set on the worker before [Agent.ask]) */
    @Volatile
    private var workerTurn = 0

    /** hang_up called during the running turn (worker thread) */
    @Volatile
    private var endRequested = false

    @Volatile
    private var confirmLatch: CountDownLatch? = null

    @Volatile
    private var confirmAnswer = false
    private var confirmRetried = false

    /** what happens once the queued speech has played */
    private enum class After { Nothing, TurnEnd, ConfirmListen, Goodbye }

    private var after = After.Nothing

    // ---------------------------------------------------------------------------------------------
    // lifecycle

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        power = getSystemService(PowerManager::class.java)
        asr = AsrClient(this, asrListener)
        speaker = Speaker(this, speakerListener)
        route = AudioRoute(this) { kindChanged -> onRouteChanged(kindChanged) }
        focus = CallFocus(this) { end(emptyList(), reason = "被来电、闹钟或其他应用打断") }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANG_UP -> hangUp()
            ACTION_TOGGLE_MUTE -> toggleMute()
            else -> if (!started) startCall() else if (!ended) refreshForeground()
        }
        // a notification action for a call that is already over
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // the call screen swiped away from recents: the user wants it gone
        if (rootIntent?.component?.className == CallActivity::class.java.name) hangUp()
    }

    override fun onDestroy() {
        if (!ended) end(emptyList())
        releaseAll(resume = true)
        main.removeCallbacksAndMessages(null)
        running = false
        super.onDestroy()
    }

    private fun startCall() {
        started = true
        startedAt = SystemClock.elapsedRealtime()
        // first: a foreground service that doesn't call this in time gets the app killed
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "can't start the call in the foreground", e)
            Toast.makeText(this, "无法开始语音通话：${e.message}", Toast.LENGTH_LONG).show()
            ended = true
            phase = Phase.Ended
            main.post { ui?.onEnded(emptyList()) }
            stopSelf()
            return
        }
        MediaSilencer.restoreLeftover(this)
        if (!focus.acquire()) {
            end(emptyList(), reason = "正在打电话")
            return
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneAssistant:call").apply {
            setReferenceCounted(false)
            acquire(MAX_CALL_MS + 60_000L)
        }
        route.start(prefs.callHeadsetMic)
        speaker.setAttributes(route.outputAttributes)
        speaker.init()
        updateProximity()
        main.postDelayed(maxCallTimeout, MAX_CALL_MS)
        startListening(Listen.Normal, cue = false)
    }

    /** started again while running (startForegroundService expects startForeground every time) */
    private fun refreshForeground() = updateNotification()

    // ---------------------------------------------------------------------------------------------
    // the call screen

    fun attach(u: Ui) {
        ui = u
        if (ended) u.onEnded(emptyList()) else render()
    }

    fun detach(u: Ui) {
        if (ui === u) {
            ui = null
            uiVisible = false
        }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    val isEnded: Boolean get() = ended

    /** the stream the volume keys should change on the call screen */
    val volumeStream: Int get() = route.volumeStream

    private fun render() {
        val u = ui ?: return
        u.render(State(
            phase = phase,
            userText = userText,
            userPartial = userPartial,
            answer = answer,
            answerIsError = answerIsError,
            status = status,
            muted = muted,
            route = route.kind,
            speakerOn = !route.earpieceWanted,
            canSwitchSpeaker = route.kind != AudioRoute.Kind.Headset && !route.headphones && route.earpieceAvailable,
            headphones = route.kind == AudioRoute.Kind.Headset || route.headphones,
            confirming = confirmLatch != null && phase == Phase.Confirming,
            micOpen = capturing && listenMode != Listen.Continuation,
            startedAt = startedAt,
        ))
    }

    // ---------------------------------------------------------------------------------------------
    // user actions (from the screen or the notification)

    fun hangUp() {
        if (started) end(emptyList())
    }

    /**
     * The big circle: while the user talks, "I'm done"; while the assistant thinks or talks,
     * "stop, let me talk"; when the microphone is off, "listen".
     */
    fun tap() {
        if (ended || !started) return
        when (phase) {
            Phase.Listening -> if (listenMode == Listen.Normal && heard) asr.stop()
            Phase.Thinking, Phase.Speaking -> interruptTurn()
            Phase.Confirming -> if (after == After.ConfirmListen) {
                // skip the question being read out, answer right away
                speaker.stop()
                after = After.Nothing
                startListening(Listen.Confirm, cue = false)
            }
            Phase.Paused -> if (muted) toggleMute() else startListening(Listen.Normal, cue = false)
            else -> {}
        }
    }

    fun toggleMute() {
        if (ended || !started) return
        muted = !muted
        if (muted) {
            when {
                listenMode == Listen.Continuation -> closeContinuation()
                phase == Phase.Listening -> {
                    stopListening()
                    prefix = ""
                    phase = Phase.Paused
                    status = "麦克风已关闭"
                    armIdle()
                }
                // answer with the buttons
                phase == Phase.Confirming && listenMode == Listen.Confirm -> stopListening()
            }
        } else {
            when {
                phase == Phase.Paused -> startListening(Listen.Normal, cue = false)
                phase == Phase.Confirming && confirmLatch != null && after != After.ConfirmListen ->
                    startListening(Listen.Confirm, cue = false)
            }
        }
        render()
        updateNotification()
    }

    fun toggleSpeaker() {
        if (ended || !started) return
        route.setEarpiece(!route.earpieceWanted)
        render()
    }

    /** the yes / no buttons */
    fun answerConfirm(yes: Boolean) {
        val latch = confirmLatch ?: return
        confirmAnswer = yes
        latch.countDown()
    }

    // ---------------------------------------------------------------------------------------------
    // listening

    private fun startListening(mode: Listen, cue: Boolean) {
        if (ended) return
        main.removeCallbacks(beginCapture)
        listenMode = mode
        heard = false
        finalSeen = false
        when (mode) {
            Listen.Normal -> {
                phase = Phase.Listening
                status = "正在听…"
                answerIsError = false
            }
            Listen.Confirm -> phase = Phase.Confirming
            Listen.Continuation -> {}
        }
        if (muted && mode != Listen.Continuation) {
            // the microphone is off: a confirmation is answered with the buttons
            stopListening()
            if (mode == Listen.Normal) {
                phase = Phase.Paused
                status = "麦克风已关闭，点一下说话"
                armIdle()
            }
            render()
            return
        }
        if (!ModelManager.isInstalled(this, prefs.speechModel)) {
            paused("语音模型还没装：打开“语音助手”设置下载或从小企鹅输入法复制")
            return
        }
        if (mode == Listen.Normal) armIdle()
        render()
        // the tone must not end up in the recording: open the microphone after it
        val delay = if (cue && cueWanted()) Earcon.yourTurn(route.outputAttributes) + CUE_GAP_MS else 0L
        if (delay > 0) main.postDelayed(beginCapture, delay) else beginCapture.run()
    }

    private val beginCapture = Runnable {
        if (ended) return@Runnable
        finalSeen = false
        asr.start(
            prefs.speechModel, partial = true, silenceMs = prefs.callSilenceMs,
            keepLoaded = prefs.keepModelLoaded, continuous = true,
        )
        if (!capturing) {
            if (!capture.start(this, route.micSource)) {
                micFailed("无法打开麦克风")
                return@Runnable
            }
            capturing = true
        }
        main.removeCallbacks(maxTurnTimeout)
        main.postDelayed(maxTurnTimeout, MAX_TURN_MS)
        render()
    }

    private fun stopListening() {
        main.removeCallbacks(beginCapture)
        main.removeCallbacks(idleTimeout)
        main.removeCallbacks(maxTurnTimeout)
        if (capturing) {
            capture.stop()
            capturing = false
        }
        asr.cancel()
    }

    /** the answer has started (or a tool, a question back): the user can't go on talking now */
    private fun closeContinuation() {
        if (listenMode != Listen.Continuation) return
        stopListening()
        listenMode = Listen.Normal
    }

    private fun armIdle() {
        main.removeCallbacks(idleTimeout)
        val s = prefs.callIdleSeconds
        if (s > 0) main.postDelayed(idleTimeout, s * 1000L)
    }

    private val idleTimeout = Runnable {
        if (phase == Phase.Listening || phase == Phase.Paused) goodbye("好久没听到你说话，我先挂了，有事再叫我。")
    }

    /** someone talking without end (a TV): take what was said */
    private val maxTurnTimeout = Runnable { if (capturing) asr.stop() }

    private val maxCallTimeout = Runnable { goodbye("通话时间太长了，我先挂了。") }

    /** the "your turn" tone only when nobody is looking at the animated screen */
    private fun cueWanted() = prefs.callCue && (!uiVisible || !power.isInteractive)

    private fun micFailed(message: String) {
        if (ended) return
        paused("麦克风不可用：$message")
    }

    /** microphone / recognizer trouble: stop listening, show it, tap retries */
    private fun paused(message: String) {
        stopListening()
        listenMode = Listen.Normal
        phase = Phase.Paused
        status = "点一下重试"
        answer = message
        answerIsError = true
        armIdle()
        render()
    }

    private val asrListener = object : AsrClient.Listener {
        override fun onLoading() {
            if (phase == Phase.Listening && listenMode == Listen.Normal) {
                status = "正在加载语音模型…（可以直接说）"
                render()
            }
        }

        override fun onReady(backend: String, loadMillis: Long) {
            if (phase == Phase.Listening && listenMode == Listen.Normal && status.startsWith("正在加载")) {
                status = "正在听…"
                render()
            }
        }

        override fun onSpeechStart() {
            heard = true
            main.removeCallbacks(idleTimeout)
            if (listenMode == Listen.Continuation) tryContinue()
        }

        override fun onPartial(text: String) {
            when (listenMode) {
                Listen.Normal -> {
                    userText = joinText(prefix, text)
                    userPartial = true
                    render()
                }
                Listen.Confirm -> {
                    userText = text
                    userPartial = true
                    render()
                }
                Listen.Continuation -> {}
            }
        }

        override fun onFinal(text: String) {
            finalSeen = true
            main.removeCallbacks(maxTurnTimeout)
            when (listenMode) {
                Listen.Normal -> onHeard(text)
                Listen.Confirm -> onConfirmReply(text)
                // the speech start turned it into Normal, or closed it
                Listen.Continuation -> {}
            }
        }

        override fun onDone() {
            // right after FINAL: the normal end. Alone: stopped with nothing said, keep listening
            if (finalSeen || ended || !capturing) return
            if (listenMode == Listen.Normal || listenMode == Listen.Confirm) {
                restartSession()
                if (listenMode == Listen.Normal) armIdle()
            }
        }

        override fun onError(message: String) {
            if (!ended) paused(message)
        }

        override fun onServiceCrashed() {
            if (!ended) paused("语音识别进程崩溃了（多半是 NPU 初始化失败），可以在设置里换成 CPU 模型")
        }
    }

    /** a finished utterance in the user's turn */
    private fun onHeard(text: String) {
        if (VoiceReplies.isFiller(text)) {
            // "嗯…" after a pause: ask what was said before it
            if (prefix.isNotEmpty()) {
                startTurn(prefix)
            } else {
                userText = ""
                render()
                restartSession()
                armIdle()
            }
            return
        }
        val full = joinText(prefix, text.trim())
        userText = full
        userPartial = false
        if (VoiceReplies.isHangUp(full)) {
            goodbye("好的，再见。")
            return
        }
        startTurn(full)
    }

    /** a new recognizer session on the open microphone */
    private fun restartSession() {
        finalSeen = false
        heard = false
        asr.start(
            prefs.speechModel, partial = true, silenceMs = prefs.callSilenceMs,
            keepLoaded = prefs.keepModelLoaded, continuous = true,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // asking

    private fun startTurn(question: String) {
        prefix = question
        userText = question
        userPartial = false
        answer = ""
        answerIsError = false
        main.removeCallbacks(idleTimeout)
        val t = Turn(++turnSeq, question)
        turn = t
        endRequested = false
        phase = Phase.Thinking
        status = "正在思考…"
        val a = agent ?: createAgent()
        val w = worker ?: Executors.newSingleThreadExecutor { r -> Thread(r, "call-agent") }.also { worker = it }
        w.execute {
            workerTurn = t.id
            a.ask(question, t.id)
        }
        // until the answer starts the user may go on talking: keep the microphone open
        if (capturing && !muted) {
            listenMode = Listen.Continuation
            restartSession()
        } else {
            stopListening()
            listenMode = Listen.Normal
        }
        render()
    }

    /** the user went on talking while the model thinks: drop the question, keep listening */
    private fun tryContinue() {
        val t = turn
        if (t == null || t.gotText || t.toolStarted || t.finished || confirmLatch != null) {
            closeContinuation()
            return
        }
        if (agent?.interrupt(t.id) != true) {
            closeContinuation()
            return
        }
        turn = null
        listenMode = Listen.Normal
        phase = Phase.Listening
        status = "正在听…"
        render()
        // the session goes on: its final is appended to [prefix]
    }

    /** tapped while the assistant thinks or talks */
    private fun interruptTurn() {
        speaker.stop()
        after = After.Nothing
        val t = turn
        if (t == null) {
            startListening(Listen.Normal, cue = false)
            return
        }
        t.silent = true
        if (t.finished) {
            finishTurn(t)
            return
        }
        if (agent?.interrupt(t.id) == true) {
            turn = null
            prefix = ""
            answer = ""
            if (listenMode == Listen.Continuation) {
                // the microphone is open already: what comes next is a new question
                listenMode = Listen.Normal
                phase = Phase.Listening
                status = "正在听…"
                armIdle()
                render()
            } else {
                startListening(Listen.Normal, cue = false)
            }
            return
        }
        // a tool is running: the turn ends by itself, without more speech
        closeContinuation()
        status = "正在执行，马上好…"
        render()
    }

    private fun createAgent(): Agent {
        val listener = object : Agent.Listener {
            // worker thread: tag each callback with the turn it belongs to
            override fun onText(delta: String) {
                val id = workerTurn
                main.post { onTurnText(id, delta) }
            }

            override fun onToolRunning(name: String) {
                val id = workerTurn
                main.post { onTurnTool(id, name) }
            }

            override fun onFinished(text: String, launches: List<Intent>, acted: Boolean) {
                val id = workerTurn
                val end = endRequested
                main.post { onTurnFinished(id, text, launches, end) }
            }

            override fun onError(message: String) {
                val id = workerTurn
                main.post { onTurnError(id, message) }
            }
        }
        return Agent(this, uiHost, listener, voice = true).also { agent = it }
    }

    private fun current(id: Int): Turn? = turn?.takeIf { it.id == id && !ended }

    private fun onTurnText(id: Int, delta: String) {
        val t = current(id) ?: return
        if (!t.gotText) {
            t.gotText = true
            closeContinuation()
        }
        answer += delta
        if (!t.silent) t.chunker.push(delta).forEach { speakFor(t, it) }
        render()
    }

    private fun onTurnTool(id: Int, name: String) {
        val t = current(id) ?: return
        t.toolStarted = true
        closeContinuation()
        // the user controls the music themselves: don't resume it after the call
        if (name == "media_control" || name == "play_music") focus.forgetPausedPlayer()
        status = ToolLabels.status(name)
        render()
    }

    private fun onTurnFinished(id: Int, text: String, launches: List<Intent>, endCall: Boolean) {
        val t = current(id) ?: return
        t.finished = true
        t.launches = launches
        t.endCall = endCall
        closeContinuation()
        if (answer.isBlank()) answer = text.ifBlank { "好的" }
        if (!t.silent) {
            t.chunker.flush()?.let { speakFor(t, it) }
            if (!t.spoke) {
                // a local command or a launch: nothing was streamed
                val said = when {
                    launches.isNotEmpty() && text.startsWith("已") -> "好的，" + text.substring(1)
                    else -> text.ifBlank { "好的" }
                }
                speakFor(t, said)
            }
        }
        status = if (launches.isNotEmpty()) "马上打开…" else ""
        render()
        afterSpeech(After.TurnEnd)
    }

    private fun onTurnError(id: Int, message: String) {
        val t = current(id) ?: return
        t.finished = true
        closeContinuation()
        answer = message
        answerIsError = true
        status = "出错了"
        if (!t.silent) speaker.speak(spokenError(message))
        render()
        afterSpeech(After.TurnEnd)
    }

    private fun speakFor(t: Turn, text: String) {
        speaker.speak(text)
        t.spoke = true
    }

    /** the answer has been spoken: open an app, hang up, or listen again */
    private fun finishTurn(t: Turn) {
        if (turn !== t) return
        turn = null
        prefix = ""
        if (t.endCall || t.launches.isNotEmpty()) {
            end(t.launches)
            return
        }
        startListening(Listen.Normal, cue = true)
    }

    // ---------------------------------------------------------------------------------------------
    // speech

    private val speakerListener = object : Speaker.Listener {
        override fun onSpeakerReady(available: Boolean) {
            if (!available && !ended) {
                Toast.makeText(this@CallService, "没有可用的语音合成引擎，回答只显示在屏幕上", Toast.LENGTH_LONG).show()
            }
        }

        override fun onSpeechStarted() {
            if (phase == Phase.Thinking) {
                phase = Phase.Speaking
                status = "点圆球可以打断"
                render()
            }
        }

        override fun onSpeechDrained() = onDrained()

        override fun onSpeechError(code: Int) {
            if (ttsErrorShown || ended) return
            ttsErrorShown = true
            val why = if (code == TextToSpeech.ERROR_NOT_INSTALLED_YET) "语音合成引擎的模型还没下载（打开 MOSS-TTS-Nano 下载）"
            else "语音合成出错（$code）"
            Toast.makeText(this@CallService, "$why，回答只显示在屏幕上", Toast.LENGTH_LONG).show()
        }
    }

    private var ttsErrorShown = false

    /** [next] runs once everything queued has been spoken (right away if nothing is) */
    private fun afterSpeech(next: After) {
        after = next
        if (!speaker.busy) main.post { onDrained() }
    }

    private fun onDrained() {
        val a = after
        if (a == After.Nothing || speaker.busy || ended) return
        after = After.Nothing
        when (a) {
            After.TurnEnd -> turn?.let { if (it.finished) finishTurn(it) }
            After.ConfirmListen -> if (confirmLatch != null) startListening(Listen.Confirm, cue = true)
            After.Goodbye -> end(emptyList())
            After.Nothing -> {}
        }
    }

    /** say [text], then hang up */
    private fun goodbye(text: String) {
        if (ended) return
        stopListening()
        listenMode = Listen.Normal
        turn?.let { agent?.interrupt(it.id) }
        turn = null
        speaker.stop()
        answer = text
        answerIsError = false
        status = ""
        phase = Phase.Speaking
        render()
        speaker.speak(text)
        afterSpeech(After.Goodbye)
    }

    private fun spokenError(message: String): String = when {
        message.contains("API Key") && message.contains("填") -> "还没有填大模型的 API Key，请在设置里填好。"
        message.contains("HTTP 401") || message.contains("HTTP 403") -> "API Key 无效或者没有权限。"
        message.contains("HTTP 429") -> "请求太频繁或者额度用完了。"
        message.contains("resolve host", ignoreCase = true) || message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) || message.contains("connect", ignoreCase = true) ->
            "网络好像有问题，没连上大模型。"
        message.length <= 30 -> "出错了，$message"
        else -> "出错了，详情在屏幕上。"
    }

    // ---------------------------------------------------------------------------------------------
    // confirmations (asked by a tool on the worker thread)

    private val uiHost = object : Agent.UiHost {
        override fun confirm(question: String): Boolean {
            val latch = CountDownLatch(1)
            confirmAnswer = false
            confirmLatch = latch
            val id = workerTurn
            main.post { beginConfirm(id, question) }
            val answered = try {
                latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            confirmLatch = null
            main.post { endConfirm(id) }
            return answered && confirmAnswer && !ended
        }

        override fun startNow(intent: Intent): Boolean = launchNow(intent)

        override fun endCall(): Boolean {
            endRequested = true
            return true
        }
    }

    private fun beginConfirm(id: Int, question: String) {
        val t = current(id)
        if (t == null) {
            answerConfirm(false)
            return
        }
        closeContinuation()
        // what the model said before asking, then the question
        t.chunker.flush()?.let { speakFor(t, it) }
        answer = question
        answerIsError = false
        status = "请说“确定”或“取消”，也可以点按钮"
        phase = Phase.Confirming
        confirmRetried = false
        speaker.speak(question)
        render()
        afterSpeech(After.ConfirmListen)
    }

    private fun onConfirmReply(text: String) {
        userText = text
        userPartial = false
        val yes = VoiceReplies.parseConfirm(text)
        when {
            yes != null -> {
                stopListening()
                answerConfirm(yes)
            }
            !confirmRetried -> {
                confirmRetried = true
                stopListening()
                speaker.speak("没听清，请说确定或者取消。")
                afterSpeech(After.ConfirmListen)
            }
            else -> {
                stopListening()
                answerConfirm(false)
            }
        }
        render()
    }

    private fun endConfirm(id: Int) {
        if (ended) return
        if (listenMode == Listen.Confirm) stopListening()
        listenMode = Listen.Normal
        if (after == After.ConfirmListen) {
            speaker.stop()
            after = After.Nothing
        }
        if (current(id) == null) return
        phase = Phase.Thinking
        status = "正在执行…"
        answer = ""
        render()
    }

    // ---------------------------------------------------------------------------------------------
    // other apps

    /**
     * Worker thread. Alarms and timers open the clock app without a screen: with the call screen
     * in view a normal start works, otherwise (screen off, another app on top) root `am start`.
     */
    private fun launchNow(intent: Intent): Boolean {
        if (uiVisible && power.isInteractive) {
            val latch = CountDownLatch(1)
            var ok = false
            main.post {
                ok = try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "start failed", e)
                    false
                }
                latch.countDown()
            }
            try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            return ok
        }
        return Launcher.startAsRoot(intent)
    }

    // ---------------------------------------------------------------------------------------------
    // ending

    /**
     * @param launches apps to open now (the user asked for them)
     * @param reason why the call was cut off, shown as a toast
     */
    private fun end(launches: List<Intent>, reason: String? = null) {
        if (ended) return
        ended = true
        phase = Phase.Ended
        main.removeCallbacksAndMessages(null)
        stopListening()
        asr.unbind()
        agent?.cancel()
        agent = null
        confirmLatch?.countDown()
        worker?.shutdownNow()
        worker = null
        speaker.shutdown()

        // a phone call goes through Telecom; the rest needs an activity start
        val rest = launches.filterNot { Launcher.placeCallIfCall(this, it) }
        val u = ui
        if (rest.isNotEmpty() && u != null && uiVisible && power.isInteractive) {
            u.onEnded(rest)
        } else {
            if (rest.isNotEmpty()) {
                val list = ArrayList(rest)
                Thread({ list.forEach { Launcher.startAsRoot(it) } }, "call-launch").start()
            }
            u?.onEnded(emptyList())
        }
        reason?.let { Toast.makeText(this, "语音通话已结束：$it", Toast.LENGTH_LONG).show() }
        // the hang-up tone plays on the call's route: release that after it
        val tone = if (launches.isEmpty() && reason == null && started) Earcon.hangUp(route.outputAttributes) else 0L
        main.postDelayed({ releaseAll(resume = launches.isEmpty()) }, tone + 150L)
    }

    private fun releaseAll(resume: Boolean) {
        if (released) return
        released = true
        focus.release(resume)
        route.release()
        proximityLock?.let { if (it.isHeld) it.release() }
        proximityLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // audio route

    private fun onRouteChanged(kindChanged: Boolean) {
        speaker.setAttributes(route.outputAttributes)
        updateProximity()
        if (kindChanged && capturing) {
            // the microphone source follows the route: reopen it, the recognizer session goes on
            capture.stop()
            capturing = capture.start(this, route.micSource)
            if (!capturing) micFailed("无法打开麦克风")
        }
        render()
    }

    /** held to the ear: the proximity sensor turns the screen off, like in a phone call */
    private fun updateProximity() {
        val want = route.kind == AudioRoute.Kind.Earpiece && !ended &&
                power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        val lock = proximityLock
        if (want && lock == null) {
            proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "PhoneAssistant:ear").apply {
                setReferenceCounted(false)
                acquire(MAX_CALL_MS)
            }
        } else if (!want && lock != null) {
            if (lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            proximityLock = null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "语音通话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "语音通话进行中"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE,
        )
        val mute = PendingIntent.getService(
            this, 2, Intent(this, CallService::class.java).setAction(ACTION_TOGGLE_MUTE), PendingIntent.FLAG_IMMUTABLE,
        )
        val wallStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt)
        val muteAction = Notification.Action.Builder(
            Icon.createWithResource(this, if (muted) R.drawable.ic_mic else R.drawable.ic_mic_off),
            if (muted) "打开麦克风" else "静音", mute,
        ).build()
        fun base() = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("语音通话中")
            .setContentText(if (muted) "麦克风已关闭" else "点按回到通话")
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(wallStart)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return try {
            base()
                .setCategory(Notification.CATEGORY_CALL)
                .setStyle(Notification.CallStyle.forOngoingCall(
                    Person.Builder().setName(getString(R.string.app_name)).setImportant(true).build(), hangUp,
                ))
                .addAction(muteAction)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "call style refused, plain notification", e)
            base()
                .addAction(muteAction)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_call_end), "挂断", hangUp).build())
                .build()
        }
    }

    /**
     * Re-posted through startForeground: a CallStyle notification must belong to the foreground
     * service, a plain notify() of it can be refused.
     */
    private fun updateNotification() {
        if (ended || !started) return
        val n = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            } catch (e2: Exception) {
                Log.w(TAG, "notification update failed", e2)
            }
        }
    }

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL = "call"
        private const val NOTIFICATION_ID = 7

        const val ACTION_HANG_UP = "com.capsopasme.assistant.call.HANG_UP"
        const val ACTION_TOGGLE_MUTE = "com.capsopasme.assistant.call.TOGGLE_MUTE"

        /** a call is going on (this process): the sheet hands over to it, no second call starts */
        @Volatile
        var running = false
            private set

        /** hard limit of one call */
        private const val MAX_CALL_MS = 60 * 60_000L

        /** one turn of the user's at most (someone talking without end) */
        private const val MAX_TURN_MS = 90_000L
        private const val CONFIRM_TIMEOUT_S = 45L

        /** after the "your turn" tone, before the microphone opens */
        private const val CUE_GAP_MS = 60L
    }
}
