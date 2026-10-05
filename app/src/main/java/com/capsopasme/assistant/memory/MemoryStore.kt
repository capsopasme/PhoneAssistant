package com.capsopasme.assistant.memory

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 噜噜's memory of the user, on the phone only (app private files; the app has backup off):
 *
 * - facts: short lines about the user ("用户的猫叫团子"), distilled from each call after it ended
 *   ([MemoryDistiller]) or saved when the user asks ("记住…"). All of them go into the call's
 *   system prompt, with their numbers, so "忘掉…" can name the right one.
 * - calls: one or two sentences per call, what it was about. The last few go into the prompt,
 *   older ones are found with the recall_memory tool.
 * - pending: transcripts of calls not distilled yet (no network, no API key yet).
 *
 * Main process only; every access holds this object's lock, files are replaced atomically.
 */
object MemoryStore {

    class Fact(val id: Int, val text: String, val time: Long)

    class CallSummary(val time: Long, val text: String)

    /** one call, waiting to be distilled */
    class Transcript(val file: File, val time: Long, val turns: List<Pair<String, String>>)

    private const val TAG = "MemoryStore"
    private const val FILE = "memory.json"
    private const val PENDING_DIR = "memory_pending"

    /** more than this: the distiller is asked to merge, beyond it the oldest go */
    const val MAX_FACTS = 120
    const val MERGE_ABOVE = 80
    private const val MAX_FACT_CHARS = 100
    private const val MAX_CALLS = 300
    private const val MAX_SUMMARY_CHARS = 160
    private const val MAX_PENDING = 20

    /** a transcript keeps its latest turns within this many characters */
    private const val MAX_TRANSCRIPT_CHARS = 16_000

    /** call summaries in the system prompt */
    private const val PROMPT_CALLS = 5

    private var loaded = false
    private val facts = ArrayList<Fact>()
    private val calls = ArrayList<CallSummary>()
    private var nextId = 1

    /** bumped on every change: a call in progress rebuilds its prompt when it moved */
    @Volatile
    var version = 0
        private set

    // ---------------------------------------------------------------------------------------------
    // reading

    @Synchronized
    fun facts(ctx: Context): List<Fact> {
        load(ctx)
        return ArrayList(facts)
    }

    /** oldest first */
    @Synchronized
    fun calls(ctx: Context): List<CallSummary> {
        load(ctx)
        return ArrayList(calls)
    }

    /**
     * What 噜噜 knows, for the call's system prompt; null when there's nothing yet.
     */
    @Synchronized
    fun promptBlock(ctx: Context, zone: ZoneId): String? {
        load(ctx)
        if (facts.isEmpty() && calls.isEmpty()) return null
        return buildString {
            if (facts.isNotEmpty()) {
                append("你记得的关于对方的事（方括号里是编号）：\n")
                for (f in facts) append("[").append(f.id).append("] ").append(f.text).append('\n')
            }
            if (calls.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("最近几次通话聊了什么（从早到晚）：\n")
                for (c in calls.takeLast(PROMPT_CALLS)) append("- ").append(stamp(c.time, zone)).append("：").append(c.text).append('\n')
            }
        }.trimEnd()
    }

