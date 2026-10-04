package com.capsopasme.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var status: TextView
    private lateinit var level: View
    private lateinit var userText: TextView
    private lateinit var answer: TextView
    private lateinit var confirmRow: View
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.Idle
    private var heardSomething = false

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
            level.post { level.scaleX = (rms * 12f).coerceIn(0.08f, 1f) }
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
                setIdle("没听清，再说一次？")
            } else {
                showUserText(t, partial = false)
                ask(t)
            }
        }

        override fun onDone() {
            // DONE right after FINAL is the normal end; alone it means nothing was said
            if (phase == Phase.Listening) {
                stopListening(cancel = false)
                setIdle(if (heardSomething) "没听清，再说一次？" else "没听到声音")
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
        setContentView(R.layout.activity_assist)
        prefs = Prefs(this)
        asr = AsrClient(this, asrListener)

        val root = findViewById<View>(R.id.root)
        status = findViewById(R.id.status)
        level = findViewById(R.id.level)
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
        root.setOnClickListener { finish() }

        // any touch on the card means "I'm reading": don't auto-close
        findViewById<View>(R.id.card).setOnTouchListener { _, _ ->
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

        startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // invoked again while open: start over
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

    private fun startListening() {
        if (phase == Phase.Thinking || phase == Phase.Confirming || phase == Phase.Listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            setIdle("需要麦克风权限")
            return
        }
        val model = prefs.speechModel
        if (!ModelManager.isInstalled(this, model)) {
            setIdle("语音模型还没装：打开“语音助手”设置下载或从小企鹅输入法复制。现在可以先打字。")
            return
        }
        heardSomething = false
        phase = Phase.Listening
        status.text = "正在听…"
        mic.setImageResource(R.drawable.ic_stop)
        level.visibility = View.VISIBLE
        asr.start(model, partial = true, silenceMs = prefs.silenceMs, keepLoaded = prefs.keepModelLoaded)
        if (!capture.start(this)) {
            stopListening(cancel = true)
            showError("无法打开麦克风")
            return
        }
        main.postDelayed(noSpeechTimeout, NO_SPEECH_MS)
        main.postDelayed(maxListenTimeout, MAX_LISTEN_MS)
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
        level.visibility = View.INVISIBLE
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

            override fun onFinished(text: String, launches: List<Intent>) = live {
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

    private val autoClose = Runnable { if (phase == Phase.Done) finish() }

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
        userText.alpha = if (partial) 0.6f else 1f
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

    companion object {
        /** for other apps (QuickBall etc.): `am start -a com.capsopasme.assistant.START` */
        const val ACTION_START = "com.capsopasme.assistant.START"

        private const val REQ_MIC = 1
        private const val NO_SPEECH_MS = 7_000L
        private const val MAX_LISTEN_MS = 25_000L
        private const val CONFIRM_TIMEOUT_S = 30L
        private const val AUTO_CLOSE_BASE_MS = 2_500L
        private const val AUTO_CLOSE_PER_CHAR_MS = 120L
        private const val AUTO_CLOSE_MAX_MS = 10_000L

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
