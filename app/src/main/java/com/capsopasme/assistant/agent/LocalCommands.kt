package com.capsopasme.assistant.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Commands simple enough to run without the language model: "打开 xx" and "倒计时 xx".
 * Parsed and executed on the phone (no network, no API key), in well under a second.
 *
 * Anything this isn't sure about returns null and goes to the model as usual: settings switches
 * ("打开蓝牙"), compound commands ("打开微信然后…"), in-app actions ("打开微信扫一扫"),
 * names that match no app or several apps equally well, durations it can't read.
 */
object LocalCommands {

    sealed interface Command
    class OpenApp(val label: String, val intent: Intent) : Command
    class Timer(val seconds: Int) : Command

    private val POLITE_PREFIX = Regex("^(请你|请|帮我|给我|麻烦你|麻烦|帮忙|你|我想|我要|快)+")
    private val POLITE_SUFFIX = Regex("(一下子|一下|吧|啊|呀|哈|呗|好吗|好不好|谢谢)+$")
    private val OPEN_VERB = Regex("^(打开|启动|开启|运行|进入)")
    private val APP_SUFFIX = Regex("(这个应用|这个软件|应用程序|应用|软件|app|程序)$", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = Regex("[\\s。.!！?？~～,，、]+$")

    /** words that turn a sentence into something more than one simple command */
    private val COMPOUND = listOf("然后", "并且", "并", "再", "接着", "同时", "之后", "以后", "和", "跟", "，", ",", "。")

    /** "打开 X" where X is a system switch or a function, not an app: the model has tools for these */
    private val NOT_APPS = listOf(
        "蓝牙", "wifi", "无线", "网络", "流量", "数据", "热点", "飞行", "勿扰", "免打扰", "定位", "gps", "位置",
        "nfc", "手电", "闪光灯", "旋转", "深色", "暗色", "夜间", "省电", "静音", "震动", "振动", "铃声",
        "亮度", "音量", "闹钟", "日程", "导航", "去", "地图上",
    )

    fun parse(context: Context, text: String): Command? {
        // recognizers end sentences with punctuation: "打开微信。"
        val raw = text.trim().replace(TRAILING_PUNCT, "")
        if (raw.isEmpty() || raw.length > 30) return null
        if (COMPOUND.any { raw.contains(it) }) return null
        val s = clean(raw)
        if (s.isEmpty()) return null

        parseTimer(s)?.let { return it }

        val m = OPEN_VERB.find(s) ?: return null
        var name = s.substring(m.range.last + 1).replace(APP_SUFFIX, "")
        if (name.isEmpty() || name.length > 15) return null
        if (NOT_APPS.any { name.lowercase().contains(it) }) return null
        name = name.removePrefix("一下")
        return findApp(context, name, strict = true)
    }

    private fun clean(s: String): String {
        // keep "." : "2.5分钟"
        var t = s.replace(Regex("[\\s！!？?、~～“”\"']"), "")
        repeat(2) {
            t = t.replace(POLITE_PREFIX, "").replace(POLITE_SUFFIX, "")
        }
        return t
    }

    // ---------------------------------------------------------------------------------------------
    // apps

    class AppMatch(val label: String, val pkg: String, val activity: String, val score: Double)

    /** All launcher activities ranked against the spoken name, best first */
    fun rankApps(context: Context, name: String): List<AppMatch> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = FuzzyMatch.norm(name)
        val latinQuery = q.isNotEmpty() && q.all { it.code < 128 }
        return pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0)).mapNotNull { ri ->
            val info = ri.activityInfo
            if (info.packageName == context.packageName) return@mapNotNull null
            val label = ri.loadLabel(pm).toString()
            var score = FuzzyMatch.score(name, label)
            // English names of Chinese apps: "wechat" -> com.tencent.mm won't match, but
            // "bilibili" -> tv.danmaku.bili / "taobao" -> com.taobao.taobao do
            if (latinQuery && q.length >= 4 && info.packageName.lowercase().contains(q)) score = maxOf(score, 0.8)
            if (score > 0) AppMatch(label, info.packageName, info.name, score) else null
        }
            .sortedByDescending { it.score }
            .distinctBy { it.pkg }
    }

    /**
     * @param strict local fast path: only a clear winner (near-exact name or sound, and well
     * ahead of the runner-up), otherwise let the model handle it
     */
    fun findApp(context: Context, name: String, strict: Boolean): OpenApp? {
        val ranked = rankApps(context, name)
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val ok = if (strict) best.score >= 0.82 && best.score - second >= 0.08
        else best.score >= 0.6 && best.score - second >= 0.05
        if (!ok) return null
        return OpenApp(best.label, launchIntent(best))
    }

    fun launchIntent(m: AppMatch): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(m.pkg, m.activity)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    // ---------------------------------------------------------------------------------------------
    // timer

    private val TIMER_WORDS = listOf("倒计时", "倒数", "计时器", "计时")
    private val TIMER_NEGATIVE = listOf("取消", "停止", "停掉", "关闭", "关掉", "暂停", "删除", "还剩", "查看", "看看", "多久")

    private const val NUM = "[0-9０-９.点零〇一二两俩三四五六七八九十百千]+"
    private val DURATION = Regex("($NUM|半)个?(半)?(小时|钟头|分钟|分|秒钟|秒|刻钟|刻)(半)?")

    /** "倒计时5分钟" "定一个三分半的倒计时" "一个半小时倒计时" "计时二十秒" */
    fun parseTimer(s: String): Timer? {
        if (TIMER_WORDS.none { s.contains(it) }) return null
        if (TIMER_NEGATIVE.any { s.contains(it) }) return null
        var seconds = 0.0
        var found = false
        for (m in DURATION.findAll(s)) {
            val numText = m.groupValues[1]
            val n = if (numText == "半") 0.5 else parseNumber(numText) ?: return null
            val half = m.groupValues[2].isNotEmpty()
            val unit = when (m.groupValues[3]) {
                "小时", "钟头" -> 3600.0
                "分钟", "分" -> 60.0
                "秒钟", "秒" -> 1.0
                else -> 900.0 // 刻 / 刻钟
            }
            seconds += (n + if (half) 0.5 else 0.0) * unit
            // "三分半" = 3.5 min, "一小时半" = 1.5 h
            if (m.groupValues[4].isNotEmpty()) seconds += 0.5 * unit
            found = true
        }
        if (!found) return null
        // leftovers like "倒计时5分钟后叫我做饭" are more than a timer: let the model decide
        val rest = DURATION.replace(s, "").let { r -> TIMER_WORDS.fold(r) { acc, w -> acc.replace(w, "") } }
            .replace(Regex("^(设置|设定|设|定|开始|开|来|弄|搞)?(一个|一下|个)?"), "")
            .replace(Regex("(的)?$"), "")
        if (rest.length > 2) return null
        val total = Math.round(seconds).toInt()
        return if (total in 1..86_400) Timer(total) else null
    }

    /** Arabic or Chinese numerals: 25, 2.5, 二十五, 两百, 一百零五, 十, 三点五 */
    fun parseNumber(text: String): Double? {
        val t = text.map { ch -> if (ch in '０'..'９') '0' + (ch - '０') else ch }.joinToString("")
            .replace('点', '.')
        t.toDoubleOrNull()?.let { return it }
        val dot = t.indexOf('.')
        if (dot >= 0) {
            val whole = parseChineseInt(t.substring(0, dot)) ?: return null
            val frac = t.substring(dot + 1).map { digit(it) ?: return null }.joinToString("")
            return "$whole.$frac".toDoubleOrNull()
        }
        return parseChineseInt(t)?.toDouble()
    }

    private fun digit(ch: Char): Int? = when (ch) {
        '零', '〇' -> 0
        '一' -> 1
        '二', '两', '俩' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        in '0'..'9' -> ch - '0'
        else -> null
    }

    private fun parseChineseInt(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0
        var current = 0
        var seenAny = false
        for (ch in s) {
            val d = digit(ch)
            when {
                d != null -> {
                    current = current * 10 + d // "一五" style digit strings too
                    seenAny = true
                }
                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                    seenAny = true
                }
                ch == '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                    seenAny = true
                }
                ch == '千' -> {
                    total += (if (current == 0) 1 else current) * 1000
                    current = 0
                    seenAny = true
                }
                else -> return null
            }
        }
        return if (seenAny) total + current else null
    }
}
