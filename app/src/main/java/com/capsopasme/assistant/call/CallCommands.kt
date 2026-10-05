package com.capsopasme.assistant.call

import com.capsopasme.assistant.agent.FuzzyMatch

/**
 * Entering and leaving the voice call, understood on the phone without the model: in the sheet
 * "进入通话模式" / "陪我聊聊" switches to the call; in the call "退出通话" / "挂了吧" / "拜拜" /
 * "晚安" hangs up, "切回助手" hangs up and opens the sheet.
 *
 * Robust against how people say it and how the recognizer writes it:
 * - polite words and particles around the command are ignored ("那我们进入通话模式吧", "好了拜拜啦");
 * - verbs and names are compared by sound (toneless pinyin, zh/z ch/c sh/s -ng/-n l/n folded),
 *   so homophones work: "童话模式", "推出通话", "进如通话模式", "挂段";
 * - the verb may come first or last ("关闭通话" / "通话关了吧" / "把通话关了");
 * - two commands in a row are fine ("好的拜拜挂了").
 *
 * And conservative: the whole utterance must be the command. "给妈妈打电话", "通话记录在哪",
 * "别挂", "我不想聊这个了", "算了不说了" are not commands (the last two are feelings for the
 * companion to answer, not a hang-up). Anything unsure goes to the model as usual, which in the
 * call can still hang up by itself.
 */
object CallCommands {

    sealed interface Command

    /** switch from the sheet to the voice call */
    data object Enter : Command

    /** end the call; [farewell] is said first; [toAssistant]: then open the assistant sheet */
    data class Exit(val farewell: String, val toAssistant: Boolean = false) : Command

    /** the sound of a text, one folded pinyin syllable per character (replaceable in tests) */
    internal var syllables: (String) -> List<String> = FuzzyMatch::syllables

