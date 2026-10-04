package com.capsopasme.assistant.agent

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * Fuzzy matching of spoken names (app names, song titles, artists) against real names.
 *
 * Speech recognition gets Chinese names "right by sound": 危信 for 微信, 网易云因乐 for
 * 网易云音乐. So besides plain text, names are compared as toneless pinyin syllables with the
 * usual near-homophones folded together (zh/z, ch/c, sh/s, -ng/-n, l/n), plus an edit
 * distance over syllables for one wrong or missing syllable. Uses the platform ICU, no data files.
 */
object FuzzyMatch {

    private val pinyinCache = ConcurrentHashMap<String, List<String>>()

    private val transliterator: Transliterator by lazy {
        Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
    }

    /** lowercase, letters / digits / CJK only */
    fun norm(s: String): String = buildString {
        for (ch in s.lowercase()) if (Character.isLetterOrDigit(ch)) append(ch)
    }

    private fun isCjk(ch: Char) = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN

    /**
     * Syllables of a name: each Chinese character becomes its (folded) pinyin, runs of latin
     * letters / digits stay one token.
     */
    fun syllables(s: String): List<String> {
        val n = norm(s)
        if (n.isEmpty()) return emptyList()
        return pinyinCache.getOrPut(n) {
            val out = ArrayList<String>()
            val latin = StringBuilder()
            fun flush() {
                if (latin.isNotEmpty()) out.add(latin.toString()).also { latin.setLength(0) }
            }
            for (ch in n) {
                if (isCjk(ch)) {
                    flush()
                    val py = synchronized(transliterator) { transliterator.transliterate(ch.toString()) }
                    out.add(fold(norm(py)))
                } else latin.append(ch)
            }
            flush()
            if (pinyinCache.size > 4000) pinyinCache.clear()
            out
        }
    }

    /** fold near-homophones that speech recognition (and accents) mix up */
    private fun fold(py: String): String {
        var s = py
        if (s.length > 1) {
            s = when {
                s.startsWith("zh") -> "z" + s.substring(2)
                s.startsWith("ch") -> "c" + s.substring(2)
                s.startsWith("sh") -> "s" + s.substring(2)
                else -> s
            }
            if (s.endsWith("ng")) s = s.dropLast(1)
            if (s.startsWith("l")) s = "n" + s.substring(1)
        }
        return s
    }

    /**
     * How well the spoken [query] names [candidate], 0..1. Roughly:
     * 1.0 same text, 0.95 same sound, 0.75-0.9 one contains the other, below that partial.
     */
    fun score(query: String, candidate: String): Double {
        val q = norm(query)
        val c = norm(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0

        val qs = syllables(q)
        val cs = syllables(c)
        if (qs == cs) return 0.95

        var best = 0.0
        // containment, text then sound; the closer the lengths, the better
        if (q.length >= 2 && c.contains(q)) best = maxOf(best, 0.75 + 0.15 * q.length / c.length)
        if (c.length >= 2 && q.contains(c)) best = maxOf(best, 0.72 + 0.15 * c.length / q.length)
        if (qs.size >= 2 && containsRun(cs, qs)) best = maxOf(best, 0.72 + 0.15 * qs.size / cs.size)
        if (cs.size >= 2 && containsRun(qs, cs)) best = maxOf(best, 0.70 + 0.15 * cs.size / qs.size)

        // one or two syllables off (wrong character, dropped character)
        val longest = maxOf(qs.size, cs.size)
        if (longest >= 2) {
            val sim = 1.0 - editDistance(qs, cs).toDouble() / longest
            if (sim >= 0.5) best = maxOf(best, sim * 0.85)
        }
        // latin names: character edit distance ("wechat" / "we chat" / "wechart")
        if (q.all { it.code < 128 } && c.all { it.code < 128 } && maxOf(q.length, c.length) >= 4) {
            val sim = 1.0 - editDistance(q.toList(), c.toList()).toDouble() / maxOf(q.length, c.length)
            if (sim >= 0.6) best = maxOf(best, sim * 0.85)
        }
        return best
    }

    private fun containsRun(hay: List<String>, needle: List<String>): Boolean {
        if (needle.size > hay.size) return false
        for (i in 0..hay.size - needle.size) {
            if (hay.subList(i, i + needle.size) == needle) return true
        }
        return false
    }

    private fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.size]
    }
}
