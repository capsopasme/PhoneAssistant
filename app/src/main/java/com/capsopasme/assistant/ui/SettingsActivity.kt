package com.capsopasme.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
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
import com.capsopasme.assistant.DeviceInfo
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.R
import com.capsopasme.assistant.agent.LearnedSwitches
import com.capsopasme.assistant.agent.RootShell
import com.capsopasme.assistant.asr.AsrClient
import com.capsopasme.assistant.asr.ModelManager
import com.capsopasme.assistant.asr.SpeechModel
import com.capsopasme.assistant.call.CallActivity
import com.capsopasme.assistant.call.CallService
import com.capsopasme.assistant.llm.LlmClient
import com.capsopasme.assistant.memory.MemoryStore

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
    private lateinit var memoryEnabled: Switch
    private lateinit var memoryStatus: TextView
    private lateinit var timeStopFx: Switch
    private lateinit var entryHint: TextView
    private lateinit var bgStatus: TextView
    private lateinit var bgHint: TextView
    private lateinit var learnStatus: TextView

    /** null until detected (off the main thread) */
    private var rom: DeviceInfo.Rom? = null

    private var testClient: AsrClient? = null
    private var testTts: TextToSpeech? = null

    private val selectedModel get() = prefs.speechModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaSilencer.restoreLeftover(this)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        // calls whose memory wasn't distilled yet (the job was dropped or refused): try again
        if (prefs.memoryEnabled && com.capsopasme.assistant.memory.MemoryStore.pendingFiles(this).isNotEmpty()) {
            com.capsopasme.assistant.memory.MemoryJobService.schedule(this)
        }

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
        memoryEnabled = findViewById(R.id.memoryEnabled)
        memoryStatus = findViewById(R.id.memoryStatus)
        timeStopFx = findViewById(R.id.timeStopFx)
        entryHint = findViewById(R.id.entryHint)
        bgStatus = findViewById(R.id.bgStatus)
        bgHint = findViewById(R.id.bgHint)
        learnStatus = findViewById(R.id.learnStatus)
        renderRomTexts()
        background {
            val r = DeviceInfo.rom(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rom = r
                renderRomTexts()
            }
        }

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
        memoryEnabled.isChecked = prefs.memoryEnabled
        timeStopFx.isChecked = prefs.timeStopFx
        // saved right away: a call or the sheet started from here must see it
        memoryEnabled.setOnCheckedChangeListener { _, on -> prefs.memoryEnabled = on }
        timeStopFx.setOnCheckedChangeListener { _, on -> prefs.timeStopFx = on }

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
            // not every ROM has the AOSP default-apps screen under this action
            openFirst(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<Button>(R.id.tileAdd).setOnClickListener {
            addTile(AssistTileService::class.java, R.string.tile_assist, R.drawable.ic_tile_assist)
        }
        findViewById<Button>(R.id.tileAddCall).setOnClickListener {
            addTile(CallTileService::class.java, R.string.tile_call, R.drawable.ic_stat_call)
        }

        findViewById<Button>(R.id.bgRoot).setOnClickListener { allowBackground() }
        findViewById<Button>(R.id.bgAppInfo).setOnClickListener { openAppDetails(packageName) }
        findViewById<Button>(R.id.bgMossInfo).setOnClickListener {
            if (installed(MOSS_PACKAGE)) openAppDetails(MOSS_PACKAGE) else toast("没有安装 MOSS-TTS-Nano")
        }
        findViewById<Button>(R.id.learnSwitch).setOnClickListener {
            SwitchLearnFlow(this) { renderLearned() }.start()
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
            openFirst(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<Button>(R.id.ttsDefaultRoot).setOnClickListener {
            if (!installed(MOSS_PACKAGE)) {
                toast("没有安装 MOSS-TTS-Nano")
                return@setOnClickListener
            }
            // the same setting the system TTS screen writes; ColorOS buries that screen deep
            background {
                val r = RootShell.run("settings put secure tts_default_synth $MOSS_PACKAGE", 30_000)
                runOnUiThread {
                    toast(if (r.ok) "已把 MOSS-TTS-Nano 设为首选引擎" else "失败：${r.output.ifEmpty { "没有 root" }}")
                    renderTts()
                }
            }
        }
        findViewById<Button>(R.id.ttsTest).setOnClickListener { testSpeech() }

        findViewById<Button>(R.id.memoryManage).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        findViewById<Button>(R.id.memoryClear).setOnClickListener { confirmClearMemory() }

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
        renderMemory()
        renderBackground()
        renderLearned()
        callEntrySub.text = if (CallService.inCall) "通话中，点按回到通话" else "肥嘟嘟的噜噜，随时陪你聊"
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
        prefs.memoryEnabled = memoryEnabled.isChecked
        prefs.timeStopFx = timeStopFx.isChecked
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

    /** texts that depend on the ROM: how to invoke the assistant, what keeps apps alive */
    private fun renderRomTexts() {
        val r = rom
        val colorOs = r?.isColorOs == true
        val tiles = "下拉控制中心点“语音助手”或“和噜噜通话”磁贴（上面的按钮一键添加，或在控制中心的编辑里拖进去）"
        val others = "长按桌面图标选“语音助手”或“和噜噜通话”；QuickBall 等应用可启动 Intent 动作 com.capsopasme.assistant.START（通话是 com.capsopasme.assistant.CALL）。"
        entryHint.text = if (colorOs) {
            "${r!!.name} 没有原生安卓“从屏幕角落斜向上滑调用助理”的手势，可以这样唤起：\n· $tiles\n· $others\n" +
                    "· 设为默认数字助理后，如果系统设置里电源键或手势有“数字助理/语音助手”可选、并且它打开的是默认数字助理，也会唤出这里（不同版本不一样，以设置里实际有的为准）。"
        } else {
            "手势导航下从屏幕左下角或右下角斜向上滑唤起（系统设置 → 系统 → 手势 → 系统导航 → 手势导航旁的齿轮 → 打开“滑动调用助理”）。\n· $tiles\n· $others"
        }
        bgHint.text = if (colorOs) {
            "上面的按钮把语音助手和 MOSS-TTS-Nano 加进系统的电池优化白名单。ColorOS 还有自己的后台管理，按钮管不到，要在两个应用的详情里各设一次：" +
                    "耗电管理 → 允许完全后台行为（或“允许后台行为”），并打开“允许自动启动”和“允许关联启动”（名字随版本略有不同）。\n" +
                    "不设的话：熄屏通话可能被系统掐断；语音助手连 MOSS 朗读引擎时可能被“关联启动”拦下，通话里只显示文字不出声；挂断后的记忆整理会拖很久。"
        } else {
            "加进电池优化白名单后，熄屏通话和挂断后的记忆整理不会被系统省电策略推迟。"
        }
    }

    private fun installed(pkg: String) = try {
        packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** starts the first of [intents] that the system can open */
    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        toast("打不开系统设置")
    }

    private fun openAppDetails(pkg: String) =
        openFirst(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))

    /**
     * Android 13+ asks the user to add the tile with one tap; a system UI that doesn't implement
     * that (or refuses) gets the manual way.
     */
    private fun addTile(service: Class<*>, label: Int, icon: Int) {
        val manual = "请下拉控制中心 → 编辑，把“${getString(label)}”拖进去"
        try {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, service), getString(label), Icon.createWithResource(this, icon), mainExecutor,
            ) { result ->
                toast(when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到控制中心"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "控制中心里已经有了"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "没有添加"
                    else -> "系统不支持一键添加：$manual"
                })
            }
        } catch (e: Exception) {
            toast("系统不支持一键添加：$manual")
        }
    }

    private fun renderBackground() {
        val pm = getSystemService(PowerManager::class.java)
        fun mark(pkg: String, name: String) = when {
            !installed(pkg) -> "$name 未安装"
            pm.isIgnoringBatteryOptimizations(pkg) -> "✓ $name"
            else -> "✗ $name"
        }
        bgStatus.text = "不受电池优化限制：${mark(packageName, "语音助手")}   ${mark(MOSS_PACKAGE, "MOSS-TTS-Nano")}"
    }

    /** battery optimization off and background running allowed, for this app and the TTS engine */
    private fun allowBackground() {
        val pkgs = listOf(packageName) + listOfNotNull(MOSS_PACKAGE.takeIf { installed(it) })
        val cmd = pkgs.joinToString("; ") { p ->
            "cmd deviceidle whitelist +$p >/dev/null && cmd appops set $p RUN_ANY_IN_BACKGROUND allow && cmd appops set $p RUN_IN_BACKGROUND allow"
        }
        background {
            val r = RootShell.run(cmd, 30_000)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(when {
                    !r.ok -> "失败：${r.output.take(120).ifEmpty { "没有 root" }}"
                    rom?.isColorOs == true -> "已放行。ColorOS 自己的后台管理还要在应用详情里设（见下面的说明）"
                    else -> "已放行"
                })
                renderBackground()
            }
        }
    }

    private fun renderLearned() {
        val learned = LearnedSwitches.LEARNABLE.filterKeys { LearnedSwitches.get(this, it) != null }.values
        learnStatus.text = if (learned.isEmpty()) "都用默认方式" else "已学会：${learned.joinToString("、")}"
    }

    private fun renderPerms() {
        fun mark(p: String, name: String) =
            (if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "✓ " else "✗ ") + name
        permStatus.text = listOf(
            mark(Manifest.permission.RECORD_AUDIO, "麦克风"),
            mark(Manifest.permission.READ_CONTACTS, "通讯录"),
            mark(Manifest.permission.CALL_PHONE, "电话"),
            mark(Manifest.permission.READ_MEDIA_AUDIO, "音乐"),
            mark(Manifest.permission.POST_NOTIFICATIONS, "通知"),
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
            t.speak("你好呀，我是噜噜，通话的时候我就用这个声音陪你聊天。", TextToSpeech.QUEUE_FLUSH, null, "test")
        }
        testTts = tts
    }

    private fun renderMemory() {
        val facts = MemoryStore.facts(this).size
        val calls = MemoryStore.calls(this).size
        val pending = MemoryStore.pendingFiles(this).size
        memoryStatus.text = buildString {
            append(if (facts == 0 && calls == 0) "还没有记忆" else "记得 $facts 件事、$calls 次通话")
            if (pending > 0) append("，还有 $pending 次通话等联网后整理")
        }
    }

    private fun confirmClearMemory() {
        AlertDialog.Builder(this)
            .setMessage("清空噜噜记得的所有事？清空后不能恢复。")
            .setPositiveButton("清空") { _, _ ->
                MemoryStore.clear(this)
                renderMemory()
                toast("已清空")
            }
            .setNegativeButton("取消", null)
            .show()
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
            // the call's notification: hang up / mute from the lock screen
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
