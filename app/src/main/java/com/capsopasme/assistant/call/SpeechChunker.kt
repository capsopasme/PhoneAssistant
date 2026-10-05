package com.capsopasme.assistant.call

/**
 * Cuts the model's streamed answer into pieces worth handing to the TTS engine one by one, so
 * the first sentence is spoken while the rest is still being generated.
 *
 * Pieces end at sentence punctuation. The first piece may also end at a comma once it is long
 * enough (the first audio comes sooner); later pieces only at a comma when a sentence runs very
 * long. Whole sentences otherwise: the voice sounds more natural across one sentence.
 */
class SpeechChunker {

    private val buf = StringBuilder()
    private var emittedAny = false

    /** @return pieces completed by [delta], in order */
    fun push(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val end = findCut() ?: break
            val piece = buf.substring(0, end)
            buf.delete(0, end)
            emit(piece)?.let { out.add(it) }
        }
        return out
    }

    /** the rest, at the end of the answer */
    fun flush(): String? {
        val rest = buf.toString()
        buf.setLength(0)
        return emit(rest)
    }

    private fun emit(piece: String): String? {
        val t = SpeechText.clean(piece)
        if (!SpeechText.hasWords(t)) return null
        emittedAny = true
        return t
    }

    /** index just after the cut, or null if no piece is complete yet */
    private fun findCut(): Int? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            when {
                c in SENTENCE_END -> return cutAfter(i)
                // "3.5" / "v1.2" are not sentence ends; ". " in English is
                c == '.' && i + 1 < buf.length && buf[i + 1].isWhitespace() -> return cutAfter(i)
                // "晚安呀～好梦" ends a sentence at the tilde; "3～5" is a range
                c in TILDES && i + 1 < buf.length && buf[i + 1] !in TILDES &&
                        !(i > 0 && buf[i - 1].isDigit() && buf[i + 1].isDigit()) -> return cutAfter(i)
                // ASCII "," / ":" only before a space: "1,000" and "https://" are not pauses
                c in PAUSE && (c.code >= 128 || i + 1 < buf.length && buf[i + 1].isWhitespace()) -> {
                    val len = i + 1
                    if (!emittedAny && len >= FIRST_PIECE_MIN) return cutAfter(i)
                    if (len >= LONG_PIECE) return cutAfter(i)
                }
            }
            i++
        }
        return null
    }

    /** include closing quotes / brackets and repeated marks ("！！", "。”", "～～") in the piece */
    private fun cutAfter(i: Int): Int {
        var j = i + 1
        while (j < buf.length && (buf[j] in SENTENCE_END || buf[j] in CLOSERS || buf[j] in TILDES)) j++
        return j
    }

    companion object {
        private const val SENTENCE_END = "。！？!?；;…\n"
        private const val TILDES = "～~"
        private const val PAUSE = "，,、：:"
        private const val CLOSERS = "”’\"')）】」』"

        /** the first piece may end at a comma once this long (chars) */
        private const val FIRST_PIECE_MIN = 8

        /** later pieces end at a comma only past this length */
        private const val LONG_PIECE = 40
    }
}

/** Light cleanup of model text for speaking; the TTS engine does its own number reading */
object SpeechText {
    private val URL = Regex("https?://\\S+")
    private val MARKDOWN = Regex("(\\*\\*|__|`+|~~)")
    private val HEADING = Regex("(?m)^\\s*#{1,6}\\s*")
    private val BULLET = Regex("(?m)^\\s*([-*•]|\\d+[.)、])\\s+")
    private val SPACES = Regex("[ \\t]+")
    private val RANGE = Regex("(\\d)\\s*[~～]+\\s*(?=\\d)")
    private val TILDE = Regex("\\s*[~～]+\\s*")

    fun clean(text: String): String {
        var t = URL.replace(text, "")
        t = MARKDOWN.replace(t, "")
        t = HEADING.replace(t, "")
        t = BULLET.replace(t, "")
        t = stripEmoji(t)
        // engines read a tilde oddly or as a symbol: "3～5" is "3到5", otherwise it's a pause
        t = RANGE.replace(t, "$1到")
        t = TILDE.replace(t, "，")
        return SPACES.replace(t, " ").trim().trim('，').trim()
    }

    /** anything a voice would say (not only punctuation / symbols) */
    fun hasWords(text: String): Boolean = text.any { it.isLetterOrDigit() }

    private fun stripEmoji(text: String): String {
        if (text.none { Character.isSurrogate(it) || it.code in 0x2600..0x27BF || it == '️' || it == '‍' }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
            if (!emoji) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
