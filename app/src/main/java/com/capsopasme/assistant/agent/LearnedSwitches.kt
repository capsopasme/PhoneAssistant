package com.capsopasme.assistant.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * System switches as this phone's own Quick Settings tiles set them.
 *
 * Some switches are OEM features with their own settings keys: ColorOS's 护眼模式 and 省电模式
 * aren't the AOSP night light / battery saver the default commands drive. Rather than guessing
 * keys, the phone shows us: the settings are listed with the switch off, on, and off again (the
 * user flips the tile in between); keys that changed with the switch and changed back are its
 * keys, with their on / off values. [Tools] then writes those instead of the default command.
 *
 * Only plain key / value pairs from `settings list` are kept, checked against strict patterns,
 * so writing them back can't run anything else.
 */
object LearnedSwitches {

    /** one settings key of a learned switch; a null value means the key is absent ("null") */
    class Entry(val namespace: String, val key: String, val on: String?, val off: String?) {
        fun valueFor(enabled: Boolean) = if (enabled) on else off
        override fun toString() = "$namespace/$key：开=${on ?: "（无）"}，关=${off ?: "（无）"}"
    }

    /** switches that can be learned: toggle_setting name to label */
    val LEARNABLE = linkedMapOf(
        "night_light" to "护眼模式",
        "extra_dim" to "极暗模式",
        "battery_saver" to "省电模式",
    )

    private const val PREFS = "learned_switches"
    private val NAMESPACES = listOf("system", "secure", "global")
    private val KEY_OK = Regex("^[A-Za-z0-9_.:-]{1,96}$")
    private val VALUE_OK = Regex("^[A-Za-z0-9_.:,+-]{0,64}$")

    /** more keys than this changed together: something else changed too, don't trust it */
    private const val MAX_KEYS = 6

    // ---------------------------------------------------------------------------------------------
    // storage

    fun get(ctx: Context, setting: String): List<Entry>? {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(setting, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("ns"), o.getString("key"), o.optStringOrNull("on"), o.optStringOrNull("off"))
            }.filter { valid(it) }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun put(ctx: Context, setting: String, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("ns", e.namespace).put("key", e.key)
                .put("on", e.on ?: JSONObject.NULL).put("off", e.off ?: JSONObject.NULL))
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(setting, arr.toString()).apply()
    }

    fun remove(ctx: Context, setting: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(setting).apply()
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private fun valid(e: Entry) = e.namespace in NAMESPACES && KEY_OK.matches(e.key) &&
            (e.on == null || VALUE_OK.matches(e.on)) && (e.off == null || VALUE_OK.matches(e.off)) && e.on != e.off

    // ---------------------------------------------------------------------------------------------
    // learning

    /** "namespace/key" to value; null without root. Blocking, off the main thread. */
    fun snapshot(): Map<String, String>? {
        val cmd = NAMESPACES.joinToString("; ") { "echo '@@ns $it'; settings list $it" }
        val r = RootShell.runReading(cmd, 15_000)
        if (!r.ok || !r.output.contains("@@ns global")) return null
        val out = HashMap<String, String>()
        var ns = ""
        for (line in r.output.lineSequence()) {
            if (line.startsWith("@@ns ")) {
                ns = line.removePrefix("@@ns ").trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || ns.isEmpty()) continue
            out["$ns/${line.substring(0, eq)}"] = line.substring(eq + 1)
        }
        return out
    }

    sealed interface Outcome {
        class Learned(val entries: List<Entry>) : Outcome
        class Failed(val reason: String) : Outcome
    }

    /** [off] / [on] / [offAgain]: the settings with the switch off, on, and off again */
    fun diff(off: Map<String, String>, on: Map<String, String>, offAgain: Map<String, String>): Outcome {
        val keys = off.keys + on.keys + offAgain.keys
        val changed = keys.filter { k -> off[k] == offAgain[k] && on[k] != off[k] }
        if (changed.isEmpty()) {
            return Outcome.Failed("没有找到跟着这个开关变化的系统设置项：它可能不是靠系统设置实现的，继续用默认方式。")
        }
        if (changed.size > MAX_KEYS) {
            return Outcome.Failed("同时变化的设置项太多（${changed.size} 个），可能顺手改了别的设置，请只切换这一个开关再试一次。")
        }
        val entries = changed.mapNotNull { k ->
            val ns = k.substringBefore('/')
            Entry(ns, k.substringAfter('/'), on[k].nullIfUnset(), off[k].nullIfUnset()).takeIf { valid(it) }
        }
        if (entries.isEmpty()) return Outcome.Failed("变化的设置项格式不支持，继续用默认方式。")
        return Outcome.Learned(entries)
    }

    private fun String?.nullIfUnset(): String? = if (this == null || this == "null") null else this

    // ---------------------------------------------------------------------------------------------
    // using

    /** shell command printing the current value of the first key */
    fun stateQuery(entries: List<Entry>): String = entries.first().let { "settings get ${it.namespace} ${it.key}" }

    /** the value [stateQuery] prints when the switch is [enabled] */
    fun expected(entries: List<Entry>, enabled: Boolean): String = entries.first().valueFor(enabled) ?: "null"

    /** shell commands that set every key of the switch */
    fun writeCommand(entries: List<Entry>, enabled: Boolean): String = entries.joinToString("; ") { e ->
        val v = e.valueFor(enabled)
        if (v == null) "settings delete ${e.namespace} ${e.key}" else "settings put ${e.namespace} ${e.key} ${RootShell.quote(v)}"
    }
}
