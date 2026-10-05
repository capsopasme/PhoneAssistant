package com.capsopasme.assistant.agent

/** What the assistant shows while a tool runs: "正在" + label + "…" */
object ToolLabels {
    private val LABELS = mapOf(
        "set_alarm" to "设闹钟",
        "set_timer" to "设倒计时",
        "show_alarms" to "打开闹钟",
        "add_calendar_event" to "新建日程",
        "find_contact" to "查通讯录",
        "call_phone" to "拨号",
        "compose_sms" to "写短信",
        "navigate" to "打开导航",
        "open_app" to "打开应用",
        "web_search" to "搜索",
        "get_weather" to "查天气",
        "play_music" to "播放音乐",
        "media_control" to "控制播放",
        "set_volume" to "调音量",
        "set_ringer_mode" to "切换铃声",
        "flashlight" to "开关手电筒",
        "set_brightness" to "调亮度",
        "toggle_setting" to "切换开关",
        "screen_off" to "锁屏",
        "get_device_status" to "查询状态",
        "hang_up" to "挂断",
        "search_web" to "搜索",
        "read_webpage" to "看网页",
        "remember" to "记下来",
        "forget_memory" to "忘掉",
        "recall_memory" to "回想",
    )

    fun status(tool: String): String = LABELS[tool]?.let { "正在$it…" } ?: "正在执行…"
}
