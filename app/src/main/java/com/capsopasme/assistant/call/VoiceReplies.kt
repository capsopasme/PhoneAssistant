package com.capsopasme.assistant.call

/**
 * Short spoken answers understood on the phone, without the model: yes / no to a confirmation
 * and filler sounds to ignore. Entering / leaving the call: [CallCommands].
 */
object VoiceReplies {

    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）《》…～·]+")

    fun normalize(text: String): String = PUNCT.replace(text, "").lowercase()

    /** "嗯" "啊" "呃…": noise or hesitation, not something to answer */
    fun isFiller(text: String): Boolean {
        val t = normalize(text)
        return t.isEmpty() || t.all { it in FILLER_CHARS }
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

    private const val FILLER_CHARS = "嗯啊呃额哦噢喔唔哼诶欸哎嘿呀嗨恩呐么"
}