    /**
     * Facts and call summaries sharing the most words (Chinese: two-character pieces) with
     * [query]; the latest calls when nothing matches.
     */
    @Synchronized
    fun search(ctx: Context, query: String, zone: ZoneId, limit: Int = 6): List<String> {
        load(ctx)
        val keys = pieces(query)
        val scored = ArrayList<Pair<Int, String>>()
        for (f in facts) {
            val s = score(f.text, keys)
            if (s > 0) scored.add(s to "[${f.id}] ${f.text}")
        }
        for (c in calls) {
            val s = score(c.text, keys)
            if (s > 0) scored.add(s to "${stamp(c.time, zone)}的通话：${c.text}")
        }
        if (scored.isEmpty()) return calls.takeLast(3).map { "${stamp(it.time, zone)}的通话：${it.text}" }
        return scored.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    // ---------------------------------------------------------------------------------------------
    // writing

    /** saved right away ("记住…"); null if it was empty or known already */
    @Synchronized
    fun addFact(ctx: Context, text: String): Fact? {
        load(ctx)
        val t = clean(text, MAX_FACT_CHARS) ?: return null
        if (facts.any { it.text == t }) return null
        val f = Fact(nextId++, t, System.currentTimeMillis())
        facts.add(f)
        capFacts()
        save(ctx)
        return f
    }

    /** @return the texts that were removed */
    @Synchronized
    fun removeFacts(ctx: Context, ids: Collection<Int>): List<String> {
        load(ctx)
        val gone = facts.filter { it.id in ids }
        if (gone.isEmpty()) return emptyList()
        facts.removeAll(gone.toSet())
        save(ctx)
        return gone.map { it.text }
    }

    /** facts mentioning [query] (forgetting by description, when no number was given) */
    @Synchronized
    fun findFacts(ctx: Context, query: String): List<Fact> {
        load(ctx)
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        facts.filter { it.text.contains(q) }.takeIf { it.isNotEmpty() }?.let { return it }
        val keys = pieces(q)
        if (keys.isEmpty()) return emptyList()
        // most of the query's pieces in it
        return facts.filter { score(it.text, keys) * 2 >= keys.size }
    }

    @Synchronized
    fun removeCall(ctx: Context, time: Long) {
        load(ctx)
        if (calls.removeAll { it.time == time }) save(ctx)
    }

    /** everything: facts, call summaries, and calls not distilled yet */
    @Synchronized
    fun clear(ctx: Context) {
        load(ctx)
        facts.clear()
        calls.clear()
        save(ctx)
        pendingDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * The distiller's result for one call, applied at once.
     * @param time when the call started
     */
    @Synchronized
    fun apply(ctx: Context, add: List<String>, update: Map<Int, String>, delete: Collection<Int>, summary: String?, time: Long) {
        load(ctx)
        facts.removeAll { it.id in delete }
        val now = System.currentTimeMillis()
        for ((id, text) in update) {
            val i = facts.indexOfFirst { it.id == id }
            val t = clean(text, MAX_FACT_CHARS) ?: continue
            if (i >= 0) facts[i] = Fact(id, t, now)
        }
        for (text in add) {
            val t = clean(text, MAX_FACT_CHARS) ?: continue
            if (facts.none { it.text == t }) facts.add(Fact(nextId++, t, now))
        }
        capFacts()
        clean(summary ?: "", MAX_SUMMARY_CHARS)?.let {
            calls.add(CallSummary(time, it))
            calls.sortBy { c -> c.time }
            while (calls.size > MAX_CALLS) calls.removeAt(0)
        }
        save(ctx)
    }

    // ---------------------------------------------------------------------------------------------
    // calls waiting to be distilled

    /** keeps what was said in a call that just ended; the latest turns if it was very long */
    @Synchronized
    fun savePending(ctx: Context, time: Long, turns: List<Pair<String, String>>) {
        if (turns.isEmpty()) return
        val kept = ArrayList<Pair<String, String>>()
        var chars = 0
        for (turn in turns.asReversed()) {
            chars += turn.first.length + turn.second.length
            if (chars > MAX_TRANSCRIPT_CHARS && kept.isNotEmpty()) break
            kept.add(turn)
        }
        kept.reverse()
        val arr = JSONArray()
        for ((u, a) in kept) arr.put(JSONObject().put("u", u).put("a", a))
        val dir = pendingDir(ctx).apply { mkdirs() }
        write(File(dir, "$time.json"), JSONObject().put("t", time).put("turns", arr).toString())
        // a phone that never gets online doesn't collect them forever
        val files = pendingFiles(ctx)
        if (files.size > MAX_PENDING) files.take(files.size - MAX_PENDING).forEach { it.delete() }
    }

    /** oldest first */
    @Synchronized
    fun pendingFiles(ctx: Context): List<File> =
        pendingDir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun readPending(file: File): Transcript? = try {
        val o = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        val arr = o.getJSONArray("turns")
        val turns = (0 until arr.length()).map { arr.getJSONObject(it).let { t -> t.optString("u") to t.optString("a") } }
        Transcript(file, o.optLong("t"), turns)
    } catch (e: Exception) {
        Log.w(TAG, "unreadable ${file.name}", e)
        null
    }

    @Synchronized
    fun deletePending(file: File) {
        AtomicFile(file).delete()
    }

    // ---------------------------------------------------------------------------------------------

    private fun pendingDir(ctx: Context) = File(ctx.filesDir, PENDING_DIR)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, FILE)
        try {
            val o = JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
            nextId = o.optInt("next", 1)
            o.optJSONArray("facts")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    facts.add(Fact(x.getInt("id"), x.getString("text"), x.optLong("t")))
                }
            }
            o.optJSONArray("calls")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    calls.add(CallSummary(x.getLong("t"), x.getString("text")))
                }
            }
            nextId = maxOf(nextId, (facts.maxOfOrNull { it.id } ?: 0) + 1)
        } catch (_: java.io.FileNotFoundException) {
            // nothing remembered yet
        } catch (e: Exception) {
            Log.w(TAG, "memory unreadable, starting empty", e)
            facts.clear()
            calls.clear()
        }
    }

    private fun save(ctx: Context) {
        version++
        val fa = JSONArray()
        for (f in facts) fa.put(JSONObject().put("id", f.id).put("text", f.text).put("t", f.time))
        val ca = JSONArray()
        for (c in calls) ca.put(JSONObject().put("t", c.time).put("text", c.text))
        write(File(ctx.filesDir, FILE), JSONObject().put("next", nextId).put("facts", fa).put("calls", ca).toString())
    }

    private fun write(file: File, text: String) {
        val af = AtomicFile(file)
        val out = try {
            af.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "can't write ${file.name}", e)
            return
        }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            Log.w(TAG, "can't write ${file.name}", e)
        }
    }

    /** too many: the oldest go */
    private fun capFacts() {
        if (facts.size <= MAX_FACTS) return
        facts.sortBy { it.time }
        while (facts.size > MAX_FACTS) facts.removeAt(0)
        facts.sortBy { it.id }
    }

    private fun clean(text: String, max: Int): String? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.isEmpty()) null else t.take(max)
    }

    private val DATE = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)

    private fun stamp(time: Long, zone: ZoneId): String = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), zone).format(DATE)

    /** two-character pieces of the Chinese, whole words of anything else */
    private fun pieces(s: String): Set<String> {
        val out = HashSet<String>()
        val han = StringBuilder()
        val word = StringBuilder()
        fun flushHan() {
            if (han.length == 1) out.add(han.toString())
            for (i in 0 until han.length - 1) out.add(han.substring(i, i + 2))
            han.setLength(0)
        }
        fun flushWord() {
            if (word.length >= 2) out.add(word.toString().lowercase())
            word.setLength(0)
        }
        for (ch in s) {
            when {
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                    flushWord()
                    han.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushHan()
                    word.append(ch)
                }
                else -> {
                    flushHan()
                    flushWord()
                }
            }
        }
        flushHan()
        flushWord()
        // too common to mean anything
        out.removeAll(setOf("用户", "对方", "什么", "一下", "就是", "这个", "那个", "我们", "你们", "他们"))
        return out
    }

    private fun score(text: String, keys: Set<String>): Int {
        val lower = text.lowercase()
        return keys.count { lower.contains(it) }
    }
}
