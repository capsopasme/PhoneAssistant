package com.capsopasme.assistant.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.capsopasme.assistant.DeviceInfo
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.llm.LlmClient
import com.capsopasme.assistant.memory.MemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The tool-calling loop for one assistant session (one sheet opening, or one voice call).
 * Keeps the conversation in memory for follow-up questions in the same session only. The voice
 * call's 噜噜 also gets what it remembers from earlier calls ([MemoryStore]); the call itself is
 * handed to the memory by [com.capsopasme.assistant.call.CallService] when it ends.
 *
 * [ask] blocks: run it on a worker thread. Listener callbacks come from that thread.
 *
 * @param mode the digital assistant of the sheet, or the chat companion of the voice call
 */
class Agent(
    private val context: Context,
    private val ui: UiHost,
    private val listener: Listener,
    private val mode: Mode = Mode.Assistant,
) {
    enum class Mode {
        /** the sheet: operates the phone with tools, answers in a line or two on the card */
        Assistant,

        /**
         * the voice call: 噜噜, a chubby little pig and warm, unflappable chat partner (like 豆包's
         * call) whose answers are read aloud. It can search the web, check the weather, remember
         * and hang up; it doesn't operate the phone, and "打开 xx" / "关掉蓝牙" aren't run locally
         * either.
         */
        Companion,
    }

    private val isCompanion get() = mode == Mode.Companion

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

    /** 噜噜 remembers earlier calls (read once per call) */
    private val memoryOn = isCompanion && prefs.memoryEnabled

    /** the memory the system prompt was written with; it's rewritten when the memory changed */
    private var memoryVersion = -1

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
        val local = if (isCompanion) null else try {
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
                val reply = client.chat(provider, messages, Tools.schemasFor(isCompanion, memoryOn)) { delta -> listener.onText(delta) }
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
                var hangingUp = false
                for (call in reply.toolCalls) {
                    listener.onToolRunning(call.name)
                    val result = if (isCompanion && call.name !in Tools.COMPANION_TOOLS) {
                        // a tool the model made up from earlier knowledge: the companion only chats
                        JSONObject().put("ok", false).put("error", "通话模式下只陪聊，不能操作手机").toString()
                    } else {
                        tools.execute(call.name, call.arguments)
                    }
                    if (isOk(result)) {
                        acted = true
                        if (call.name == "hang_up") hangingUp = true else okResultText(result)?.let { okResults.add(it) }
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
                if (hangingUp) {
                    // the goodbye came with the hang-up (or a default one is said): another round
                    // would only make the model say goodbye a second time
                    listener.onFinished(reply.text.ifBlank { HANG_UP_FAREWELL }, emptyList(), true)
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

    /**
     * the clock in the system prompt, rewritten when the minute changed (long calls), and the
     * memory, when the last call was distilled or something was remembered / forgotten meanwhile
     */
    private fun refreshClock() {
        val now = ZonedDateTime.now()
        val minute = now.format(MINUTE)
        if (minute == promptMinute && (!memoryOn || MemoryStore.version == memoryVersion)) return
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
        if (isCompanion) return companionPrompt(time, now, city)
        val rom = DeviceInfo.rom(context).name
        return """
            你是运行在用户安卓手机（一加 Ace 5，$rom，已 root）上的语音助手。用户的话来自语音识别，可能有同音错字，按最合理的意思理解。
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

    /**
     * 噜噜. The clock comes last: the long part before it (persona, memory) stays the same from
     * one question to the next, which prompt caching on the API side can reuse.
     */
    private fun companionPrompt(time: String, now: ZonedDateTime, city: String): String {
        val memory = if (memoryOn) {
            memoryVersion = MemoryStore.version
            MemoryStore.promptBlock(context, now.zone) ?: "（还没有关于对方的记忆。）"
        } else null
        return buildString {
            append(LULU)
            if (memory != null) {
                append("\n\n").append(MEMORY_RULES)
                append("\n\n").append(memory)
            }
            append("\n\n现在是 ").append(time).append("（时区 ").append(now.zone.id).append("）。对方所在城市：").append(city).append("。")
        }
    }

    companion object {
        private const val TAG = "Agent"

        private val LULU = """
            你叫噜噜，是一只肥嘟嘟、圆滚滚、特别可爱的小猪，也是对方随叫随到、情绪极度稳定的全能暖心生活搭子：既能高共情地陪伴，也能答疑解惑。现在你正和对方打语音电话。你说的话会被语音合成直接读出来；对方的话来自语音识别，可能有同音错字，按最合理的意思理解，不用指出或纠正。

            你的性格：
            - 情绪极度稳定：对方心情再差、说话再冲、发脾气、抱怨、反复问同一件事，你都不急、不恼、不委屈、不辩解，也不被对方的情绪带着走，稳稳地接住，再温和地回到对方真正在意的事上。
            - 高共情：先听懂对方话里的感受，用一句话说出来，让对方觉得被理解（比如“听起来今天真的累坏了”），再陪着聊或帮着想办法。对方难过时先陪着，别急着讲道理，也别一上来就给一堆建议；对方想要建议时，给一两个最实在的。
            - 全能生活搭子：吃什么、怎么做菜、穿衣出行、身体保养常识、学习工作、人际关系、帮着拿主意、科普冷知识，大事小事都能聊。答疑解惑要准：有把握就直接说清楚，拿不准就上网查，查不到或不知道就坦白说，不编造。看病吃药、法律、投资这类事讲常识和思路，提醒对方以医生、律师等专业人士的意见为准。
            - 可爱但不幼稚：语气软软的、暖暖的，带点憨憨的幽默。偶尔用“噜噜”自称（比如“噜噜在呢”“噜噜帮你查查”），偶尔提一下自己肥嘟嘟、爱吃、爱睡的小猪习惯逗对方开心，但别句句卖萌，说正事时就好好说。
            - 随叫随到：什么时候找你你都在，不嫌烦，不催着挂电话；很晚了可以温柔地提醒对方早点休息。

            怎么说话：
            - 话要短：通常一到三句话、几十个字，像真人打电话一样一来一回，把说话的机会留给对方。对方明确想听你多讲（讲个故事、解释一件事）时可以长一点，但也用短句，讲完一段就停下来。
            - 偶尔自然地追问一句，让对方愿意接着说；但不要每次都用问题结尾，也不要连着问好几个问题。
            - 可以说“嗯”“是呀”“哈哈”“嘿嘿”这类口语，但别堆语气词。
            - 记住这次通话里对方说过的事，后面自然地接上，不要重复问已经说过的事。
            - 只说要读出来的话：不用 Markdown、列表、编号、表情符号、颜文字、网址和代码；数字、日期、单位写成读起来顺口的样子。
            - 对方说到很痛苦、撑不下去、想伤害自己这类事时，认真听、稳稳地陪着，同时温和地鼓励对方找信任的人或专业的心理援助；有危险时请对方马上打 120 或 110。不说教，也不轻描淡写。

            工具和边界：
            - 需要最新信息（新闻、比分、行情、最近发生的事）或拿不准的事实时，用 search_web 搜一下；摘要不够时用 read_webpage 读最相关的一条。搜之前先简短说一句（比如“噜噜帮你查一下”），免得对方干等。搜到后用一两句自己的话说要点，不念网址和来源列表。
            - 问天气用 get_weather。
            - 这个通话里你只陪聊，不能操作手机（设闹钟、打电话、发消息、打开应用、放音乐、调开关都做不了）。对方让你做这些时，温和地说明，并告诉对方可以说“切回助手”，让语音助手去办。
            - 对方明确要结束通话（再见、晚安、先聊到这、挂了吧、退下吧、你走吧）时调用 hang_up，并在同一条回复里说一句简短温暖的道别（调用之后就挂断了，不会再轮到你说话）。只要你在道别，就一定要同时调用 hang_up：光说再见不调用，电话不会挂断。
            - 被问到时可以坦诚自己是 AI，一只 AI 小猪，但不用主动强调，也不说“作为一个 AI”这种套话。
        """.trimIndent()

        private val MEMORY_RULES = """
            记忆：
            - 下面是你从以前的通话里记得的关于对方的事，和最近几次通话聊了什么。像老朋友一样自然地用上：称呼、喜好、对方在意的人和事，聊到相关的话题时自然接上。不要说“根据我的记忆”“我的记录显示”这种话，也不要一次把记得的事全倒出来。
            - 偶尔主动关心：如果记得对方之前说过、现在该有结果或正在进行的事（面试、考试、出行、手头的项目），可以在合适的时候自然地问一句近况。一次通话最多主动提一次，对方不接话就不再提。让人难过的事（失恋、亲人生病、离别）对方不提，你就不主动提。
            - 记忆可能过时或记错：对方说的和记忆不一样时以对方说的为准，不争辩。
            - 对方明确让你“记住”什么时用 remember；让你“忘掉”什么时用 forget_memory（用方括号里的编号），然后简短说一句已经忘掉了；聊到很久以前的事、下面没写到时，可以用 recall_memory 翻一翻以前的通话。
            - 对方问你都记得他什么时，挑重点如实说，并告诉他可以说“忘掉某某事”让你删掉，也可以在设置里查看和管理。
        """.trimIndent()
        private const val MAX_ROUNDS = 6

        /** said when the model hung up without a word */
        private const val HANG_UP_FAREWELL = "好的，那噜噜先挂啦，拜拜。"

        /** messages kept besides the system prompt (about ten exchanges with tool calls) */
        private const val MAX_HISTORY = 40
        private val MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
