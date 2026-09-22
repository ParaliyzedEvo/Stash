package com.stash.data.lyrics.parser

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import kotlin.math.roundToLong

/** One timed syllable. All times are song-absolute milliseconds. */
data class TtmlSyllable(val text: String, val partOfWord: Boolean, val startMs: Long, val endMs: Long)

/** The lead vocal of a line, or one adlib (background) vocal. */
data class TtmlGroup(val syllables: List<TtmlSyllable>, val startMs: Long, val endMs: Long) {
    /** Syllables glue together when [TtmlSyllable.partOfWord], otherwise space-separated. */
    val text: String
        get() = buildString {
            syllables.forEachIndexed { i, s ->
                append(s.text)
                if (i < syllables.lastIndex && !s.partOfWord) append(' ')
            }
        }
}

data class TtmlLine(val oppositeAligned: Boolean, val lead: TtmlGroup, val background: List<TtmlGroup>)

data class TtmlLyrics(val lines: List<TtmlLine>, val startMs: Long, val endMs: Long) {
    fun toPlainText(): String = lines.mapNotNull { it.displayText() }.joinToString("\n")

    /** Line-level LRC so every existing consumer (sheet, live bar, sidecar) keeps working. */
    fun toLrc(): String = lines.mapNotNull { line ->
        val text = line.displayText() ?: return@mapNotNull null
        val cs = line.lead.startMs / 10
        "[%02d:%02d.%02d]%s".format(Locale.ROOT, cs / 6000, cs / 100 % 60, cs % 100, text)
    }.joinToString("\n")
}

private fun TtmlLine.displayText(): String? =
    lead.text.ifBlank { background.firstOrNull()?.text.orEmpty() }.takeIf { it.isNotBlank() }

