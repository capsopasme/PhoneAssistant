package com.capsopasme.assistant.agent

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.KeyEvent
import com.capsopasme.assistant.Prefs
import com.capsopasme.assistant.memory.MemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The assistant's tools: system intents (tier 1) and root-backed system switches (tier 2).
 *
 * Root commands are built here from validated enums and numbers only, the model never supplies
 * command text. Anything that leaves this app (an app launch, a call screen, the calendar editor)
 * is returned as a [Host.launch] request and performed when the round ends.
 */
class Tools(private val ctx: Context, private val host: Host) {

    interface Host {
        /** Ask the user (blocks the worker thread until answered); false on cancel */
        fun confirm(question: String): Boolean

        /** Queue an activity to start once this round's tools have run; the session then ends */
        fun launch(intent: Intent)

        /**
         * Start an activity right now (for intents with no UI of their own, alarm / timer).
         * @return false if it couldn't be started (screen off in a call and no root)
         */
        fun startNow(intent: Intent): Boolean

        /** End the voice call once this answer has been spoken; false outside a call */
        fun endCall(): Boolean = false
    }

    private val prefs = Prefs(ctx)
    private val audio get() = ctx.getSystemService(AudioManager::class.java)

    fun execute(name: String, argsJson: String): String {
        val a = try {
            JSONObject(argsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            return err("参数不是合法 JSON")
        }
        return try {
            when (name) {
                "set_alarm" -> setAlarm(a)
                "set_timer" -> setTimer(a)
                "show_alarms" -> launchOrError(Intent(AlarmClock.ACTION_SHOW_ALARMS), "闹钟列表")
                "add_calendar_event" -> addCalendarEvent(a)
                "find_contact" -> findContact(a)
                "call_phone" -> callPhone(a)
                "compose_sms" -> composeSms(a)
                "navigate" -> navigate(a)
                "open_app" -> openApp(a)
                "web_search" -> webSearch(a)
                "search_web" -> searchWeb(a)
                "read_webpage" -> readWebpage(a)
                "get_weather" -> getWeather(a)
                "play_music" -> playMusic(a)
                "media_control" -> mediaControl(a)
                "set_volume" -> setVolume(a)
                "set_ringer_mode" -> setRingerMode(a)
                "flashlight" -> flashlight(a)
                "set_brightness" -> setBrightness(a)
                "toggle_setting" -> toggleSetting(a)
                "screen_off" -> rootAction("input keyevent 223", "已锁屏")
                "get_device_status" -> deviceStatus()
                "hang_up" -> if (host.endCall()) ok("通话会在道别说完后结束") else err("现在不在通话中")
                "remember" -> remember(a)
                "forget_memory" -> forgetMemory(a)
                "recall_memory" -> recallMemory(a)
                else -> err("没有这个工具：$name")
            }
        } catch (e: SecurityException) {
            err("缺少权限：${e.message}")
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    // --------------------------------------------------------------------------------------------
    // tier 1: intents & framework APIs

    private fun setAlarm(a: JSONObject): String {
        val hour = a.optInt("hour", -1)
        val minute = a.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return err("时间不合法")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        a.optJSONArray("days")?.let { arr ->
            // 1 = Monday ... 7 = Sunday  ->  java.util.Calendar (SUNDAY = 1 ... SATURDAY = 7)
            val days = ArrayList<Int>()
            for (i in 0 until arr.length()) {
                val d = arr.optInt(i)
                if (d in 1..7) days.add(if (d == 7) 1 else d + 1)
            }
            if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, days)
        }
        return startNow(intent, "已设定 %02d:%02d 的闹钟".format(hour, minute))
    }

    private fun setTimer(a: JSONObject): String {
        val seconds = a.optInt("seconds", 0)
        if (seconds !in 1..86_400) return err("时长需在 1 秒到 24 小时之间")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return startNow(intent, "已开始${formatDuration(seconds)}倒计时")
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val sec = seconds % 60
        return buildString {
            if (h > 0) append(" $h 小时")
            if (m > 0) append(" $m 分钟")
            if (sec > 0 || isEmpty()) append(" $sec 秒")
            append(' ')
        }
    }

    private fun addCalendarEvent(a: JSONObject): String {
        val title = a.optString("title").ifBlank { return err("缺少标题") }
        val allDay = a.optBoolean("all_day", false)
        val zone = ZoneId.systemDefault()
        val begin: Long
        val end: Long
        if (allDay) {
            val day = LocalDate.parse(a.optString("start").take(10))
            begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
            end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            val start = parseDateTime(a.optString("start")) ?: return err("开始时间格式应为 yyyy-MM-dd HH:mm")
            begin = start.atZone(zone).toInstant().toEpochMilli()
            end = parseDateTime(a.optString("end"))?.atZone(zone)?.toInstant()?.toEpochMilli()
                ?: (begin + 3_600_000L)
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        a.optString("location").takeIf { it.isNotBlank() }?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        return launchOrError(intent, "日历（请在编辑页点保存）")
    }

    private class Contact(val name: String, val number: String)

    private fun queryContacts(name: String): List<Contact> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("通讯录（请在助手设置里授权）")
        }
        val out = LinkedHashMap<String, Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { c ->
            while (c.moveToNext() && out.size < 10) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val key = num.filter { it.isDigit() || it == '+' }
                if (key.isNotEmpty()) out.putIfAbsent(key, Contact(n, num))
            }
        }
        // exact name matches first
        return out.values.sortedBy { if (it.name == name) 0 else 1 }
    }