    // ---------------------------------------------------------------------------------------------
    // normalising

    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）《》…～·~]+")

    private val PREFIX = Regex(
        "^(那就|那么|那我们|那咱们|那我|那|好的|好吧|好啦|好了|好|嗯嗯|嗯|哦|噢|行|ok|okay|请你|请|帮我|给我|麻烦你|麻烦|" +
                "你|我想要|我想|我要|可以|咱们|咱|我们|现在|马上|快点|快|嘿|喂|哎|诶|小助手|助手)+"
    )
    private val SUFFIX = Regex(
        "(一下子|一下|一会儿|一会|会儿|好不好|好吗|行不行|行吗|可以吗|可以不|谢谢你|谢谢|吧|啊|呀|哈|啦|了|呗|喽|咯|哦|嘛|呢|哟|噢|喔)+$"
    )

    fun normalize(text: String): String = PUNCT.replace(text, "").lowercase()

    /** only the particles at the end: "我要睡了" keeps its 我要 */
    private fun light(text: String): String {
        var s = normalize(text)
        repeat(2) { s = s.replace(SUFFIX, "") }
        return s
    }

    /** without the polite words and particles at both ends */
    fun core(text: String): String {
        var s = normalize(text)
        repeat(3) { s = s.replace(PREFIX, "").replace(SUFFIX, "") }
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // entering

    /** the call itself */
    private val CALL_NAMES = listOf(
        "通话模式", "语音通话", "语音通话模式", "电话模式", "聊天模式", "语音聊天", "语音聊天模式", "陪聊模式",
        "通话", "实时通话", "实时语音", "连续对话", "连续对话模式", "对话模式",
    )
    private val ENTER_VERBS = listOf(
        "", "进入", "打开", "开启", "开始", "启动", "启用", "切换到", "切换成", "切换为", "切换", "切到", "切成",
        "换到", "换成", "转到", "转成", "改成", "改为", "调成", "变成", "进", "开", "来", "来个", "来一个", "用", "使用",
        "我要", "要", "去", "回到",
    )
    private val ENTER_AFTER = listOf("打开", "开启", "开始", "启动", "开")

    /** "陪我聊聊" "我想跟你聊会儿天" "我们聊聊天" "给你打个电话": always with 你 / 我, never "给妈妈打电话" */
    private val CHAT_PATTERNS = listOf(
        Regex("^陪我(聊|说|唠|谈)(聊|说|唠|谈|一下|会儿|会|一会儿)?(天|说话|几句|心)?$"),
        Regex("^陪(陪)?我$"),
        Regex("^(跟|和|同)我(聊|唠|谈)(聊|唠|谈|一下|会儿|会|一会儿)?(天|几句|心)?$"),
        Regex("^(我)?(想|要)?(跟|和|同|找)你(聊|唠|谈)(聊|唠|谈|一下|会儿|会|一会儿)?(天|几句|心)?$"),
        Regex("^(来)?(聊|唠)(聊|唠|一下|会儿|会|一会儿)?(天|几句)$"),
        Regex("^(我)?(想|要)?(给|跟|和)你打(个|一个|通)?电话$"),
        Regex("^打(个|一个|通)?电话给你$"),
        Regex("^(跟|和)你(通话|通电话|语音|语音通话|打电话)$"),
    )

    private val ENTER_NEGATION = listOf("不", "别", "没", "退出", "关闭", "关掉", "结束", "取消", "停止", "离开")

    /** the sheet heard a request to switch to the voice call */
    fun isEnter(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty() || n.length > 16) return false
        val t = core(text)
        if (t.isEmpty() || ENTER_NEGATION.any { t.contains(it) }) return false
        if (CHAT_PATTERNS.any { it.matches(t) }) return true
        return verbThenName(t, ENTER_VERBS, CALL_NAMES) || nameThenVerb(t, CALL_NAMES, ENTER_AFTER) || callsLulu(t)
    }

    // ---------------------------------------------------------------------------------------------
    // 噜噜 by name

    private val LULU_NAMES = listOf("噜噜", "小猪噜噜", "噜噜猪", "小噜噜", "噜噜小猪")
    private val LULU_BEFORE = listOf("", "找", "叫", "喊", "召唤", "跟", "和", "同")
    private val LULU_AFTER = listOf(
        "", "来", "过来", "快来", "出来", "快出来", "在吗", "在不在", "你在吗", "你在哪",
        "聊聊", "聊天", "聊聊天", "聊会儿", "聊会儿天", "聊一会儿", "聊一下", "说说话", "唠唠", "陪陪我", "陪我聊聊",
    )

    /**
     * "噜噜" "叫噜噜来" "噜噜在吗" "我想跟噜噜聊聊天": compared by sound, so "路路" "鲁鲁" also call
     * it. Not "给露露打电话": calling someone stays a phone call.
     */
    private fun callsLulu(t: String): Boolean {
        val ts = syllables(t)
        if (ts.isEmpty() || ts.size > 9) return false
        for (b in LULU_BEFORE) {
            val bs = syllables(b)
            if (!startsWith(ts, bs)) continue
            for (a in LULU_AFTER) {
                val asx = syllables(a)
                if (bs.size + asx.size >= ts.size || !endsWith(ts, asx)) continue
                val name = ts.subList(bs.size, ts.size - asx.size)
                if (LULU_NAMES.any { name == syllables(it) }) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // leaving

    private val EXIT_NAMES = listOf(
        "通话模式", "语音通话", "语音通话模式", "电话模式", "聊天模式", "语音聊天", "语音聊天模式", "陪聊模式",
        "通话", "电话", "语音电话", "聊天", "对话", "会话", "连续对话", "对话模式",
    )
    private val EXIT_VERBS = listOf(
        "退出", "关闭", "结束", "关掉", "关上", "关了", "停止", "停掉", "断开", "离开", "取消", "终止", "中断", "挂断", "挂掉",
        "挂", "关", "停", "退",
    )
    private val EXIT_AFTER = listOf("结束", "关闭", "关掉", "关上", "关", "退出", "停止", "停", "挂断", "挂掉", "挂", "断开")

    private val HANG_UP = listOf(
        "退出", "结束", "关闭", "挂", "挂断", "挂掉", "挂上", "挂机", "先挂", "我先挂", "那我先挂", "我挂", "我要挂", "要挂", "挂电话", "挂断电话",
        "挂掉电话", "挂个电话", "先挂电话", "可以挂", "可以挂断", "你挂", "你挂吧", "你先挂",
        // "你退下吧" "下去吧" "跪安吧"
        "退下", "退下去", "下去", "你下去", "跪安", "退朝",
    )

    /** what is said, then the call ends */
    private class Farewell(val phrases: List<String>, val reply: String)

    private val FAREWELLS = listOf(
        Farewell(listOf(
            "晚安", "晚安安", "睡觉晚安", "晚安好梦", "好梦", "我要睡", "我要睡觉", "我去睡", "我去睡觉", "我先睡",
            "我先去睡", "去睡觉", "睡觉去", "该睡觉", "我该睡", "我该睡觉", "我该去睡", "我要去睡",
        ), "晚安，好梦，噜噜也去睡啦。"),
        Farewell(listOf(
            "再见", "再会", "拜拜", "拜", "白白", "掰掰", "bye", "byebye", "goodbye", "回见", "回头见", "下次见", "明天见",
            "改天见", "先这样再见", "就这样再见", "那再见", "拜了", "拜拜拜拜",
        ), "好，拜拜，想聊天了随时叫噜噜。"),
        Farewell(listOf(
            "下次聊", "下次再聊", "改天聊", "改天再聊", "回头聊", "回头再聊", "有空再聊", "以后再聊", "明天再聊", "明天聊",
            "先聊到这", "先聊到这里", "就聊到这", "就聊到这里", "今天就聊到这", "今天就聊到这里", "今天先聊到这",
            "聊到这", "聊到这里", "今天先这样", "先不聊", "今天先不聊", "今天不聊", "我先不聊",
        ), "好呀，那今天就先聊到这，下次见。"),
    )

    private val BACK_VERBS = listOf("回到", "切回", "切换回", "切换到", "切到", "换回", "换到", "返回", "回", "退回", "转到", "去")
    private val ASSISTANT_NAMES = listOf("助手", "助手模式", "语音助手", "助理", "助理模式", "普通模式", "指令模式", "命令模式", "卡片", "小卡片")

    private val EXIT_NEGATION = listOf("别", "不要", "不用", "不想", "不能", "不准", "不许", "先不要", "没")

    /** the call heard a request to end it ("挂了吧", "退出通话", "拜拜", "晚安", "切回助手") */
    fun parseExit(text: String): Exit? {
        val n = normalize(text)
        if (n.isEmpty() || n.length > 16) return null
        farewellOf(light(text))?.let { return it }
        val t = core(text)
        if (t.isEmpty()) return null
        exitOf(t)?.let { return it }
        // two in a row: "拜拜挂了" "好的再见挂了吧" "晚安拜拜"
        for (i in 1 until t.length) {
            val a = exitOf(core(t.substring(0, i))) ?: farewellOf(light(t.substring(0, i))) ?: continue
            val b = exitOf(core(t.substring(i))) ?: farewellOf(light(t.substring(i))) ?: continue
            return if (b.toAssistant) b else a
        }
        return null
    }

    private fun farewellOf(t: String): Exit? {
        if (t.isEmpty()) return null
        for (f in FAREWELLS) if (soundsLikeAny(t, f.phrases)) return Exit(f.reply)
        return null
    }

    private fun exitOf(t: String): Exit? {
        if (t.isEmpty()) return null
        // farewells first: "先不聊" has a 不 but is a goodbye
        farewellOf(t)?.let { return it }
        if (EXIT_NEGATION.any { t.contains(it) }) return null
        if (verbThenName(t, BACK_VERBS, ASSISTANT_NAMES)) return Exit("好，切回助手。", toAssistant = true)
        val s = t.removePrefix("把").removePrefix("将")
        if (soundsLikeAny(s, HANG_UP) ||
            verbThenName(s, EXIT_VERBS, EXIT_NAMES) ||
            nameThenVerb(s, EXIT_NAMES, EXIT_AFTER)
        ) return Exit("好的，那噜噜先挂啦，拜拜。")
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // matching by sound

    /** filler between the verb and the name: "切换到一下通话模式" "进入到通话模式" */
    private val LINKS = listOf("", "到", "成", "为", "一下", "下", "个", "一个", "这个", "那个")

    /** [t] = one of [verbs] + one of [names], compared syllable by syllable */
    private fun verbThenName(t: String, verbs: List<String>, names: List<String>): Boolean {
        val ts = syllables(t)
        if (ts.isEmpty()) return false
        for (v in verbs) {
            val vs = syllables(v)
            if (!startsWith(ts, vs)) continue
            val rest = ts.subList(vs.size, ts.size)
            for (l in LINKS) {
                val ls = syllables(l)
                if (!startsWith(rest, ls)) continue
                val name = rest.subList(ls.size, rest.size)
                if (name.isNotEmpty() && names.any { sameSound(name, syllables(it)) }) return true
            }
        }
        return false
    }

    /** [t] = one of [names] + one of [verbs]: "通话结束" "通话模式打开" */
    private fun nameThenVerb(t: String, names: List<String>, verbs: List<String>): Boolean {
        val ts = syllables(t)
        for (v in verbs) {
            val vs = syllables(v)
            if (vs.isEmpty() || !endsWith(ts, vs)) continue
            val name = ts.subList(0, ts.size - vs.size)
            if (name.isNotEmpty() && names.any { sameSound(name, syllables(it)) }) return true
        }
        return false
    }

    private fun soundsLikeAny(t: String, phrases: List<String>): Boolean {
        val ts = syllables(t)
        if (ts.isEmpty()) return false
        return phrases.any { t == it || sameSound(ts, syllables(it)) }
    }

    /**
     * Same syllables; long names (five or more) may have one syllable wrong or missing
     * ("进入通话模" "语音通话模试").
     */
    private fun sameSound(a: List<String>, b: List<String>): Boolean {
        if (a == b) return true
        if (minOf(a.size, b.size) < 5 || kotlin.math.abs(a.size - b.size) > 1) return false
        return editDistance(a, b) <= 1
    }

    private fun startsWith(a: List<String>, prefix: List<String>) =
        prefix.size <= a.size && a.subList(0, prefix.size) == prefix

    private fun endsWith(a: List<String>, suffix: List<String>) =
        suffix.size <= a.size && a.subList(a.size - suffix.size, a.size) == suffix

    private fun editDistance(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.size]
    }
}
