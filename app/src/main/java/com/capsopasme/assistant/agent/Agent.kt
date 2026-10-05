package com.capsopasme.assistant.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The tool-calling loop for one assistant session (one sheet opening, or one voice call).
 * Keeps the conversation in memory for follow-up questions in the same session only; nothing
 * is persisted.
 *
 * [ask] blocks: run it on a worker thread. Listener callbacks come from that thread.
 *
 * @param voice voice call: the answers are read aloud ([systemPrompt]) and the model gets the
 * hang_up tool
 */
class Agent(
    private val context: Context,
    private val ui: UiHost,
    private val listener: Listener,
    private val voice: Boolean = false,
) {
    interface UiHost {
        fun confirm(question: String): Boolean

        /** see [Tools.Host.startNow] */
        fun startNow(intent: Intent): Boolean

        /** the model ended the voice call (hang_up); false outside a call */
        fun endCall(): Boolean = false
    }

    interface Listener {
        fun onText(delta: String)
        fun onToolRunning(name: String)

        /**
         * final answer of this question; [launches] are to be started by the UI, then close.
         * [acted] is false when nothing was actually done (only talk, or every tool failed):
         * the UI then listens again for the user's reply. With launches and no text from the
         * model, [text] is what the tools reported ("已打开留声机，播放《…》").
         */
        fun onFinished(text: String, launches: List<Intent>, acted: Boolean)
        fun onError(message: String)
    }

    private val prefs = Prefs(context)
    private val messages = JSONArray()
    private val launches = ArrayList<Intent>()

    /** read once per session: the model selected in the settings, never switched mid-way */
    private val provider: LlmClient.Provider? = prefs.currentProvider()

    private val tools = Tools(context, object : Tools.Host {
        override fun confirm(question: String) = ui.confirm(question)
        override fun launch(intent: Intent) {
            launches.add(intent)
        }
        override fun startNow(intent: Intent) = ui.startNow(intent)
        override fun endCall() = ui.endCall()
    })

    // ---------------------------------------------------------------------------------------------
    // cancelling: [cancel] ends the whole session, [interrupt] only one question (voice call)

    private val lock = Any()

    /** the request of the running question; a new client per question, a cancelled one stays so */
    @Volatile
    private var llm = LlmClient()

    @Volatile
    private var closed = false

    // all guarded by [lock]
    private var currentTurn = -1
    private var interruptedTurn = -1
    private var toolsStarted = false

    /** the minute the system prompt's clock was written for */
    private var promptMinute = ""

    init {
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(ZonedDateTime.now())))
    }

    /** Ends the session: the running request is aborted and nothing is asked any more */
    fun cancel() {
        closed = true
        llm.cancel()
    }

    /**
     * Drops question [turn] (voice call: the user went on talking, or tapped to interrupt), unless
     * one of its tools has started already: an action that ran must stay in the conversation.
     * Safe to call before [ask] for that turn has started.
     *
     * @return true if the question was dropped (no callback will come for it); false if it
     * goes on to the end
     */
    fun interrupt(turn: Int): Boolean = synchronized(lock) {
        if (currentTurn == turn) {
            if (toolsStarted) return false
            interruptedTurn = turn
            llm.cancel()
            return true
        }
        interruptedTurn = turn
        true
    }

    fun ask(question: String, turn: Int = 0) {
        val client = LlmClient()
        synchronized(lock) {
            if (closed || interruptedTurn == turn && turn != 0) return
            llm = client
            currentTurn = turn
            toolsStarted = false
        }

        // "打开 xx" / "倒计时 xx" / "关掉蓝牙": done on the phone, no model round trip
        val local = try {
            LocalCommands.parse(context, question)
        } catch (e: Exception) {
            Log.w(TAG, "local command failed", e)
            null
        }
        if (local != null) {
            if (!startTools(client)) return
            runLocal(question, local)
            return
        }

        val provider = provider ?: run {
            listener.onError("${prefs.provider.label} 还没有填 API Key，请打开“语音助手”设置")
            return
        }
        refreshClock()
        trimHistory()
        val userIndex = messages.length()
        messages.put(JSONObject().put("role", "user").put("content", question))
        var acted = false
        val okResults = ArrayList<String>()
        try {
            repeat(MAX_ROUNDS) {
                val reply = client.chat(provider, messages, Tools.schemasFor(voice)) { delta -> listener.onText(delta) }
                if (client.cancelled) {
                    rollback(userIndex)
                    return
                }
                messages.put(reply.assistantMessage)
                if (reply.toolCalls.isEmpty()) {
                    listener.onFinished(reply.text, emptyList(), acted)
                    return
                }
                if (!startTools(client)) {
                    // interrupted between the reply and its tools: none ran, forget the question
                    rollback(userIndex)
                    return
                }
                for (call in reply.toolCalls) {
                    listener.onToolRunning(call.name)
                    val result = tools.execute(call.name, call.arguments)
                    if (isOk(result)) {
                        acted = true
                        okResultText(result)?.let { okResults.add(it) }
                    }
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", result))
                }
                if (launches.isNotEmpty()) {
                    // opening another app ends the session: no point in a summary nobody sees
                    val text = reply.text.ifBlank { okResults.joinToString("，") }
                    listener.onFinished(text, ArrayList(launches), true)
                    launches.clear()
                    return
                }
                // the session was cancelled while the tools ran: nobody wants the summary
                if (client.cancelled) return
            }
            listener.onError("步骤太多，已停止")
        } catch (e: Exception) {
            if (client.cancelled) {
                rollback(userIndex)
                return
            }
            // failed before any reply: drop the question, so asking again doesn't send it twice
            if (messages.length() - 1 == userIndex) messages.remove(userIndex)
            Log.w(TAG, "agent failed", e)
            listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * From here on the question can't be interrupted: a tool is about to run.
     * @return false if it was interrupted already
     */
    private fun startTools(client: LlmClient): Boolean = synchronized(lock) {
        if (client.cancelled) return false
        toolsStarted = true
        true
    }

    /** forget an interrupted question and the half answer, so the conversation stays valid */
    private fun rollback(userIndex: Int) {
        while (messages.length() > userIndex) messages.remove(messages.length() - 1)
        launches.clear()
    }

    /**
     * A long call would send an ever-growing history: keep the system prompt and the last
     * messages, cut at a user message so tool calls never lose their results.
     */
    private fun trimHistory() {
        if (messages.length() <= MAX_HISTORY + 1) return
        var cut = messages.length() - MAX_HISTORY
        while (cut < messages.length() && messages.optJSONObject(cut)?.optString("role") != "user") cut++
        if (cut >= messages.length()) return
        for (i in cut - 1 downTo 1) messages.remove(i)
    }

    /** the clock in the system prompt, rewritten when the minute changed (long calls) */
    private fun refreshClock() {
        val now = ZonedDateTime.now()
        val minute = now.format(MINUTE)
        if (minute == promptMinute) return
        messages.put(0, JSONObject().put("role", "system").put("content", systemPrompt(now)))
    }

    private fun isOk(toolResult: String) = try {
        JSONObject(toolResult).optBoolean("ok")
    } catch (_: Exception) {
        false
    }

    private fun okResultText(toolResult: String): String? = try {
        JSONObject(toolResult).opt("result") as? String
    } catch (_: Exception) {
        null
    }

    /** (text to show, ok) from a tool's JSON result */
    private fun toolResult(json: String): Pair<String, Boolean> {
        val result = JSONObject(json)
        return if (result.optBoolean("ok")) result.optString("result") to true
        else result.optString("error", "执行失败") to false
    }

    private fun runLocal(question: String, command: LocalCommands.Command) {
        val (text, ok) = when (command) {
            is LocalCommands.OpenApp -> {
                launches.add(command.intent)
                "已打开${command.label}" to true
            }
            is LocalCommands.Timer -> toolResult(tools.execute("set_timer", JSONObject().put("seconds", command.seconds).toString()))
            is LocalCommands.Switch -> toolResult(tools.toggleLocal(command.setting, command.on))
        }
        // keep it in the conversation, so a follow-up question has the context
        trimHistory()
        messages.put(JSONObject().put("role", "user").put("content", question))
        messages.put(JSONObject().put("role", "assistant").put("content", text))
        if (!ok) {
            listener.onError(text)
            return
        }
        val out = ArrayList(launches)
        launches.clear()
        listener.onFinished(text, out, true)
    }

    private fun systemPrompt(now: ZonedDateTime): String {
        promptMinute = now.format(MINUTE)
        val time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.CHINA))
        val city = prefs.defaultCity.ifBlank { "未设置" }
        if (voice) return """
            你是运行在用户安卓手机（一加 Ace 5，LineageOS，已 root）上的语音助手，现在正和用户语音通话。用户的话来自语音识别，可能有同音错字，按最合理的意思理解；你的回复会被语音合成直接读出来。
            现在是 $time（时区 ${now.zone.id}）。默认城市：$city。
            规则：
            - 像打电话聊天一样自然、口语化，默认一到三句话；用户要你详细讲（讲故事、解释原理）时可以长一些，但用短句。
            - 只输出要读出来的话：不用 Markdown、列表、表情符号、网址、代码，数字和单位写成读得顺的样子。
            - 能用工具完成的操作就直接调用工具，不要只给建议；一句话里有多个操作就依次调用多个工具；完成后一句话说结果。
            - 工具返回 ok=false 时如实说明原因；需要用户补充或选择时直接简短地问，用户会接着说。
            - 打开别的应用、导航、放音乐、打电话、网页搜索执行后通话会自动结束，简短确认一句就行。
            - 用户想结束通话（再见、没事了、挂了吧）时调用 hang_up，并简短道别。
            - 播放音乐用 play_music；继续/暂停/切歌用 media_control。
            - 知识类问题直接回答；不确定或需要最新信息时如实说明。
            - 不要编造工具没有返回的信息（如天气、电量、联系人号码）。
            - 相对时间（明天、半小时后、下周一）按当前时间换算成具体时间再调用工具。
        """.trimIndent()
        return """
            你是运行在用户安卓手机（一加 Ace 5，LineageOS，已 root）上的语音助手。用户的话来自语音识别，可能有同音错字，按最合理的意思理解。
            现在是 $time（时区 ${now.zone.id}）。默认城市：$city。
            规则：
            - 能用工具完成的操作就直接调用工具，不要只给建议；一句话里有多个操作就依次调用多个工具。
            - 回复显示在屏幕底部的小卡片上：用中文，一两句话，不用 Markdown、不用列表。
            - 操作完成后简短确认结果；工具返回 ok=false 时如实说明原因，需要用户补充或选择时直接简短地问（问完会自动开麦听用户回答）。
            - 播放音乐（按歌名、歌手、专辑或随机）用 play_music；继续/暂停/切歌用 media_control。
            - 知识类问题直接简洁回答；不确定或需要最新信息时如实说明，可以建议用 web_search。
            - 不要编造工具没有返回的信息（如天气、电量、联系人号码）。
            - 相对时间（明天、半小时后、下周一）按当前时间换算成具体时间再调用工具。
        """.trimIndent()
    }

    companion object {
        private const val TAG = "Agent"
        private const val MAX_ROUNDS = 6

        /** messages kept besides the system prompt (about ten exchanges with tool calls) */
        private const val MAX_HISTORY = 40
        private val MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