    private fun findContact(a: JSONObject): String {
        val name = a.optString("name").ifBlank { return err("缺少姓名") }
        val list = queryContacts(name)
        if (list.isEmpty()) return err("通讯录里没有找到“$name”")
        return ok(JSONArray().apply {
            list.forEach { put(JSONObject().put("name", it.name).put("number", it.number)) }
        })
    }

    /** @return (contact, null) or (null, error json for the model); a bare number has an empty name */
    private fun resolveNumber(target: String): Pair<Contact?, String?> {
        val t = target.trim()
        if (t.isEmpty()) return null to err("缺少联系人或号码")
        val looksLikeNumber = t.all { it.isDigit() || it in "+-() " }
        if (looksLikeNumber) {
            val digits = t.filter { it.isDigit() || it == '+' }
            return if (digits.length >= 3) Contact("", digits) to null else null to err("号码不完整")
        }
        val list = queryContacts(t)
        val exact = list.filter { it.name == t }
        return when {
            list.isEmpty() -> null to err("通讯录里没有找到“$t”")
            list.size == 1 -> list[0] to null
            exact.size == 1 -> exact[0] to null
            else -> null to err(
                "匹配到多个号码，请让用户选择：" + list.joinToString("；") { "${it.name} ${it.number}" }
            )
        }
    }

    private fun callPhone(a: JSONObject): String {
        val (contact, error) = resolveNumber(a.optString("target"))
        if (contact == null) return error!!
        val number = contact.number
        // in a voice call this is read aloud: the name is what the user recognises
        val who = if (contact.name.isNotEmpty()) "${contact.name}（$number）" else number
        if (!host.confirm("拨打 $who ？")) return err("用户取消了拨号")
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return launchOrError(intent, if (canCall) "正在拨打 $number" else "拨号盘（$number）")
    }

    private fun composeSms(a: JSONObject): String {
        val (contact, error) = resolveNumber(a.optString("target"))
        if (contact == null) return error!!
        val number = contact.number
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", a.optString("text"))
        return launchOrError(intent, "短信编辑页（$number，需要用户点发送）")
    }

