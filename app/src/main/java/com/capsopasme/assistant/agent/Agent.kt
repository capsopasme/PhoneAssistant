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
 * The tool-calling loop for one assistant session (one sheet opening). Keeps the conversation
 * in memory for follow-up questions in the same session only; nothing is persisted.
 *
 * [ask] blocks: run it on a worker thread. Listener callbacks come from that thread.
 */
class Agent(
    context: Context,
    private val ui: UiHost,
    private val listener: Listener,
) {
    interface UiHost {
        fun confirm(question: String): Boolean
        fun startNow(intent: Intent)
    }

    interface Listener {
        fun onText(delta: String)
        fun onToolRunning(name: String)

        /** final answer of this question; [launches] are to be started by the UI, then close */
        fun onFinished(text: String, launches: List<Intent>)
        fun onError(message: String)
    }

    private val prefs = Prefs(context)
    private val llm = LlmClient()
    private val messages = JSONArray()
    private val launches = ArrayList<Intent>()

    private val providers: List<LlmClient.Provider> = buildList {
        if (prefs.deepseekKey.isNotEmpty()) add(LlmClient.Provider(LlmClient.Kind.DeepSeek, prefs.deepseekKey, prefs.deepseekModel))
        if (prefs.geminiKey.isNotEmpty()) add(LlmClient.Provider(LlmClient.Kind.Gemini, prefs.geminiKey, prefs.geminiModel))
    }

    /** index into [providers]; after a fallback the session stays on the working provider */
    private var current = 0

    private val tools = Tools(context, object : Tools.Host {
        override fun confirm(question: String) = ui.confirm(question)
        override fun launch(intent: Intent) {
            launches.add(intent)
        }
        override fun startNow(intent: Intent) = ui.startNow(intent)
    })

    init {
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
    }

    fun cancel() = llm.cancel()

    fun ask(question: String) {
        if (providers.isEmpty()) {
            listener.onError("还没有填 API Key，请打开“语音助手”设置")
            return
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        try {
            repeat(MAX_ROUNDS) {
                val reply = chatWithFallback()
                messages.put(reply.assistantMessage)
                if (reply.toolCalls.isEmpty()) {
                    listener.onFinished(reply.text, emptyList())
                    return
                }
                for (call in reply.toolCalls) {
                    if (llm.cancelled) return
                    listener.onToolRunning(call.name)
                    val result = tools.execute(call.name, call.arguments)
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", result))
                }
                if (launches.isNotEmpty()) {
                    // opening another app ends the session: no point in a summary nobody sees
                    listener.onFinished(reply.text, ArrayList(launches))
                    launches.clear()
                    return
                }
            }
            listener.onError("步骤太多，已停止")
        } catch (e: Exception) {
            if (llm.cancelled) return
            Log.w(TAG, "agent failed", e)
            listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun chatWithFallback(): LlmClient.Reply {
        while (true) {
            var streamed = false
            try {
                return llm.chat(providers[current], messages, Tools.schemas) { delta ->
                    streamed = true
                    listener.onText(delta)
                }
            } catch (e: Exception) {
                // fall back only if nothing reached the screen yet, so the answer doesn't repeat
                if (llm.cancelled || streamed || current + 1 >= providers.size) throw e
                Log.w(TAG, "${providers[current].label} failed, falling back", e)
                current++
            }
        }
    }

    private fun systemPrompt(): String {
        val now = ZonedDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.CHINA))
        val city = prefs.defaultCity.ifBlank { "未设置" }
        return """
            你是运行在用户安卓手机（一加 Ace 5，LineageOS，已 root）上的语音助手。用户的话来自语音识别，可能有同音错字，按最合理的意思理解。
            现在是 $time（时区 ${now.zone.id}）。默认城市：$city。
            规则：
            - 能用工具完成的操作就直接调用工具，不要只给建议；一句话里有多个操作就依次调用多个工具。
            - 回复显示在屏幕底部的小卡片上：用中文，一两句话，不用 Markdown、不用列表。
            - 操作完成后简短确认结果；工具返回 ok=false 时如实说明原因，需要用户选择时直接问。
            - 知识类问题直接简洁回答；不确定或需要最新信息时如实说明，可以建议用 web_search。
            - 不要编造工具没有返回的信息（如天气、电量、联系人号码）。
            - 相对时间（明天、半小时后、下周一）按当前时间换算成具体时间再调用工具。
        """.trimIndent()
    }

    companion object {
        private const val TAG = "Agent"
        private const val MAX_ROUNDS = 6
    }
}
