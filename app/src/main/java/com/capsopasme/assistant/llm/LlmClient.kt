package com.capsopasme.assistant.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenAI-compatible Chat Completions client with streaming and tool calls.
 * Works with DeepSeek and with Gemini's OpenAI compatibility endpoint.
 * No dependencies: HttpURLConnection + org.json. Blocking, call it from a worker thread.
 */
class LlmClient {

    enum class Kind { DeepSeek, Gemini }

    class Provider(val kind: Kind, val key: String, val model: String) {
        val url: String
            get() = when (kind) {
                Kind.DeepSeek -> "https://api.deepseek.com/chat/completions"
                Kind.Gemini -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            }
        val label: String get() = kind.name
    }

    class ToolCall(val id: String, val name: String, val arguments: String)

    class Reply(
        val text: String,
        val toolCalls: List<ToolCall>,
        /** to append to the conversation as is (keeps reasoning / signatures the API wants back) */
        val assistantMessage: JSONObject,
    )

    class LlmException(message: String, val httpCode: Int = 0) : IOException(message)

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    var cancelled = false
        private set

    /** Abort the running request from any thread */
    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * @param onText receives each streamed text delta (worker thread)
     */
    fun chat(provider: Provider, messages: JSONArray, tools: JSONArray, onText: (String) -> Unit): Reply {
        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", messagesFor(provider.kind, messages))
            if (tools.length() > 0) put("tools", tools)
            put("stream", true)
            // Gemini counts thinking tokens against this budget too
            put("max_tokens", 2048)
            when (provider.kind) {
                // non-thinking mode: these are short, latency-sensitive commands
                Kind.DeepSeek -> put("thinking", JSONObject().put("type", "disabled"))
                // Gemini 3 can't turn reasoning off, keep it minimal
                Kind.Gemini -> put("reasoning_effort", "low")
            }
        }
        if (cancelled) throw LlmException("已取消")
        val conn = URL(provider.url).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer ${provider.key}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                throw LlmException("${provider.label} HTTP $code ${extractError(err)}", code)
            }
            return readStream(conn, onText)
        } catch (e: IOException) {
            if (cancelled) throw LlmException("已取消")
            throw e
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    private class CallBuilder {
        var id = ""
        var name = ""
        val args = StringBuilder()
        var extra: JSONObject? = null
    }

    private fun readStream(conn: HttpURLConnection, onText: (String) -> Unit): Reply {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = ArrayList<CallBuilder>()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                if (cancelled) throw LlmException("已取消")
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val chunk = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    continue
                }
                chunk.optJSONObject("error")?.let { throw LlmException(it.optString("message", "未知错误")) }
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                delta.optStringOrNull("reasoning_content")?.let { reasoning.append(it) }
                delta.optStringOrNull("content")?.let {
                    if (it.isNotEmpty()) {
                        text.append(it)
                        onText(it)
                    }
                }
                val tc = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until tc.length()) {
                    val d = tc.optJSONObject(i) ?: continue
                    val id = d.optStringOrNull("id") ?: ""
                    val b: CallBuilder = when {
                        d.has("index") -> {
                            val idx = d.optInt("index")
                            while (calls.size <= idx) calls.add(CallBuilder())
                            calls[idx]
                        }
                        // no index (seen on some compatibility layers): a new id starts a new call
                        id.isNotEmpty() && calls.none { it.id == id } -> CallBuilder().also { calls.add(it) }
                        id.isNotEmpty() -> calls.first { it.id == id }
                        calls.isEmpty() -> CallBuilder().also { calls.add(it) }
                        else -> calls.last()
                    }
                    if (id.isNotEmpty()) b.id = id
                    d.optJSONObject("function")?.let { f ->
                        f.optStringOrNull("name")?.let { if (it.isNotEmpty()) b.name = it }
                        f.optStringOrNull("arguments")?.let { b.args.append(it) }
                    }
                    // Gemini thought signature, must be sent back with the call
                    d.optJSONObject("extra_content")?.let { b.extra = it }
                }
            }
        }

        val toolCalls = calls.filter { it.name.isNotEmpty() }.mapIndexed { i, b ->
            ToolCall(b.id.ifEmpty { "call_$i" }, b.name, b.args.toString().ifBlank { "{}" })
        }
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", text.toString())
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
            if (toolCalls.isNotEmpty()) {
                val arr = JSONArray()
                calls.filter { it.name.isNotEmpty() }.forEachIndexed { i, b ->
                    arr.put(JSONObject().apply {
                        put("id", toolCalls[i].id)
                        put("type", "function")
                        put("function", JSONObject().put("name", b.name).put("arguments", toolCalls[i].arguments))
                        b.extra?.let { put("extra_content", it) }
                    })
                }
                put("tool_calls", arr)
            }
        }
        return Reply(text.toString(), toolCalls, message)
    }

    /** Drop the other provider's private fields (after a fallback mid-conversation) */
    private fun messagesFor(kind: Kind, messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") != "assistant") {
                out.put(m)
                continue
            }
            val copy = JSONObject(m.toString())
            if (kind != Kind.DeepSeek) copy.remove("reasoning_content")
            if (kind != Kind.Gemini) {
                copy.optJSONArray("tool_calls")?.let { arr ->
                    for (j in 0 until arr.length()) arr.optJSONObject(j)?.remove("extra_content")
                }
            }
            out.put(copy)
        }
        return out
    }

    private fun extractError(body: String): String = try {
        val o = JSONObject(body)
        (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).take(200)
    } catch (_: Exception) {
        // Gemini sometimes wraps the error in an array
        try {
            JSONArray(body).optJSONObject(0)?.optJSONObject("error")?.optString("message")?.take(200) ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
