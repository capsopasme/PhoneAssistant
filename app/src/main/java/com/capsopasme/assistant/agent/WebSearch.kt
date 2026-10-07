package com.capsopasme.assistant.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Web search for the call companion, done on the phone and handed back to the model as text
 * (the sheet's web_search opens the browser instead). No API key: the result pages of Bing
 * (cn.bing.com, reachable in mainland China) and, if that yields nothing, DuckDuckGo's HTML page.
 * Plus reading one page as plain text, for when the snippets aren't enough.
 *
 * Blocking, worker thread. Small and bounded: short timeouts, at most [MAX_BYTES] per page.
 */
object WebSearch {

    class Result(val title: String, val url: String, val snippet: String)

    /** @throws IOException when no search engine could be reached */
    fun search(query: String, max: Int = 6): List<Result> {
        val q = URLEncoder.encode(query, "UTF-8")
        var failure: Exception? = null
        try {
            val r = parseBing(get("https://cn.bing.com/search?q=$q&form=QBLH").second)
            if (r.isNotEmpty()) return r.take(max)
        } catch (e: Exception) {
            failure = e
        }
        try {
            val r = parseDuckDuckGo(get("https://html.duckduckgo.com/html/?q=$q").second)
            if (r.isNotEmpty()) return r.take(max)
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
        failure?.let { throw IOException("搜索服务连不上（${it.message ?: it.javaClass.simpleName}）") }
        return emptyList()
    }

    /** title and the readable text of a web page, at most [maxChars] characters */
    fun read(url: String, maxChars: Int = 3000): Pair<String, String> {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            throw IOException("网址不合法")
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) throw IOException("只能打开 http/https 网页")
        // a public web page, not something on the phone or the local network (every redirect too)
        val (contentType, body) = get(uri.toString(), publicOnly = true)
        return when {
            contentType.isEmpty() || contentType.contains("html") -> pageTitle(body) to clip(pageText(body), maxChars)
            contentType.startsWith("text/") -> "" to clip(body.replace(SPACES, " ").trim(), maxChars)
            else -> throw IOException("不是网页（${contentType.substringBefore(';')}）")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // parsing (internal for tests)

    private val BING_ITEM = Regex("<li[^>]*class=\"b_algo[^\"]*\"[^>]*>")
    private val BING_TITLE = Regex("<h2[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val BING_SNIPPET = listOf(
        Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL),
        Regex("<div class=\"b_caption[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL),
    )
    private val BING_ICON = Regex("<span class=\"algoSlug_icon\"[^>]*>.*?</span>", RegexOption.DOT_MATCHES_ALL)

    internal fun parseBing(html: String): List<Result> {
        val starts = BING_ITEM.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, start ->
            val end = starts.getOrNull(i + 1) ?: minOf(html.length, start + 20_000)
            val block = html.substring(start, end)
            val t = BING_TITLE.find(block) ?: return@mapIndexedNotNull null
            val title = clean(t.groupValues[2])
            val snippet = BING_SNIPPET.firstNotNullOfOrNull { r ->
                r.find(block)?.let { clean(BING_ICON.replace(it.groupValues[1], "")) }?.takeIf { it.length >= 8 }
            } ?: ""
            if (title.isEmpty()) null else Result(title, bingTarget(decodeEntities(t.groupValues[1])), clip(snippet, 220))
        }.distinctBy { it.url }
    }

    /** Bing sometimes links through its click tracker: ck/a?...&u=a1<base64url of the target> */
    private fun bingTarget(href: String): String {
        if (!href.contains("bing.com/ck/a")) return href
        val u = Regex("[?&]u=a1([^&]+)").find(href)?.groupValues?.get(1) ?: return href
        return try {
            String(java.util.Base64.getUrlDecoder().decode(u.trimEnd('=')), Charsets.UTF_8)
                .takeIf { it.startsWith("http") } ?: href
        } catch (_: Exception) {
            href
        }
    }

    private val DDG_BODY = Regex("class=\"[^\"]*\\bresult__body\\b[^\"]*\"")
    private val DDG_TITLE = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val DDG_TITLE_ALT = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val DDG_SNIPPET = Regex("class=\"result__snippet\"[^>]*>(.*?)</(a|div|td)>", RegexOption.DOT_MATCHES_ALL)

    internal fun parseDuckDuckGo(html: String): List<Result> {
        val starts = DDG_BODY.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { i, start ->
            val end = starts.getOrNull(i + 1) ?: html.length
            val block = html.substring(start, end)
            if (block.contains("result--ad")) return@mapIndexedNotNull null
            val t = DDG_TITLE.find(block) ?: DDG_TITLE_ALT.find(block) ?: return@mapIndexedNotNull null
            var url = decodeEntities(t.groupValues[1])
            // //duckduckgo.com/l/?uddg=<encoded target>&rut=…
            Regex("[?&]uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            val title = clean(t.groupValues[2])
            val snippet = DDG_SNIPPET.find(block)?.let { clean(it.groupValues[1]) } ?: ""
            if (title.isEmpty()) null else Result(title, url, clip(snippet, 220))
        }.distinctBy { it.url }
    }

    private val DROP_BLOCKS = Regex(
        "<(script|style|noscript|svg|head|header|footer|nav|aside|form|iframe|template)\\b[^>]*>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val MAIN = listOf(
        Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        Regex("<main\\b[^>]*>(.*?)</main>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
    )
    private val BREAKS = Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/section|/blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    internal fun pageTitle(html: String): String = TITLE.find(html)?.let { clean(it.groupValues[1]) } ?: ""

    internal fun pageText(html: String): String {
        var body = DROP_BLOCKS.replace(html, " ")
        // the article itself when the page marks it, without menus and link lists around it
        MAIN.firstNotNullOfOrNull { r -> r.find(body)?.groupValues?.get(1)?.takeIf { stripTags(it).trim().length > 200 } }
            ?.let { body = it }
        val text = decodeEntities(stripTags(BREAKS.replace(body, "\n")))
        return text.lines()
            .map { it.replace(SPACES, " ").trim() }
            // lone short lines are mostly menu items and buttons
            .filter { it.length >= 6 || it.any { ch -> ch in "。！？.!?" } }
            .joinToString("\n")
    }

    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("[\\s\\u00a0\\u3000]+")

    private fun stripTags(s: String) = TAG.replace(s, " ")

    /** a title or snippet: inline tags (<strong>, <b>) go without leaving spaces in Chinese text */
    private fun clean(s: String) = decodeEntities(TAG.replace(s, "")).replace(SPACES, " ").trim()

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max).trimEnd() + "…"

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ensp" to " ",
        "emsp" to " ", "middot" to "·", "hellip" to "…", "mdash" to "—", "ndash" to "–", "ldquo" to "“",
        "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "copy" to "©", "reg" to "®", "times" to "×",
    )

    internal fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e.lowercase()]
        } ?: m.value
    }

    // ---------------------------------------------------------------------------------------------
    // fetching

    /** refuses hosts on the phone itself or the local network */
    private fun checkPublic(url: URL) {
        if (url.protocol.lowercase() !in setOf("http", "https") || url.host.isNullOrBlank()) throw IOException("只能打开 http/https 网页")
        val addresses = try {
            InetAddress.getAllByName(url.host)
        } catch (_: Exception) {
            throw IOException("找不到网站 ${url.host}")
        }
        if (addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) {
            throw IOException("不能打开本机或局域网地址")
        }
    }

    /**
     * (content type, decoded body); follows up to 5 redirects, http <-> https included.
     * [publicOnly]: every hop must be a public host (a page could redirect to the local network)
     */
    private fun get(url: String, publicOnly: Boolean = false): Pair<String, String> {
        var target = url
        repeat(6) {
            if (publicOnly) checkPublic(URL(target))
            val conn = URL(target).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 6_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    target = URL(URL(target), location).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val type = conn.contentType?.lowercase() ?: ""
                val bytes = readCapped(conn)
                return type to decode(bytes, type)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("跳转太多次")
    }

    private fun readCapped(conn: HttpURLConnection): ByteArray {
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (out.size() < MAX_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, MAX_BYTES - out.size()))
            }
        }
        return out.toByteArray()
    }

    private val META_CHARSET = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)

    /** charset from the header, else from the page's meta tag (GBK sites), else UTF-8 */
    private fun decode(bytes: ByteArray, contentType: String): String {
        val name = Regex("charset=([\\w-]+)").find(contentType)?.groupValues?.get(1)
            ?: META_CHARSET.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val cs = try {
            name?.let { Charset.forName(if (it.equals("gb2312", true)) "GBK" else it) }
        } catch (_: Exception) {
            null
        } ?: Charsets.UTF_8
        return String(bytes, cs)
    }

    private const val MAX_BYTES = 1_500_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
}
