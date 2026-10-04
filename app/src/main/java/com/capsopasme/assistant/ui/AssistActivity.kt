package com.capsopasme.assistant.ui

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.R
import com.capsopasme.assistant.agent.Agent
import com.capsopasme.assistant.asr.AsrClient
import com.capsopasme.assistant.asr.AudioCapture
import com.capsopasme.assistant.asr.ModelManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The assistant sheet. Started by the system as the default digital assistant
 * (ACTION_ASSIST: corner swipe with gesture navigation) or by other apps (e.g. QuickBall)
 * through [ACTION_START].
 *
 * Lifetime = one interaction: the microphone, the recognizer session, the network request and
 * the worker thread all live only while this sheet is visible; leaving it finishes it.
 */
class AssistActivity : Activity() {

    private enum class Phase { Idle, Listening, Thinking, Confirming, Done }

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var card: ViewGroup
    private lateinit var scrim: Drawable
    private lateinit var status: TextView
    private lateinit var halo: View
    private lateinit var userText: TextView
    private lateinit var answer: TextView
    private lateinit var confirmRow: View
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton

    private val main = Handler(Looper.getMainLooper())

    /** all writes on the main thread; the listening halo and the "thinking" pulse follow it */
    private var phase = Phase.Idle
        set(value) {
            val old = field
            field = value
            if (old != value) onPhaseChanged(value)
        }

    /** pauses / mutes media playing on the loudspeaker while the sheet is open */
    private lateinit var silencer: MediaSilencer

    /** the exit animation is running: the sheet finishes when it ends */
    private var dismissing = false
    private var scrimAnimator: ValueAnimator? = null
    private var statusPulse: ObjectAnimator? = null
    private var heardSomething = false

    /**
     * listening again after an answer that didn't do anything (a question back, a failed
     * tool): silence then just closes the sheet instead of showing "没听到声音"
     */
    private var followUp = false

    /** one worker thread for the agent, created lazily, killed in onDestroy */
    private var worker: ExecutorService? = null
    private var agent: Agent? = null

    @Volatile
    private var confirmLatch: CountDownLatch? = null

    @Volatile
    private var confirmAnswer = false

    @Volatile
    private var destroyed = false

    private lateinit var asr: AsrClient

    private val capture = AudioCapture(
        onAudio = { pcm, rms ->
            asr.sendAudio(pcm)
            halo.post { onLevel(rms) }
        },
        onFailure = { e -> main.post { showError("麦克风不可用：${e.message}") ; stopListening(cancel = true) } },
    )

    private val asrListener = object : AsrClient.Listener {
        override fun onLoading() {
            if (phase == Phase.Listening) status.text = "正在加载语音模型…（可以直接说）"
        }

        override fun onReady(backend: String, loadMillis: Long) {
            if (phase == Phase.Listening) status.text = "正在听…"
        }

        override fun onSpeechStart() {
            heardSomething = true
        }

        override fun onPartial(text: String) {
            if (phase != Phase.Listening) return
            heardSomething = true
            showUserText(text, partial = true)
        }

        override fun onFinal(text: String) {
            if (phase != Phase.Listening) return
            stopListening(cancel = false)
            val t = text.trim()
            if (t.isEmpty()) {
                if (followUp) endFollowUp() else setIdle("没听清，再说一次？")
            } else {
                showUserText(t, partial = false)
                ask(t)
            }
        }

        override fun onDone() {
            // DONE right after FINAL is the normal end; alone it means nothing was said
            if (phase == Phase.Listening) {
                stopListening(cancel = false)
                when {
                    followUp && !heardSomething -> endFollowUp()
                    else -> setIdle(if (heardSomething) "没听清，再说一次？" else "没听到声音")
                }
            }
        }

        override fun onError(message: String) {
            stopListening(cancel = true)
            showError(message)
        }

        override fun onServiceCrashed() {
            stopListening(cancel = true)
            showError("语音识别进程崩溃了（多半是 NPU 初始化失败），可以在设置里换成 CPU 模型")
        }
    }

    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // no window animation: the sheet slides itself in and out (see playEnter / dismiss)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        setContentView(R.layout.activity_assist)
        prefs = Prefs(this)
        asr = AsrClient(this, asrListener)
        silencer = MediaSilencer(this)
        silencer.engage()