    private fun navigate(a: JSONObject): String {
        val dest = a.optString("destination").ifBlank { return err("缺少目的地") }
        val mode = a.optString("mode", "driving")
        val enc = URLEncoder.encode(dest, "UTF-8")
        val pm = ctx.packageManager
        val intent = when {
            // the user's default map: geo: with a query opens CoMaps' search for it
            isInstalled(LocalCommands.DEFAULT_MAP) ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).setPackage(LocalCommands.DEFAULT_MAP)
            isInstalled("com.autonavi.minimap") -> {
                val t = when (mode) { "transit" -> 1; "walking" -> 2; "riding" -> 3; else -> 0 }
                Intent(Intent.ACTION_VIEW, Uri.parse("amapuri://route/plan/?sourceApplication=assistant&dname=$enc&dev=0&t=$t"))
                    .setPackage("com.autonavi.minimap")
            }
            isInstalled("com.baidu.BaiduMap") -> {
                val m = when (mode) { "transit", "walking", "riding" -> mode; else -> "driving" }
                Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/direction?destination=$enc&mode=$m&src=andr.capsopasme.assistant"))
                    .setPackage("com.baidu.BaiduMap")
            }
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
        }
        if (intent.resolveActivity(pm) == null) return err("没有可用的地图应用")
        return launchOrError(intent, if (intent.`package` == LocalCommands.DEFAULT_MAP) "CoMaps 搜索“$dest”" else "导航到 $dest")
    }

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun openApp(a: JSONObject): String {
        val name = a.optString("name").trim().ifEmpty { return err("缺少应用名") }
        LocalCommands.defaultApp(ctx, name)?.let { return launchOrError(LocalCommands.launchIntent(it), it.label) }
        val ranked = LocalCommands.rankApps(ctx, name)
        val best = ranked.firstOrNull()?.takeIf { it.score >= 0.6 }
            ?: return err("没有找到名为“$name”的应用")
        val close = ranked.filter { it.score >= 0.6 && it.score >= best.score - 0.05 }
        if (close.size > 1) {
            return err("找到多个相近的应用，请让用户选择：" + close.take(5).joinToString("、") { it.label })
        }
        return launchOrError(LocalCommands.launchIntent(best), best.label)
    }


    private fun webSearch(a: JSONObject): String {
        val q = a.optString("query").ifBlank { return err("缺少搜索词") }
        val url = Intent(Intent.ACTION_VIEW, Uri.parse("https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")))
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        val intent = when {
            isInstalled(LocalCommands.DEFAULT_BROWSER) -> url.setPackage(LocalCommands.DEFAULT_BROWSER)
            search.resolveActivity(ctx.packageManager) != null -> search
            else -> url
        }
        return launchOrError(intent, "搜索“$q”")
    }

    /** the call companion's search: results come back to the model, nothing opens */
    private fun searchWeb(a: JSONObject): String {
        val q = a.optString("query").trim().ifEmpty { return err("缺少搜索词") }
        val results = WebSearch.search(q)
        if (results.isEmpty()) return err("没有搜到“$q”的结果，可以换个说法再搜")
        val arr = JSONArray()
        results.forEach { arr.put(JSONObject().put("title", it.title).put("snippet", it.snippet).put("url", it.url)) }
        return ok(JSONObject().put("query", q).put("results", arr)
            .put("note", "用自己的话口语化转述要点，不要念网址；摘要不够时可以用 read_webpage 看最相关的一条"))
    }

    private fun readWebpage(a: JSONObject): String {
        val url = a.optString("url").trim().ifEmpty { return err("缺少网址") }
        val (title, text) = WebSearch.read(url)
        if (text.isBlank()) return err("这个网页没有读到正文（可能需要登录或是动态页面）")
        return ok(JSONObject().put("title", title).put("text", text))
    }

    private fun getWeather(a: JSONObject): String {
        val city = a.optString("city").ifBlank { prefs.defaultCity }
            .ifBlank { return err("用户没说城市，设置里也没有默认城市，请问用户在哪个城市") }
        val days = a.optInt("days", 1).coerceIn(1, 7)
        val geo = httpGetJson(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name=" +
                    URLEncoder.encode(city, "UTF-8")
        )
        val place = geo.optJSONArray("results")?.optJSONObject(0)
            ?: return err("找不到城市“$city”，可以换成拼音或英文再试")
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")
        val f = httpGetJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=$days" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
        )
        val out = JSONObject().put("place", listOf(place.optString("name"), place.optString("admin1"))
            .filter { it.isNotBlank() }.distinct().joinToString(" "))
        f.optJSONObject("current")?.let { c ->
            out.put("now", JSONObject()
                .putNum("temp_c", c.optDouble("temperature_2m"))
                .putNum("feels_like_c", c.optDouble("apparent_temperature"))
                .put("humidity_pct", c.optInt("relative_humidity_2m"))
                .putNum("wind_kmh", c.optDouble("wind_speed_10m"))
                .put("weather", wmo(c.optInt("weather_code"))))
        }
        f.optJSONObject("daily")?.let { d ->
            val dates = d.optJSONArray("time") ?: JSONArray()
            val arr = JSONArray()
            for (i in 0 until dates.length()) {
                arr.put(JSONObject()
                    .put("date", dates.optString(i))
                    .put("weather", wmo(d.optJSONArray("weather_code")?.optInt(i) ?: -1))
                    .putNum("max_c", d.optJSONArray("temperature_2m_max")?.optDouble(i))
                    .putNum("min_c", d.optJSONArray("temperature_2m_min")?.optDouble(i))
                    .put("rain_chance_pct", d.optJSONArray("precipitation_probability_max")?.optInt(i)))
            }
            out.put("daily", arr)
        }
        return ok(out)
    }

    /** missing values come back from optDouble as NaN, which org.json refuses to put: skip them */
    private fun JSONObject.putNum(key: String, v: Double?): JSONObject =
        if (v == null || v.isNaN() || v.isInfinite()) this else put(key, v)

    private fun wmo(code: Int) = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "未知"
    }

    private fun httpGetJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("天气服务 HTTP $code")
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    // --------------------------------------------------------------------------------------------
    // music: Gramophone (留声机), https://github.com/AkaneTan/Gramophone

    private class Song(val id: Long, val title: String, val artist: String, val album: String)

    private fun loadSongs(): List<Song>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val out = ArrayList<Song>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val artist = c.getString(2)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                out.add(Song(c.getLong(0), c.getString(1) ?: "", artist, c.getString(3) ?: ""))
            }
        }
        return out
    }

    /** best of [values] for the spoken [query], with its score */
    private fun bestOf(query: String, values: Collection<String>): Pair<String, Double>? =
        values.filter { it.isNotBlank() }.distinct()
            .map { it to FuzzyMatch.score(query, it) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MUSIC_MIN_SCORE }

    private fun playMusic(a: JSONObject): String {
        if (!isInstalled(GRAMOPHONE)) return err("没有安装留声机（Gramophone）")
        val title = a.optString("title").trim()
        val artist = a.optString("artist").trim()
        val album = a.optString("album").trim()
        val shuffle = a.optBoolean("shuffle", false)

        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) {
            return launchOrError(shuffleIntent(""), "留声机，随机播放全部歌曲")
        }

        val songs = loadSongs()
        if (songs == null) {
            // no access to the library: let Gramophone search the words as they are
            val q = title.ifEmpty { album.ifEmpty { artist } }
            val intent = if (shuffle) shuffleIntent(q) else searchIntent(q, null)
            return launchOrError(intent, "留声机，播放“$q”（未授予音乐权限，按原文搜索）")
        }
        if (songs.isEmpty()) return err("手机里没有找到音乐文件")

        // a specific song: the best title, nudged by the artist when one was said
        if (title.isNotEmpty() && !shuffle) {
            val best = songs.mapNotNull { s ->
                val t = FuzzyMatch.score(title, s.title)
                if (t < MUSIC_MIN_SCORE) return@mapNotNull null
                val r = if (artist.isEmpty()) 0.0 else FuzzyMatch.score(artist, s.artist)
                s to (t + 0.2 * r)
            }.maxByOrNull { it.second }?.first
                ?: return err("曲库里没有找到歌曲“$title”" + if (artist.isNotEmpty()) "（$artist）" else "")
            val desc = best.title + if (best.artist.isNotEmpty()) " - ${best.artist}" else ""
            return launchOrError(songIntent(best), "留声机，播放《$desc》")
        }

        // a group of songs: album, artist, or a title used as a shuffle filter
        val (kind, value) = when {
            album.isNotEmpty() -> "album" to (bestOf(album, songs.map { it.album })?.first
                ?: return err("曲库里没有找到专辑“$album”"))
            artist.isNotEmpty() -> "artist" to (bestOf(artist, songs.map { it.artist })?.first
                ?: return err("曲库里没有找到歌手“$artist”"))
            else -> "title" to (bestOf(title, songs.map { it.title })?.first
                ?: return err("曲库里没有找到“$title”"))
        }
        val what = when (kind) {
            "album" -> "专辑《$value》"
            "artist" -> "$value 的歌"
            else -> "《$value》"
        }
        val intent = if (shuffle) shuffleIntent(value) else searchIntent(value, kind)
        return launchOrError(intent, "留声机，${if (shuffle) "随机" else ""}播放$what")
    }

    /** shuffle every song whose title / artist / album contains [filter] ("" = all songs) */
    private fun shuffleIntent(filter: String) = Intent("org.akanework.gramophone.action.SHUFFLE")
        .setPackage(GRAMOPHONE)
        .putExtra("item_name", filter)

    /** play exactly this song (Gramophone looks the MediaStore id up in its library) */
    private fun songIntent(song: Song): Intent {
        val direct = Intent(Intent.ACTION_MAIN)
            .setClassName(GRAMOPHONE, "org.akanework.gramophone.ui.MainActivity")
            .putExtra("AutoStartId", song.id.toString())
        return if (direct.resolveActivity(ctx.packageManager) != null) direct
        else searchIntent(song.title, "title")
    }

    /** standard "play from search"; Gramophone plays every song containing [value] */
    private fun searchIntent(value: String, kind: String?): Intent {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(GRAMOPHONE)
            .putExtra(SearchManager.QUERY, value)
        when (kind) {
            "title" -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_TITLE, value)
            "artist" -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, value)
            "album" -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ALBUM, value)
        }
        return i
    }

    private fun mediaControl(a: JSONObject): String {
        val key = when (a.optString("action")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ok("已发送媒体按键")
    }

    private fun setVolume(a: JSONObject): String {
        val stream = when (a.optString("stream", "media")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val am = audio
        val max = am.getStreamMaxVolume(stream)
        val min = am.getStreamMinVolume(stream)
        val current = am.getStreamVolume(stream)
        val target = when (a.optString("adjust")) {
            "up" -> current + (max / 10).coerceAtLeast(1)
            "down" -> current - (max / 10).coerceAtLeast(1)
            "mute" -> min
            "max" -> max
            else -> {
                if (!a.has("percent")) return err("需要 percent 或 adjust")
                (a.optDouble("percent").coerceIn(0.0, 100.0) / 100 * max).roundToInt()
            }
        }.coerceIn(min, max)
        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return ok("音量已设为 ${target * 100 / max.coerceAtLeast(1)}%")
    }

    private fun setRingerMode(a: JSONObject): String {
        val mode = when (a.optString("mode")) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return err("mode 只能是 normal / vibrate / silent")
        }
        // entering silent, and also leaving it (it is a DND state), needs DND policy access:
        // grant it to ourselves once, with root
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            RootShell.run("cmd notification allow_dnd ${ctx.packageName}")
        }
        val am = audio
        try {
            am.ringerMode = mode
        } catch (e: SecurityException) {
            return err("没有勿扰权限，无法切换铃声模式")
        }
        return ok("铃声模式已切换")
    }

    private fun flashlight(a: JSONObject): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return err("没有找到闪光灯")
        val on = a.optBoolean("on", true)
        cm.setTorchMode(id, on)
        return ok(if (on) "手电筒已打开" else "手电筒已关闭")
    }

    private fun deviceStatus(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val am = audio
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val stat = StatFs(Environment.getDataDirectory().path)
        val o = JSONObject()
            .put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("wifi_on", ctx.getSystemService(WifiManager::class.java).isWifiEnabled)
            .put("ringer", when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            .put("dnd_on", nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
            .put("media_volume_pct", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 /
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
            .put("storage_free_gb", "%.1f".format(stat.availableBytes / 1e9))
        RootShell.run("settings get global bluetooth_on").takeIf { it.ok }?.let {
            o.put("bluetooth_on", it.output.trim() == "1")
        }
        return ok(o)
    }

    // --------------------------------------------------------------------------------------------
    // tier 2: root

    /**
     * Brightness percent as the system slider shows it (gamma space) to the 0-255 setting,
     * same HLG curve as AOSP BrightnessUtils.convertGammaToLinearFloat.
     */
    private fun sliderPercentToSetting(percent: Int): Int {
        val v = percent.coerceIn(0, 100) / 100f
        val r = 0.5f
        val hlg = if (v <= r) (v / r) * (v / r) else exp((v - 0.55991073f) / 0.17883277f) + 0.28466892f
        return (1 + (hlg.coerceIn(0f, 12f) / 12f) * 254).roundToInt().coerceIn(1, 255)
    }

    private fun setBrightness(a: JSONObject): String {
        if (a.optBoolean("auto", false)) {
            return rootAction("settings put system screen_brightness_mode 1", "已开启自动亮度")
        }
        if (!a.has("percent")) return err("需要 percent 或 auto")
        val p = a.optInt("percent").coerceIn(0, 100)
        val value = sliderPercentToSetting(p)
        return rootAction(
            "settings put system screen_brightness_mode 0 && settings put system screen_brightness $value",
            "亮度已调到 $p%"
        )
    }

    private fun toggleSetting(a: JSONObject): String {
        val setting = a.optString("setting")
        if (!a.has("enabled")) return err("缺少 enabled")
        return toggle(setting, a.optBoolean("enabled"), confirmRisky = true)
    }

    /**
     * A switch the user asked for in so many words, parsed on the phone ([LocalCommands]):
     * no "this cuts the network" question, nothing after it needs the network.
     */
    fun toggleLocal(setting: String, on: Boolean): String = try {
        toggle(setting, on, confirmRisky = false)
    } catch (e: Exception) {
        err(e.message ?: e.javaClass.simpleName)
    }

    private fun toggle(setting: String, on: Boolean, confirmRisky: Boolean): String {
        val en = if (on) "enable" else "disable"
        val bit = if (on) 1 else 0
        val (command, label) = when (setting) {
            "wifi" -> "svc wifi $en" to "WiFi"
            "bluetooth" -> "svc bluetooth $en || cmd bluetooth_manager $en" to "蓝牙"
            "mobile_data" -> "svc data $en" to "移动数据"
            "airplane_mode" -> "cmd connectivity airplane-mode $en" to "飞行模式"
            "dnd" -> "cmd notification set_dnd ${if (on) "on" else "off"}" to "勿扰模式"
            "location" -> "cmd location set-location-enabled $on" to "定位"
            "nfc" -> "svc nfc $en" to "NFC"
            "auto_rotate" -> "settings put system accelerometer_rotation $bit" to "自动旋转"
            "dark_mode" -> "cmd uimode night ${if (on) "yes" else "no"}" to "深色模式"
            // ColorDisplayService watches these two settings, same as the Quick Settings tiles
            "night_light" -> "settings put secure night_display_activated $bit" to "护眼模式"
            "extra_dim" -> "settings put secure reduce_bright_colors_activated $bit" to "极暗模式"
            "battery_saver" -> "cmd power set-mode $bit || settings put global low_power $bit" to "省电模式"
            // debugging lives under developer options: switching it on shows them too,
            // switching developer options off turns both kinds of debugging off as well
            "developer_options" -> (if (on) "settings put global development_settings_enabled 1"
            else "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0; " +
                    "settings put global development_settings_enabled 0") to "开发者选项"
            "usb_debugging" -> (if (on) "settings put global development_settings_enabled 1 && settings put global adb_enabled 1"
            else "settings put global adb_enabled 0") to "USB 调试"
            "wireless_debugging" -> if (on) return enableWirelessDebugging()
            else "settings put global adb_wifi_enabled 0" to "无线调试"
            else -> return err("不支持的开关：$setting")
        }
        val risky = (setting == "airplane_mode" && on) || (setting == "mobile_data" && !on) || (setting == "wifi" && !on)
        if (confirmRisky && risky && !host.confirm("${if (on) "打开" else "关闭"}$label？会断开网络，之后的回复可能失败")) {
            return err("用户取消了")
        }
        val done = "$label 已${if (on) "打开" else "关闭"}"
        val query = stateQuery(setting) ?: return rootAction(command, done)
        // one su call: read the current state, switch only if it differs
        val r = RootShell.run(
            "s=\$($query 2>/dev/null); case \"\$s\" in *yes*|1|2) c=1;; *no*|0) c=0;; *) c=x;; esac; " +
                    "if [ \"\$c\" = $bit ]; then echo $ALREADY; else $command; fi"
        )
        return when {
            r.ok && r.output.contains(ALREADY) -> ok("$label 本来就是${if (on) "开" else "关"}着的")
            r.ok -> ok(done)
            else -> err("执行失败（${r.code}）：${r.output.take(200).ifEmpty { "可能没有授予 root" }}")
        }
    }

    /** shell command printing a switch's current state (1/0, or "Night mode: yes/no") */
    private fun stateQuery(setting: String): String? = when (setting) {
        // 2 = on while in airplane mode
        "bluetooth" -> "settings get global bluetooth_on"
        "airplane_mode" -> "settings get global airplane_mode_on"
        "dark_mode" -> "cmd uimode night"
        "night_light" -> "settings get secure night_display_activated"
        "extra_dim" -> "settings get secure reduce_bright_colors_activated"
        else -> null
    }

    /**
     * Wireless debugging only stays on while connected to WiFi (the system switches it back off
     * otherwise) and gets a new random port each time: wait for the port and report it.
     */
    private fun enableWirelessDebugging(): String {
        val r = RootShell.run(
            "settings put global development_settings_enabled 1 && settings put global adb_wifi_enabled 1 && " +
                    "for i in 1 2 3 4 5 6 7 8; do sleep 0.5; p=\$(getprop service.adb.tls.port); " +
                    "[ -n \"\$p\" ] && [ \"\$p\" != 0 ] && break; done; " +
                    "echo \"state=\$(settings get global adb_wifi_enabled) port=\$p\"; ip -4 -o addr show wlan0",
            12_000
        )
        if (!r.ok && !r.output.contains("state=")) {
            return err("执行失败（${r.code}）：${r.output.take(200).ifEmpty { "可能没有授予 root" }}")
        }
        val state = Regex("state=(\\S*)").find(r.output)?.groupValues?.get(1)
        val port = Regex("port=(\\d+)").find(r.output)?.groupValues?.get(1)?.takeIf { it != "0" }
        val ip = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(r.output)?.groupValues?.get(1)
        if (state != "1") return err("无线调试没能保持打开：需要先连上 WiFi")
        return ok(if (port != null && ip != null) "无线调试已打开，地址 $ip:$port" else "无线调试已打开")
    }

    private fun rootAction(command: String, success: String): String {
        val r = RootShell.run(command)
        return if (r.ok) ok(success) else err("执行失败（${r.code}）：${r.output.take(200).ifEmpty { "可能没有授予 root" }}")
    }

    // --------------------------------------------------------------------------------------------

    /** Start right away, for intents that don't leave this screen (alarm / timer with SKIP_UI) */
    private fun startNow(intent: Intent, success: String): String {
        if (intent.resolveActivity(ctx.packageManager) == null) return err("没有应用能处理这个操作")
        if (!host.startNow(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return err("没能在后台打开时钟应用（熄屏时需要 root 权限），请亮屏后再试")
        }
        return ok(success)
    }

    private fun launchOrError(intent: Intent, what: String): String {
        if (intent.resolveActivity(ctx.packageManager) == null) return err("没有应用能打开$what")
        host.launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ok("已打开$what")
    }

    // --------------------------------------------------------------------------------------------
    // 噜噜's memory (voice call)

    private fun remember(a: JSONObject): String {
        if (!prefs.memoryEnabled) return err("记忆功能已在设置里关闭")
        val text = a.optString("text").trim()
        if (text.isEmpty()) return err("没有要记的内容")
        MemoryStore.addFact(ctx, text)
        return ok("记住了")
    }

    private fun forgetMemory(a: JSONObject): String {
        if (!prefs.memoryEnabled) return err("记忆功能已在设置里关闭")
        val ids = HashSet<Int>()
        a.optJSONArray("ids")?.let { arr -> for (i in 0 until arr.length()) arr.optInt(i, -1).takeIf { it >= 0 }?.let(ids::add) }
        val about = a.optString("about").trim()
        if (ids.isEmpty() && about.isNotEmpty()) MemoryStore.findFacts(ctx, about).forEach { ids.add(it.id) }
        if (ids.isEmpty()) return err("没找到相关的记忆")
        val gone = MemoryStore.removeFacts(ctx, ids)
        if (gone.isEmpty()) return err("没找到相关的记忆")
        return ok(JSONObject().put("forgotten", JSONArray(gone)).put("note", "已经删掉了，简短告诉对方忘掉了就好"))
    }

    private fun recallMemory(a: JSONObject): String {
        if (!prefs.memoryEnabled) return err("记忆功能已在设置里关闭")
        val found = MemoryStore.search(ctx, a.optString("query"), ZoneId.systemDefault())
        if (found.isEmpty()) return ok("没有找到相关的记忆")
        return ok(JSONArray(found))
    }

    private fun ok(result: Any) = JSONObject().put("ok", true).put("result", result).toString()
    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun parseDateTime(s: String): LocalDateTime? = try {
        LocalDateTime.parse(s.trim().replace('T', ' ').take(16), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val GRAMOPHONE = "org.akanework.gramophone"
        private const val MUSIC_MIN_SCORE = 0.6
        private const val ALREADY = "__ALREADY__"

        /**
         * the tools of the assistant sheet, or of the call companion (chat only, see
         * [companionSchemas]); [memory]: 噜噜's memory tools too
         */
        fun schemasFor(companion: Boolean, memory: Boolean = false): JSONArray = when {
            !companion -> schemas
            memory -> companionMemorySchemas
            else -> companionSchemas
        }

        /** what the call companion may do: look things up, remember, end the call; never operate the phone */
        val COMPANION_TOOLS = setOf("search_web", "read_webpage", "get_weather", "hang_up", "remember", "forget_memory", "recall_memory")

        private val companionMemorySchemas: JSONArray by lazy {
            JSONArray(companionSchemas.toString()).apply {
                fn("remember", "马上记住关于对方的一件事。只在对方明确让你“记住”时用；平时聊到的事通话结束后会自动整理，不用调用",
                    props("text" to str("要记的事，一句话，以“用户”开头，如“用户的生日是3月5日”")), "text")
                fn("forget_memory", "删掉记忆。对方让你“忘掉”“别记着”某件事时用；优先按编号删",
                    props(
                        "ids" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer"))
                            .put("description", "要删的记忆编号（系统提示里方括号中的数字）"),
                        "about" to str("没有对应编号时，要忘掉的事的关键词"),
                    ))
                fn("recall_memory", "翻以前的通话记忆：对方提到很久以前聊过的事、而系统提示里没写到时用",
                    props("query" to str("要找的事的关键词")), "query")
            }
        }

        private val companionSchemas: JSONArray by lazy {
            JSONArray().apply {
                fn("search_web", "上网搜索，返回搜索结果的标题和摘要。需要最新信息（新闻、比分、行情、近期的事）或拿不准的事实时用",
                    props("query" to str("搜索词，简洁明确，必要时带上时间或地点")), "query")
                fn("read_webpage", "打开一个网页读取正文（search_web 的摘要不够时，读最相关的一条结果）",
                    props("url" to str("网址，来自 search_web 的结果")), "url")
                fn("get_weather", "查天气（实时和未来几天）",
                    props("city" to str("城市名，可选，不填用默认城市"), "days" to int("预报天数 1-7，默认 1")))
                fn("hang_up", "结束这次语音通话。用户明确想结束（再见、晚安、先聊到这、挂了吧、退下吧、你走吧）时调用。只要你这条回复是在道别，就必须同时调用它，否则电话不会挂断；道别的话和这个调用写在同一条回复里（调用之后不会再让你说话）", props())
            }
        }

        /** Tool schemas, OpenAI function-calling format */
        val schemas: JSONArray by lazy {
            JSONArray().apply {
                fn("set_alarm", "设定闹钟（直接生效，不打开界面）",
                    props(
                        "hour" to int("小时 0-23"),
                        "minute" to int("分钟 0-59"),
                        "label" to str("闹钟备注，可选"),
                        "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer"))
                            .put("description", "重复的星期，1=周一 … 7=周日；一次性闹钟不填"),
                    ), "hour", "minute")
                fn("set_timer", "开始倒计时", props("seconds" to int("总秒数"), "label" to str("备注，可选")), "seconds")
                fn("show_alarms", "打开闹钟列表（查看、关闭或删除闹钟时用）", props())
                fn("add_calendar_event", "新建日程，会打开日历编辑页让用户确认保存",
                    props(
                        "title" to str("标题"),
                        "start" to str("开始时间 yyyy-MM-dd HH:mm（全天事件只需 yyyy-MM-dd）"),
                        "end" to str("结束时间 yyyy-MM-dd HH:mm，可选，默认 1 小时"),
                        "all_day" to bool("是否全天"),
                        "location" to str("地点，可选"),
                    ), "title", "start")
                fn("find_contact", "按姓名查通讯录里的号码", props("name" to str("姓名或其中一部分")), "name")
                fn("call_phone", "打电话（会先让用户确认）", props("target" to str("联系人姓名或电话号码")), "target")
                fn("compose_sms", "写短信：打开短信编辑页并填好内容，由用户点发送",
                    props("target" to str("联系人姓名或电话号码"), "text" to str("短信内容")), "target", "text")
                fn("navigate", "用地图导航到目的地",
                    props("destination" to str("目的地"), "mode" to enumOf("出行方式", "driving", "transit", "walking", "riding")),
                    "destination")
                fn("open_app", "打开手机上的应用。用户只说类别时（地图、计算器、浏览器、相机、相册、音乐、视频、录音机、阅读器、记事本等）直接把类别名作为 name，会打开用户设定的默认应用",
                    props("name" to str("应用名称或类别")), "name")
                fn("web_search", "在浏览器里搜索（用户明确要搜索、或需要最新网络信息时用）", props("query" to str("搜索词")), "query")
                fn("get_weather", "查天气（实时和未来几天）",
                    props("city" to str("城市名，可选，不填用默认城市"), "days" to int("预报天数 1-7，默认 1")))
                fn("play_music", "用留声机播放手机里的本地音乐。说了歌名就放那首歌；只说歌手或专辑就放该歌手/专辑的歌；" +
                        "什么都没指定（如“放点音乐”“随便来首歌”）就不填参数，随机播放全部歌曲。“继续播放/暂停”用 media_control。",
                    props(
                        "title" to str("歌名，可选"),
                        "artist" to str("歌手，可选"),
                        "album" to str("专辑，可选"),
                        "shuffle" to bool("随机播放（用户说“随机放某歌手的歌”时为 true），默认 false"),
                    ))
                fn("media_control", "控制正在播放的音乐/视频（继续、暂停、上一首、下一首）",
                    props("action" to enumOf("动作", "play_pause", "play", "pause", "next", "previous")), "action")
                fn("set_volume", "调节音量：给 percent 设到具体百分比，或用 adjust 相对调节",
                    props(
                        "stream" to enumOf("音量类型，默认 media", "media", "ring", "alarm", "notification", "call"),
                        "percent" to int("目标百分比 0-100"),
                        "adjust" to enumOf("相对调节", "up", "down", "mute", "max"),
                    ))
                fn("set_ringer_mode", "切换铃声模式", props("mode" to enumOf("模式", "normal", "vibrate", "silent")), "mode")
                fn("flashlight", "开关手电筒", props("on" to bool("true 打开，false 关闭")), "on")
                fn("set_brightness", "调节屏幕亮度：percent 为系统亮度条上的百分比，或 auto=true 开启自动亮度",
                    props("percent" to int("亮度百分比 0-100"), "auto" to bool("开启自动亮度")))
                fn("toggle_setting", "打开或关闭系统开关（night_light=护眼模式/夜间灯光，extra_dim=极暗模式，dark_mode=深色主题）",
                    props(
                        "setting" to enumOf("开关", "wifi", "bluetooth", "mobile_data", "airplane_mode", "dnd",
                            "location", "nfc", "auto_rotate", "dark_mode", "night_light", "extra_dim", "battery_saver",
                            "developer_options", "usb_debugging", "wireless_debugging"),
                        "enabled" to bool("true 打开，false 关闭"),
                    ), "setting", "enabled")
                fn("screen_off", "锁屏/关闭屏幕", props())
                fn("get_device_status", "查询电量、充电、WiFi、蓝牙、铃声模式、勿扰、媒体音量、剩余存储", props())
            }
        }

        private fun JSONArray.fn(name: String, description: String, properties: JSONObject, vararg required: String) {
            val function = JSONObject().put("name", name).put("description", description)
            // parameterless tools get no schema at all: Gemini rejects an object with no properties
            if (properties.length() > 0) {
                function.put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(required.toList())))
            }
            put(JSONObject().put("type", "function").put("function", function))
        }

        private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }
        private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
        private fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)
        private fun enumOf(d: String, vararg values: String) =
            JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(values.toList()))
    }
}
