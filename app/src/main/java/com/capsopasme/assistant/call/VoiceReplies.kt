package com.capsopasme.assistant.call

/**
 * Short spoken answers understood on the phone, without the model: yes / no to a confirmation,
 * "挂了吧" / "再见" to end the call, filler sounds to ignore, and the phrases that start a call
 * from the assistant sheet.
 */
object VoiceReplies {

    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）《》…～·]+")
    private val POLITE_PREFIX = Regex("^(那就|那|好的|好|嗯|行|请|帮我|麻烦|你|我要|我想|可以)+")
    private val POLITE_SUFFIX = Regex("(吧|啊|呀|哈|啦|了|呗|喽|咯|哦|嘛)+$")

    fun normalize(text: String): String = PUNCT.replace(text, "").lowercase()

    /** "嗯" "啊" "呃…": noise or hesitation, not something to answer */
    fun isFiller(text: String): Boolean {
        val t = normalize(text)
        return t.isEmpty() || t.all { it in FILLER_CHARS }
    }

    // ---------------------------------------------------------------------------------------------

    private val HANG_UP = setOf(
        // "挂了吧" loses its 了吧 in [strip]
        "挂", "挂断", "挂了", "挂了吧", "挂掉", "挂吧", "挂电话", "挂断电话", "挂掉电话", "先挂了", "我先挂了",
        "结束通话", "退出通话", "关闭通话", "结束对话", "退出对话", "结束聊天", "退出通话模式", "关闭通话模式",
        "再见", "拜拜", "拜", "byebye", "bye", "再见了", "回见", "先这样再见", "就这样再见", "好了再见",
    )

    /** the whole utterance is a request to end the call */
    fun isHangUp(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.length > 10) return false
        if (t in HANG_UP) return true
        val core = strip(t)
        return core.isNotEmpty() && core in HANG_UP
    }

    // ---------------------------------------------------------------------------------------------

    /** answers that contain a "no" character but mean yes */
    private val YES_DESPITE_NEGATION = listOf("没问题", "没错", "不错", "可不是", "那还用说")
    private val NO_WORDS = listOf(
        "不", "别", "取消", "算了", "停", "否", "没", "错", "等等", "等一下", "先等", "暂时", "放弃", "拒绝", "no", "cancel",
    )
    private val YES_WORDS = listOf(
        // no bare "嗯": noise sometimes comes out as 嗯, and this can place a phone call
        "是", "对", "好", "可以", "行", "确定", "确认", "要", "没问题", "拨", "打", "关", "开", "继续", "同意",
        "去吧", "当然", "ok", "okay", "yes", "yeah", "sure", "冲", "来吧", "执行",
    )

    /** @return true yes, false no, null not understood */
    fun parseConfirm(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty() || t.length > 16) return null
        if (YES_DESPITE_NEGATION.any { t.contains(it) }) return true
        if (NO_WORDS.any { t.contains(it) }) return false
        if (YES_WORDS.any { t.contains(it) }) return true
        return null
    }

    // ---------------------------------------------------------------------------------------------

    private val START_CALL = setOf(
        "通话模式", "语音通话", "进入通话模式", "打开通话模式", "开启通话模式", "切换到通话模式", "切换通话模式",
        "开始通话", "进入通话", "电话模式", "进入电话模式", "打开电话模式", "聊天模式", "进入聊天模式", "打开聊天模式",
        "陪我聊天", "陪我聊聊", "陪我聊聊天", "跟我聊聊", "跟我聊天", "我们聊聊", "我们聊聊天", "聊聊天", "打电话给你",
        "给你打电话", "和你通话", "跟你通话",
    )

    /** the sheet heard a request to switch to the voice call ("进入通话模式", "陪我聊聊") */
    fun isStartCall(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.length > 12) return false
        if (t in START_CALL) return true
        val core = strip(t)
        return core.isNotEmpty() && core in START_CALL
    }

    private fun strip(t: String): String {
        var s = t
        repeat(2) { s = s.replace(POLITE_PREFIX, "").replace(POLITE_SUFFIX, "") }
        return s
    }

    private const val FILLER_CHARS = "嗯啊呃额哦噢喔唔哼诶欸哎嘿呀嗨恩呐么"
}