        root = findViewById(R.id.root)
        card = findViewById(R.id.card)
        scrim = root.background.mutate()
        status = findViewById(R.id.status)
        halo = findViewById(R.id.halo)
        userText = findViewById(R.id.userText)
        answer = findViewById(R.id.answer)
        confirmRow = findViewById(R.id.confirmRow)
        input = findViewById(R.id.input)
        mic = findViewById(R.id.mic)
        send = findViewById(R.id.send)
        answer.movementMethod = ScrollingMovementMethod()

        // edge-to-edge (targetSdk 36): keep the card above the gesture bar and the keyboard
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        root.setOnClickListener { dismiss() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { dismiss() }

        // any touch on the card means "I'm reading": don't auto-close
        card.setOnTouchListener { _, _ ->
            main.removeCallbacks(autoClose)
            false
        }
        answer.setOnTouchListener { _, _ ->
            main.removeCallbacks(autoClose)
            false
        }

        mic.setOnClickListener {
            main.removeCallbacks(autoClose)
            if (phase == Phase.Listening) asr.stop() else startListening()
        }
        send.setOnClickListener { submitTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTyped()
                true
            } else false
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.removeCallbacks(autoClose)
                if (phase == Phase.Listening) {
                    stopListening(cancel = true)
                    setIdle("请输入")
                }
                send.visibility = View.VISIBLE
            }
        }
        findViewById<Button>(R.id.confirmYes).setOnClickListener { answerConfirm(true) }
        findViewById<Button>(R.id.confirmNo).setOnClickListener { answerConfirm(false) }

        playEnter()
        startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (dismissing) return
        // invoked again while open: start over
        silencer.engage()
        agent?.cancel()
        agent = null
        answerConfirm(false)
        main.removeCallbacks(autoClose)
        if (phase == Phase.Listening) stopListening(cancel = true)
        phase = Phase.Idle
        mic.isEnabled = true
        confirmRow.visibility = View.GONE
        answer.visibility = View.GONE
        userText.visibility = View.GONE
        startListening()
    }

    override fun onStop() {
        super.onStop()
        // left for another app / screen off: this sheet is done, release everything
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        destroyed = true
        silencer.release()
        statusPulse?.cancel()
        scrimAnimator?.cancel()
        main.removeCallbacksAndMessages(null)
        capture.stop()
        asr.cancel()
        asr.unbind()
        agent?.cancel()
        confirmLatch?.countDown()
        worker?.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // listening

    /** @return false if listening couldn't start (no permission / model / microphone) */
    private fun startListening(followUp: Boolean = false): Boolean {
        if (phase == Phase.Thinking || phase == Phase.Confirming || phase == Phase.Listening) return false
        this.followUp = followUp
        val model = prefs.speechModel
        if (followUp) {
            // no prompts in the middle of a conversation: just don't listen
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                !ModelManager.isInstalled(this, model)
            ) return false
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            setIdle("需要麦克风权限")
            return false
        }
        if (!ModelManager.isInstalled(this, model)) {
            setIdle("语音模型还没装：打开“语音助手”设置下载或从小企鹅输入法复制。现在可以先打字。")
            return false
        }
        heardSomething = false
        phase = Phase.Listening
        status.text = if (followUp) "请说…（不说话会自动关闭）" else "正在听…"
        mic.setImageResource(R.drawable.ic_stop)
        asr.start(model, partial = true, silenceMs = prefs.silenceMs, keepLoaded = prefs.keepModelLoaded)
        if (!capture.start(this)) {
            stopListening(cancel = true)
            showError("无法打开麦克风")
            return false
        }
        main.postDelayed(noSpeechTimeout, if (followUp) FOLLOW_UP_NO_SPEECH_MS else NO_SPEECH_MS)
        main.postDelayed(maxListenTimeout, MAX_LISTEN_MS)
        return true
    }

    /** nobody answered the follow-up: back to the answer, then close like a normal answer */
    private fun endFollowUp() {
        followUp = false
        phase = Phase.Done
        status.text = ""
        main.postDelayed(autoClose, AUTO_CLOSE_BASE_MS)
    }

    /** nobody spoke: let the recognizer finish with whatever it has */
    private val noSpeechTimeout = Runnable {
        if (phase == Phase.Listening && !heardSomething) asr.stop()
    }

    private val maxListenTimeout = Runnable {
        if (phase == Phase.Listening) asr.stop()
    }

    private fun stopListening(cancel: Boolean) {
        main.removeCallbacks(noSpeechTimeout)
        main.removeCallbacks(maxListenTimeout)
        capture.stop()
        if (cancel) asr.cancel()
        mic.setImageResource(R.drawable.ic_mic)
        if (phase == Phase.Listening) phase = Phase.Idle
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else setIdle("没有麦克风权限，可以打字")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // asking

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || phase == Phase.Thinking || phase == Phase.Confirming) return
        input.setText("")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        showUserText(text, partial = false)
        ask(text)
    }

    private fun ask(question: String) {
        main.removeCallbacks(autoClose)
        phase = Phase.Thinking
        status.text = "正在思考…"
        answer.text = ""
        answer.setTextColor(getColor(R.color.text_primary))
        answer.visibility = View.GONE
        mic.isEnabled = false

        val a = agent ?: createAgent()
        val w = worker ?: Executors.newSingleThreadExecutor { r -> Thread(r, "assistant-agent") }.also { worker = it }
        w.execute { a.ask(question) }
    }

    /**
     * Callbacks are dropped once this agent is no longer the current one (the sheet was invoked
     * again and started over), so a cancelled request can never write into the new session.
     */
    private fun createAgent(): Agent {
        var self: Agent? = null
        fun live(block: () -> Unit) = ui { if (self != null && agent === self) block() }
        val listener = object : Agent.Listener {
            override fun onText(delta: String) = live {
                answer.visibility = View.VISIBLE
                answer.append(delta)
            }

            override fun onToolRunning(name: String) = live {
                // text streamed before a tool call is a preamble; the final answer replaces it
                answer.text = ""
                answer.visibility = View.GONE
                status.text = TOOL_LABELS[name]?.let { "正在$it…" } ?: "正在执行…"
            }

            override fun onFinished(text: String, launches: List<Intent>, acted: Boolean) = live {
                mic.isEnabled = true
                if (launches.isNotEmpty()) {
                    for (intent in launches) {
                        try {
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@AssistActivity, "打开失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    finish()
                    return@live
                }
                phase = Phase.Done
                status.text = ""
                if (answer.text.isNullOrEmpty()) answer.text = text.ifBlank { "好的" }
                answer.visibility = View.VISIBLE
                // nothing got done (a question back, a failed tool): listen for the reply
                if (!acted && startListening(followUp = true)) return@live
                // short confirmations close by themselves; longer answers stay a bit for reading
                val delay = (AUTO_CLOSE_BASE_MS + answer.text.length * AUTO_CLOSE_PER_CHAR_MS)
                    .coerceAtMost(AUTO_CLOSE_MAX_MS)
                main.postDelayed(autoClose, delay)
            }

            override fun onError(message: String) = live {
                mic.isEnabled = true
                showError(message)
            }
        }
        return Agent(this, uiHost, listener).also {
            self = it
            agent = it
        }
    }

    private val autoClose = Runnable { if (phase == Phase.Done) dismiss() }

    /** Called from the agent's worker thread */
    private val uiHost = object : Agent.UiHost {
        override fun confirm(question: String): Boolean {
            val latch = CountDownLatch(1)
            confirmAnswer = false
            confirmLatch = latch
            ui {
                phase = Phase.Confirming
                status.text = "需要确认"
                answer.visibility = View.VISIBLE
                answer.text = question
                confirmRow.visibility = View.VISIBLE
            }
            val answered = try {
                latch.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            ui {
                confirmRow.visibility = View.GONE
                if (phase == Phase.Confirming) {
                    phase = Phase.Thinking
                    status.text = "正在执行…"
                    answer.text = ""
                }
            }
            return answered && confirmAnswer && !destroyed
        }

        override fun startNow(intent: Intent) {
            val latch = CountDownLatch(1)
            ui {
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
                latch.countDown()
            }
            try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun answerConfirm(yes: Boolean) {
        confirmAnswer = yes
        confirmLatch?.countDown()
        confirmLatch = null
    }

    // ---------------------------------------------------------------------------------------------

    private fun ui(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    private fun showUserText(text: String, partial: Boolean) {
        userText.visibility = View.VISIBLE
        userText.text = text
        // dimmed through the colour, not the view alpha: the appear animation owns the alpha
        val c = getColor(R.color.text_secondary)
        userText.setTextColor(if (partial) (c and 0x00FFFFFF) or (0x99 shl 24) else c)
    }

    private fun setIdle(message: String) {
        if (phase == Phase.Listening) stopListening(cancel = true)
        phase = Phase.Idle
        status.text = message
    }

    private fun showError(message: String) {
        phase = Phase.Idle
        status.text = "出错了"
        answer.visibility = View.VISIBLE
        answer.setTextColor(getColor(R.color.error))
        answer.text = message
    }

    // ---------------------------------------------------------------------------------------------
    // motion: transform / alpha only (GPU, no relayout per frame), all short

    /** card slides up from below the screen edge, scrim fades in, mic pops */
    private fun playEnter() {
        scrim.alpha = 0
        card.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                card.translationY = offscreenDistance()
                card.animate()
                    .translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(EMPHASIZED_DECELERATE)
                    .withLayer()
                    .withEndAction { if (!dismissing) enableLayoutAnimations() }
                    .start()
                animateScrim(255, ENTER_MS * 3 / 4)
                mic.scaleX = 0.6f
                mic.scaleY = 0.6f
                mic.animate()
                    .scaleX(1f).scaleY(1f)
                    .setStartDelay(ENTER_MS / 3)
                    .setDuration(360)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
                return true
            }
        })
    }

    /**
     * Closes the sheet with the reverse motion. Leaving for another app (a launch, screen off)
     * still finishes right away: the sheet isn't visible then anyway.
     */
    private fun dismiss() {
        if (dismissing || isFinishing) return
        dismissing = true
        main.removeCallbacks(autoClose)
        if (phase == Phase.Listening) stopListening(cancel = true)
        agent?.cancel()
        answerConfirm(false)
        // playback comes back while the sheet slides away
        silencer.release()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        card.layoutTransition = null
        card.animate().cancel()
        card.animate()
            .translationY(offscreenDistance())
            .setDuration(EXIT_MS)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withLayer()
            .withEndAction { finish() }
            .start()
        animateScrim(0, EXIT_MS)
    }

    private fun offscreenDistance(): Float =
        (card.height + (card.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin + root.paddingBottom).toFloat()

    private fun animateScrim(to: Int, duration: Long) {
        scrimAnimator?.cancel()
        scrimAnimator = ValueAnimator.ofInt(scrim.alpha, to).apply {
            this.duration = duration
            addUpdateListener { scrim.alpha = it.animatedValue as Int }
            start()
        }
    }

    /**
     * The card grows and shrinks smoothly as lines appear, text streams in or the keyboard
     * opens, instead of jumping. Turned on after the entry animation, so the first layout
     * doesn't animate from nothing.
     */
    private fun enableLayoutAnimations() {
        card.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LAYOUT_MS)
            setDuration(LayoutTransition.DISAPPEARING, LAYOUT_MS / 2)
            setStartDelay(LayoutTransition.APPEARING, LAYOUT_MS / 3)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, LAYOUT_MS / 4)
            setStartDelay(LayoutTransition.CHANGE_APPEARING, 0)
            setStartDelay(LayoutTransition.CHANGING, 0)
            for (type in intArrayOf(LayoutTransition.CHANGING, LayoutTransition.CHANGE_APPEARING, LayoutTransition.CHANGE_DISAPPEARING)) {
                setInterpolator(type, EMPHASIZED_DECELERATE)
            }
        }
    }

    private fun onPhaseChanged(p: Phase) {
        if (p == Phase.Listening) showHalo() else hideHalo()
        if (p == Phase.Thinking) startPulse() else stopPulse()
    }

    private fun showHalo() {
        halo.animate().cancel()
        halo.visibility = View.VISIBLE
        halo.alpha = 0f
        halo.scaleX = 0.8f
        halo.scaleY = 0.8f
        halo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideHalo() {
        if (halo.visibility != View.VISIBLE) return
        halo.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { halo.visibility = View.INVISIBLE }
            .start()
    }

    /** each 100 ms audio chunk: the halo eases towards the new level, never jumps */
    private fun onLevel(rms: Float) {
        if (phase != Phase.Listening || destroyed) return
        val s = 1f + HALO_GROWTH * (rms * 12f).coerceIn(0f, 1f)
        halo.animate().alpha(1f).scaleX(s).scaleY(s).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun startPulse() {
        if (statusPulse != null) return
        status.animate().cancel()
        statusPulse = ObjectAnimator.ofFloat(status, View.ALPHA, 1f, 0.35f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        val pulse = statusPulse ?: return
        statusPulse = null
        pulse.cancel()
        status.animate().alpha(1f).setDuration(150).start()
    }

    companion object {
        /** for other apps (QuickBall etc.): `am start -a com.capsopasme.assistant.START` */
        const val ACTION_START = "com.capsopasme.assistant.START"

        private const val REQ_MIC = 1
        private const val NO_SPEECH_MS = 7_000L
        private const val FOLLOW_UP_NO_SPEECH_MS = 8_000L
        private const val MAX_LISTEN_MS = 25_000L
        private const val CONFIRM_TIMEOUT_S = 30L
        private const val AUTO_CLOSE_BASE_MS = 2_500L
        private const val AUTO_CLOSE_PER_CHAR_MS = 120L
        private const val AUTO_CLOSE_MAX_MS = 10_000L

        private const val ENTER_MS = 420L
        private const val EXIT_MS = 220L
        private const val LAYOUT_MS = 220L
        /** halo scale at full voice level: 40dp mic -> 60dp, fits the row's 10dp padding */
        private const val HALO_GROWTH = 0.5f

        /** Material 3 emphasized easing */
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        private val TOOL_LABELS = mapOf(
            "set_alarm" to "设闹钟",
            "set_timer" to "设倒计时",
            "show_alarms" to "打开闹钟",
            "add_calendar_event" to "新建日程",
            "find_contact" to "查通讯录",
            "call_phone" to "拨号",
            "compose_sms" to "写短信",
            "navigate" to "打开导航",
            "open_app" to "打开应用",
            "web_search" to "搜索",
            "get_weather" to "查天气",
            "play_music" to "播放音乐",
            "media_control" to "控制播放",
            "set_volume" to "调音量",
            "set_ringer_mode" to "切换铃声",
            "flashlight" to "开关手电筒",
            "set_brightness" to "调亮度",
            "toggle_setting" to "切换开关",
            "screen_off" to "锁屏",
            "get_device_status" to "查询状态",
        )
    }
}
