package com.capsopasme.assistant.memory

import android.content.Context
import android.util.Log
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns the transcripts of finished calls into memory: one request per call to the model chosen
 * in the settings, which returns what to add, change or drop among the facts, and a summary of
 * the call. Runs from [MemoryJobService], blocking, on a worker thread.
 */
object MemoryDistiller {

    private const val TAG = "MemoryDistiller"

    @Volatile
    private var client: LlmClient? = null

    /** why the last attempt failed, for the memory page; set while distilling */
    @Volatile
    private var lastError: String? = null

    private const val STATUS = "memory_status"
    private const val KEY_PROBLEM = "problem"

    /** what keeps the waiting calls from being distilled, shown on the memory page; null if nothing */
    fun problem(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(STATUS, Context.MODE_PRIVATE).getString(KEY_PROBLEM, null)

    fun setProblem(ctx: Context, text: String?) {
        ctx.applicationContext.getSharedPreferences(STATUS, Context.MODE_PRIVATE).edit().apply {
            if (text == null) remove(KEY_PROBLEM) else putString(KEY_PROBLEM, text)
        }.apply()
    }

    /** aborts the running request (the job was stopped) */
    fun cancel() {
        client?.cancel()
    }

    /**
     * Distills every waiting call, oldest first. A call that can't be distilled (the provider
     * refuses its content, the answer is never the JSON asked for, it keeps failing) is dropped
     * rather than left in front of the others, so one bad call never stops the memory.
     * @return true to retry later (no network, the server busy), false when done or when retrying
     * can't help until the settings change (no key, a wrong key or model). One run at a time: a
     * job stopped and scheduled again waits for the stopped one to let go.
     */
    @Synchronized
    fun runPending(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        if (!prefs.memoryEnabled) {
            // turned off since the call: what it said is not to be kept
            MemoryStore.pendingFiles(ctx).forEach { MemoryStore.deletePending(it) }
            return false
        }
        // no key yet: the calls wait, the next call's end schedules this again
        val provider = prefs.currentProvider() ?: run {
            setProblem(ctx, "${prefs.provider.label} 还没有填 API Key")
            return false
        }
        for (file in MemoryStore.pendingFiles(ctx)) {
            if (Thread.currentThread().isInterrupted) return true
            val transcript = MemoryStore.readPending(file)
            if (transcript == null || transcript.turns.isEmpty()) {
                MemoryStore.deletePending(file)
                continue
            }
            when (val outcome = distill(ctx, provider, transcript)) {
                is Outcome.Done -> {
                    val r = outcome.result
                    if (MemoryStore.applyPending(ctx, file, r.add, r.update, r.delete, r.summary, transcript.time)) {
                        Log.i(TAG, "call ${transcript.time}: +${r.add.size} ~${r.update.size} -${r.delete.size}")
                    }
                    setProblem(ctx, null)
                }
                Outcome.Cancelled -> return true
                Outcome.Later -> {
                    setProblem(ctx, "暂时连不上（${lastError ?: "网络或服务器忙"}），稍后自动重试")
                    return true
                }
                Outcome.Settings -> {
                    setProblem(ctx, "请检查设置里的 API Key 和模型：${lastError ?: "请求被拒绝"}")
                    return false
                }
                Outcome.Failed -> {
                    if (MemoryStore.noteFailure(file) < MAX_TRIES) {
                        setProblem(ctx, "整理失败（${lastError ?: "未知错误"}），稍后自动重试")
                        return true
                    }
                    Log.w(TAG, "call ${transcript.time}: failed $MAX_TRIES times, dropped")
                    MemoryStore.deletePending(file)
                    setProblem(ctx, "有一次通话连续失败 $MAX_TRIES 次，已跳过：${lastError ?: "未知错误"}")
                }
                Outcome.Skip -> {
                    Log.w(TAG, "call ${transcript.time}: can't be distilled, dropped")
                    MemoryStore.deletePending(file)
                    setProblem(ctx, "有一次通话整理不了，已跳过${lastError?.let { "：$it" } ?: ""}")
                }
            }
        }
        return false
    }

    private class Result(
        val add: List<String>,
        val update: Map<Int, String>,
        val delete: List<Int>,
        val summary: String?,
    )

    private sealed interface Outcome {
        class Done(val result: Result) : Outcome

        /** the job was stopped */
        data object Cancelled : Outcome

        /** no network, or the provider busy / out of quota: later, as often as it takes */
        data object Later : Outcome

        /** a server error or a broken stream: retried, but only [MAX_TRIES] times for one call */
        data object Failed : Outcome

        /** the key or the model is wrong: everything waits until the settings change */
        data object Settings : Outcome

        /** this call can't be distilled as it is (refused, too long, never valid JSON) */
        data object Skip : Outcome
    }

    private fun distill(ctx: Context, provider: LlmClient.Provider, transcript: MemoryStore.Transcript): Outcome {
        lastError = null
        // an answer that isn't the JSON asked for: ask once more, then give up on this call
        repeat(2) {
            val c = LlmClient()
            client = c
            val text = try {
                c.chat(provider, request(ctx, transcript), JSONArray(), maxTokens = MAX_TOKENS) {}.text
            } catch (e: LlmClient.LlmException) {
                if (c.cancelled) return Outcome.Cancelled
                Log.w(TAG, "distilling failed", e)
                lastError = e.message
                return classify(e)
            } catch (e: IOException) {
                if (c.cancelled) return Outcome.Cancelled
                Log.w(TAG, "distilling failed", e)
                lastError = e.message ?: e.javaClass.simpleName
                return Outcome.Later
            } finally {
                client = null
            }
            parse(text)?.let { return Outcome.Done(it) }
        }
        lastError = "模型没有按要求返回 JSON"
        return Outcome.Skip
    }

    private fun classify(e: LlmClient.LlmException): Outcome {
        val code = e.httpCode
        return when {
            // refused by moderation, or the answer cut off: the same again would fail the same way
            e.permanent -> Outcome.Skip
            // an error event in the stream, an empty answer
            code == 0 -> Outcome.Failed
            code == 429 -> Outcome.Later
            code == 408 || code >= 500 -> Outcome.Failed
            code == 401 || code == 402 || code == 403 || code == 404 -> Outcome.Settings
            // DeepSeek and GLM answer an unknown model with a 400 too
            UNKNOWN_MODEL.containsMatchIn(e.message.orEmpty()) -> Outcome.Settings
            // 400 / 413 / 422: about this call's content (GLM and DeepSeek refuse input that trips
            // their moderation with a 400)
            else -> Outcome.Skip
        }
    }

    /** failed attempts at one call before it's dropped (no network doesn't count) */
    private const val MAX_TRIES = 4

    /** room for merging a long memory, and for Gemini's thinking (GLM-4-flash allows 4095) */
    private const val MAX_TOKENS = 4000

    private val UNKNOWN_MODEL = Regex(
        "(?i)model[^.]{0,40}(not exist|not found|does not exist|not supported)|invalid model|model_not_found|模型不存在|模型.{0,8}不支持",
    )

    private fun request(ctx: Context, t: MemoryStore.Transcript): JSONArray {
        val zone = ZoneId.systemDefault()
        val started = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.time), zone)
        val date = started.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA))
        val facts = MemoryStore.facts(ctx)
        val known = if (facts.isEmpty()) "（还没有）" else facts.joinToString("\n") { "[${it.id}] ${it.text}" }
        val talk = t.turns.joinToString("\n") { (u, a) -> "用户：$u\n噜噜：$a" }
        val user = "已有的记忆：\n$known\n\n这次通话（开始于 $date）：\n$talk"
        return JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions(date, facts.size)))
            .put(JSONObject().put("role", "user").put("content", user))
    }

    private fun instructions(date: String, count: Int) = """
        你在帮“噜噜”（用户的语音聊天伙伴，一只 AI 小猪）整理它对用户的记忆。下面会给你已有的记忆（每条带编号）和刚结束的一次通话记录。用户的话来自语音识别，可能有同音错字，按最合理的意思理解。
        只输出一个 JSON 对象，不要任何别的文字：
        {"add": ["新记忆", ...], "update": [{"id": 编号, "text": "改后的内容"}], "delete": [编号, ...], "summary": "这次通话聊了什么"}

        记什么：用户亲口说的、以后聊天还用得上的事——怎么称呼他、家人朋友和宠物（名字、关系）、工作学习、住在哪个城市、作息和习惯、喜欢和讨厌的东西、正在做的事和计划（带上具体日期，比如“10月12日上午有面试”）、最近在意或烦恼的事。
        不记：你自己的推测；只在这次通话里有意义的小事；噜噜说的话、给的建议、搜到的信息；密码、验证码、身份证号、银行卡号、详细门牌地址这类信息。
        写法：每条一句话，以“用户”开头，简短具体，不超过四十个字；“明天”“下周五”这类相对时间按通话日期 $date 换成具体日期。
        合并：和已有记忆说的是同一件事就用 update 改那一条，不要重复添加；被这次通话推翻或已经过时的（计划已经完成、换了工作、事情已经解决）就 update 或 delete。用户在通话里要求忘掉的事要 delete，也不要再 add。
        ${if (count > MemoryStore.MERGE_ABOVE) "现在记忆有 $count 条，太多了：把相关的合并成一条（update 其中一条、delete 其余），删掉不再重要的，尽量减到 ${MemoryStore.MERGE_ABOVE} 条以内。" else ""}
        summary：一两句话、五十个字以内，说这次聊了什么、用户当时的状态，比如“聊了周末去爬山的计划，用户最近工作有点累”。
        没有值得记的就给空数组；summary 一定要有。
    """.trimIndent()

    /** the first {...} in the answer (models sometimes wrap it in a code block) */
    private fun parse(text: String): Result? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(text.substring(start, end + 1))
            val add = ArrayList<String>()
            o.optJSONArray("add")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(add::add) }
            val update = LinkedHashMap<Int, String>()
            o.optJSONArray("update")?.let { a ->
                for (i in 0 until a.length()) {
                    val u = a.optJSONObject(i) ?: continue
                    val id = u.optInt("id", -1)
                    val t = u.optString("text")
                    if (id >= 0 && t.isNotBlank()) update[id] = t
                }
            }
            val delete = ArrayList<Int>()
            o.optJSONArray("delete")?.let { a -> for (i in 0 until a.length()) a.optInt(i, -1).takeIf { it >= 0 }?.let(delete::add) }
            Result(add, update, delete, o.optString("summary").takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            Log.w(TAG, "not the JSON asked for: ${text.take(200)}")
            null
        }
    }
}
