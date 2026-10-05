package com.capsopasme.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.R
import com.capsopasme.assistant.agent.RootShell
import com.capsopasme.assistant.asr.AsrClient
import com.capsopasme.assistant.asr.ModelManager
import com.capsopasme.assistant.asr.SpeechModel
import com.capsopasme.assistant.call.CallActivity
import com.capsopasme.assistant.call.CallService
import com.capsopasme.assistant.llm.LlmClient

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var roleStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var permStatus: TextView
    private lateinit var deepseekKey: EditText
    private lateinit var deepseekModel: EditText
    private lateinit var glmKey: EditText
    private lateinit var glmModel: EditText
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var defaultCity: EditText
    private lateinit var mirror: EditText
    private lateinit var silence: EditText
    private lateinit var keepLoaded: Switch
    private lateinit var ttsStatus: TextView
    private lateinit var callSilence: EditText
    private lateinit var callIdle: EditText
    private lateinit var callCue: Switch
    private lateinit var callHeadsetMic: Switch
    private lateinit var callEntrySub: TextView

    private var testClient: AsrClient? = null
    private var testTts: TextToSpeech? = null

    private val selectedModel get() = prefs.speechModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaSilencer.restoreLeftover(this)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        roleStatus = findViewById(R.id.roleStatus)
        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        permStatus = findViewById(R.id.permStatus)
        deepseekKey = findViewById(R.id.deepseekKey)
        deepseekModel = findViewById(R.id.deepseekModel)
        glmKey = findViewById(R.id.glmKey)
        glmModel = findViewById(R.id.glmModel)
        geminiKey = findViewById(R.id.geminiKey)
        geminiModel = findViewById(R.id.geminiModel)
        defaultCity = findViewById(R.id.defaultCity)
        mirror = findViewById(R.id.mirror)
        silence = findViewById(R.id.silence)
        keepLoaded = findViewById(R.id.keepLoaded)
        ttsStatus = findViewById(R.id.ttsStatus)
        callSilence = findViewById(R.id.callSilence)
        callIdle = findViewById(R.id.callIdle)
        callCue = findViewById(R.id.callCue)
        callHeadsetMic = findViewById(R.id.callHeadsetMic)
        callEntrySub = findViewById(R.id.callEntrySub)

        deepseekKey.setText(prefs.deepseekKey)
        deepseekModel.setText(prefs.deepseekModel.takeIf { it != Prefs.DEFAULT_DEEPSEEK_MODEL } ?: "")
        glmKey.setText(prefs.glmKey)
        glmModel.setText(prefs.glmModel.takeIf { it != Prefs.DEFAULT_GLM_MODEL } ?: "")
        geminiKey.setText(prefs.geminiKey)
        geminiModel.setText(prefs.geminiModel.takeIf { it != Prefs.DEFAULT_GEMINI_MODEL } ?: "")
        defaultCity.setText(prefs.defaultCity)
        mirror.setText(prefs.mirrorPrefix)
        silence.setText(prefs.silenceMs.toString())
        keepLoaded.isChecked = prefs.keepModelLoaded
        callSilence.setText(prefs.callSilenceMs.toString())
        callIdle.setText(prefs.callIdleSeconds.toString())
        callCue.isChecked = prefs.callCue
        callHeadsetMic.isChecked = prefs.callHeadsetMic

        val llmGroup = findViewById<RadioGroup>(R.id.llmGroup)
        LlmClient.Kind.entries.forEach { k ->
            llmGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = k.label
                tag = k
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                isChecked = k == prefs.provider
            })
        }
        llmGroup.setOnCheckedChangeListener { g, checkedId ->
            prefs.provider = g.findViewById<View>(checkedId).tag as LlmClient.Kind
        }

        val group = findViewById<RadioGroup>(R.id.modelGroup)
        SpeechModel.entries.forEach { m ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = m.label
                tag = m
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                isChecked = m == selectedModel
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            prefs.speechModel = g.findViewById<View>(checkedId).tag as SpeechModel
            renderModel()
        }

        // starts the call, or goes back to the one going on
        findViewById<View>(R.id.callEntry).setOnClickListener {
            startActivity(
                Intent(this, CallActivity::class.java).setAction(CallActivity.ACTION_CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        findViewById<Button>(R.id.roleRoot).setOnClickListener {
            // su may wait for the KernelSU prompt: never on the main thread
            background {
                val r = RootShell.run("cmd role add-role-holder --user 0 android.app.role.ASSISTANT $packageName", 30_000)
                runOnUiThread {
                    toast(if (r.ok) "已设为默认数字助理" else "失败：${r.output.ifEmpty { "没有 root" }}")
                    renderRole()
                }
            }
        }
        findViewById<Button>(R.id.roleSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }

        findViewById<Button>(R.id.modelCopy).setOnClickListener {
            ModelManager.copyFromFcitx(this, selectedModel)
            renderModel()
        }
        findViewById<Button>(R.id.modelDownload).setOnClickListener {
            if (ModelManager.isBusy) ModelManager.cancel()
            else ModelManager.download(this, selectedModel, mirror.text.toString())
            renderModel()
        }
        findViewById<Button>(R.id.modelImport).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT
            )
        }
        findViewById<Button>(R.id.modelDelete).setOnClickListener {
            ModelManager.delete(this, selectedModel)
        }
        findViewById<Button>(R.id.modelTest).setOnClickListener { selfTest() }

        findViewById<Button>(R.id.ttsSettings).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.ttsTest).setOnClickListener { testSpeech() }

        findViewById<Button>(R.id.permRequest).setOnClickListener {
            requestPermissions(PERMISSIONS, REQ_PERMS)
        }
        findViewById<Button>(R.id.rootCheck).setOnClickListener {
            background {
                val ok = RootShell.isAvailable()
                runOnUiThread { toast(if (ok) "root 可用" else "没有 root 权限（请在 KernelSU 管理器里授权）") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ModelManager.setListener { m, _ -> if (m == selectedModel) renderModel() }
        ModelManager.refresh(this)
        renderRole()
        renderPerms()
        renderModel()
        renderTts()
        callEntrySub.text = if (CallService.inCall) "通话中，点按回到通话" else "像打电话一样陪你聊天"
    }

    override fun onPause() {
        super.onPause()
        ModelManager.setListener(null)
        prefs.deepseekKey = deepseekKey.text.toString()
        prefs.deepseekModel = deepseekModel.text.toString()
        prefs.glmKey = glmKey.text.toString()
        prefs.glmModel = glmModel.text.toString()
        prefs.geminiKey = geminiKey.text.toString()
        prefs.geminiModel = geminiModel.text.toString()
        prefs.defaultCity = defaultCity.text.toString()
        prefs.mirrorPrefix = mirror.text.toString()
        prefs.silenceMs = (silence.text.toString().toIntOrNull() ?: 700).coerceIn(300, 2000)
        prefs.keepModelLoaded = keepLoaded.isChecked
        prefs.callSilenceMs = (callSilence.text.toString().toIntOrNull() ?: 800).coerceIn(300, 2000)
        prefs.callIdleSeconds = (callIdle.text.toString().toIntOrNull() ?: 180).coerceIn(0, 3600)
        prefs.callCue = callCue.isChecked
        prefs.callHeadsetMic = callHeadsetMic.isChecked
    }

    override fun onDestroy() {
        testClient?.unbind()
        testClient = null
        testTts?.shutdown()
        testTts = null
        super.onDestroy()
    }

    private fun background(block: () -> Unit) = Thread(block, "settings-bg").start()

    private fun toast(text: String) {
        if (!isDestroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun renderRole() {
        val held = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        roleStatus.text = if (held) "✓ 已是默认数字助理" else "还不是默认数字助理"
    }

    private fun renderPerms() {
        fun mark(p: String, name: String) =
            (if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "✓ " else "✗ ") + name
        permStatus.text = listOf(
            mark(Manifest.permission.RECORD_AUDIO, "麦克风"),
            mark(Manifest.permission.READ_CONTACTS, "通讯录"),
            mark(Manifest.permission.CALL_PHONE, "电话"),
            mark(Manifest.permission.READ_MEDIA_AUDIO, "音乐"),
        ).joinToString("   ")
    }

    /** the preferred TTS engine, which the voice call speaks with */
    private fun renderTts() {
        @Suppress("DEPRECATION")
        val pkg = Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        val label = pkg?.let {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        ttsStatus.text = when {
            label == null -> "没读到首选引擎，通话会用系统默认的 TTS"
            pkg == MOSS_PACKAGE -> "✓ $label（离线流式朗读）"
            else -> "$label（建议在系统 TTS 设置里把首选引擎设为 MOSS-TTS-Nano）"
        }
    }

    private fun testSpeech() {
        testTts?.shutdown()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                toast("语音合成引擎初始化失败")
                return@TextToSpeech
            }
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.speak("你好，语音通话时我会用这个声音回答你。", TextToSpeech.QUEUE_FLUSH, null, "test")
        }
        testTts = tts
    }

    private fun renderModel() {
        val busyHere = ModelManager.isBusy
        findViewById<Button>(R.id.modelDownload).text = if (busyHere) "取消" else "下载"
        when (val s = ModelManager.state(selectedModel)) {
            is ModelManager.State.Installed -> {
                modelStatus.text = "已安装（${s.bytes / 1024 / 1024} MB）"
                modelProgress.visibility = View.GONE
            }
            is ModelManager.State.Working -> {
                modelStatus.text = "${s.what} ${s.done / 1024 / 1024} / ${if (s.total > 0) s.total / 1024 / 1024 else "?"} MB"
                modelProgress.visibility = View.VISIBLE
                modelProgress.progress = if (s.total > 0) (s.done * 1000 / s.total).toInt().coerceIn(0, 1000) else 0
            }
            is ModelManager.State.Failed -> {
                modelStatus.text = "失败：${s.message}"
                modelProgress.visibility = View.GONE
            }
            else -> {
                modelStatus.text = "未安装（约 ${selectedModel.downloadSizeMb} MB）。小企鹅输入法里已有同款模型时，用“从小企鹅复制”最快（需要 root）。"
                modelProgress.visibility = View.GONE
            }
        }
    }

    private fun selfTest() {
        if (CallService.running) {
            // the recognizer serves one session at a time: the test would cut the call off
            toast("语音通话进行中，挂断后再测试")
            return
        }
        val model = selectedModel
        if (!ModelManager.isInstalled(this, model)) {
            toast("先安装模型")
            return
        }
        modelStatus.text = "测试中…"
        val client = testClient ?: AsrClient(this, object : AsrClient.Listener {
            override fun onLoading() {
                modelStatus.text = "加载模型中…"
            }

            override fun onTestResult(text: String, backend: String, loadMillis: Long, decodeMillis: Long, audioMillis: Long) {
                modelStatus.text = "$backend：加载 ${loadMillis} ms，${audioMillis / 1000.0} 秒音频识别 ${decodeMillis} ms\n“$text”"
                testClient?.unbind()
            }

            override fun onError(message: String) {
                modelStatus.text = "测试失败：$message"
                testClient?.unbind()
            }

            override fun onServiceCrashed() {
                modelStatus.text = "识别进程崩溃（NPU 初始化失败？），可以换 CPU 模型"
                testClient?.unbind()
            }
        }).also { testClient = it }
        client.selfTest(model)
    }

    @Deprecated("platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            data?.data?.let {
                ModelManager.importArchive(this, selectedModel, it)
                renderModel()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPerms()
    }

    companion object {
        private const val MOSS_PACKAGE = "io.github.capsopasme.mossnano"
        private const val REQ_IMPORT = 10
        private const val REQ_PERMS = 11
        private val PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    }
}