object TtmlParser {
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val OFFSET_TIME = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))(ms|h|m|s)?$""")

    private fun renderable(s: String) = ZERO_WIDTH.replace(s, "").isNotBlank()
    private fun Element.kids(): List<Node> = List(childNodes.length) { childNodes.item(it) }
    private fun Node.role(): String = (this as? Element)?.getAttribute("ttm:role").orEmpty()
    private fun Node.isVocalSpan() = this is Element && tagName == "span" && role().isEmpty()
    private fun Element.ownText() = kids()
        .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
        .joinToString("") { it.nodeValue }

    private fun parseTimeMs(s: String?): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        val seconds: Double = if (':' in t) {
            val parts = t.split(':')
            if (parts.size !in 2..4) return null
            val rev = parts.reversed()
            val offset = if (parts.size == 4) 1 else 0        // 4th part = SMPTE frames, dropped
            var sec = 0.0
            for (i in offset until rev.size) {
                sec += (rev[i].toDoubleOrNull() ?: return null) * 60.0.pow(i - offset)
            }
            sec
        } else {
            val m = OFFSET_TIME.matchEntire(t) ?: return null
            val v = m.groupValues[1].toDouble()
            when (m.groupValues[2]) { "ms" -> v / 1000; "h" -> v * 3600; "m" -> v * 60; else -> v }
        }
        return if (seconds >= 0) (seconds * 1000).roundToLong() else null
    }

    /** [nodes] = child nodes in document order, text nodes included: that's how word gluing is detected. */
    private fun buildSyllables(nodes: List<Node>, stripParens: Boolean): List<TtmlSyllable> {
        val out = ArrayList<TtmlSyllable>()
        nodes.forEachIndexed { i, node ->
            if (!node.isVocalSpan()) return@forEachIndexed
            val el = node as Element
            val raw = el.ownText()
            var text = raw.trim()
            if (stripParens) text = text.replace("(", "").replace(")", "").trim()
            if (text.isEmpty()) return@forEachIndexed
            val start = parseTimeMs(el.getAttribute("begin")) ?: return@forEachIndexed
            val end = parseTimeMs(el.getAttribute("end")) ?: return@forEachIndexed

            // Part of a word = next sibling is IMMEDIATELY another vocal span (no whitespace text
            // node between), no whitespace at the seam, and this one doesn't end in a comma.
            val next = nodes.getOrNull(i + 1)
            val part = next != null && next.isVocalSpan() &&
                !raw.last().isWhitespace() &&
                !raw.trim().endsWith(",") &&
                (next as Element).ownText().firstOrNull()?.isWhitespace() != true

            out += TtmlSyllable(text, part, start, end)
        }
        return out
    }

    /** Apple-style TTML (word, line or mixed). Null when unusable or unsynced-only. */
    fun parse(xml: String): TtmlLyrics? {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.newDocumentBuilder().parse(xml.trimStart('\uFEFF').byteInputStream())
        } catch (e: Exception) {
            return null
        }
        val tt = doc.documentElement ?: return null
        if (tt.getAttribute("itunes:timing") == "None") return null

        val lines = ArrayList<TtmlLine>()
        val divs = doc.getElementsByTagName("div")
        for (d in 0 until divs.length) {
            val div = divs.item(d) as Element
            if (div.getAttribute("itunes:songPart") == "Instrumental") continue
            val ps = div.getElementsByTagName("p")
            for (pi in 0 until ps.length) {
                val p = ps.item(pi) as Element
                val kids = p.kids()

                val agent = p.getAttribute("ttm:agent").ifEmpty { div.getAttribute("ttm:agent") }
                val opposite = agent == "v2" || agent == "v2000"          // v1 = main singer

                var lead = buildSyllables(kids, stripParens = false)
                val pStart = parseTimeMs(p.getAttribute("begin"))
                val pEnd = parseTimeMs(p.getAttribute("end"))

                // Line-timed <p> with no per-word spans: split on whitespace into separate
                // "words" sharing the line's whole window, rather than one giant un-splittable
                // syllable. A single opaque run can't be wrapped by the FlowRow layout and runs
                // off the edge of the screen on longer lines; separate words can.
                if (lead.isEmpty() && pStart != null && pEnd != null) {
                    val t = kids.filter { it.nodeType == Node.TEXT_NODE || it.isVocalSpan() }
                        .joinToString("") { it.textContent }.trim()
                    if (renderable(t)) {
                        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val totalChars = words.sumOf { it.length }.coerceAtLeast(1)
                        val lineStart: Long = pStart          // <- non-null locals, not captured `var`s of a nullable type
                        val lineEnd: Long = pEnd
                        val totalMs = (lineEnd - lineStart).coerceAtLeast(0L)
                        var cursor = lineStart
                        lead = words.mapIndexed { i, w ->
                            val start = cursor
                            val end = if (i == words.lastIndex) {
                                lineEnd
                            } else {
                                (cursor + ((w.length.toDouble() / totalChars) * totalMs).roundToLong())
                                    .coerceAtMost(lineEnd)
                            }
                            cursor = end
                            TtmlSyllable(w, partOfWord = false, startMs = start, endMs = end)
                        }
                    }
                }
                lead = lead.filter { renderable(it.text) }

                // Adlibs: <span ttm:role="x-bg">
                val bg = kids.filter { it.role() == "x-bg" }.mapNotNull { n ->
                    val el = n as Element
                    val syls = buildSyllables(el.kids(), stripParens = true).filter { renderable(it.text) }
                    if (syls.isEmpty()) null
                    else TtmlGroup(
                        syls,
                        parseTimeMs(el.getAttribute("begin")) ?: syls.first().startMs,
                        parseTimeMs(el.getAttribute("end")) ?: syls.last().endMs,
                    )
                }
                if (lead.isEmpty() && bg.isEmpty()) continue

                // Lead window has to cover its adlibs or they get clipped.
                var ls = pStart ?: lead.firstOrNull()?.startMs ?: bg.minOf { it.startMs }
                var le = pEnd ?: lead.lastOrNull()?.endMs ?: bg.maxOf { it.endMs }
                bg.forEach { ls = minOf(ls, it.startMs); le = maxOf(le, it.endMs) }

                lines += TtmlLine(opposite, TtmlGroup(lead, ls, le), bg)
            }
        }
        if (lines.isEmpty()) return null
        return TtmlLyrics(lines, lines.first().lead.startMs, lines.maxOf { it.lead.endMs })
    }
}