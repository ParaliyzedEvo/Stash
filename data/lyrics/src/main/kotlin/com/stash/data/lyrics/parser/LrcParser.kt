package com.stash.data.lyrics.parser

data class LrcLine(val timestampMs: Long, val text: String)

object LrcParser {

    /**
     * Parses an LRC body into a sorted, timestamp-tagged line list.
     *
     * Recognises:
     *   - `[mm:ss.xx]text` and `[mm:ss.xxx]text`
     *   - multiple timestamps per line: `[00:10.00][00:30.00]text` -> two LrcLine entries
     *
     * Strips well-known metadata tags: `[ti:...] [ar:...] [al:...] [length:...] [by:...] [offset:...]`.
     * Skips malformed lines (logs nothing — they're expected from real LRCLIB bodies).
     */
    fun parse(body: String): List<LrcLine> {
        if (body.isBlank()) return emptyList()
        val out = mutableListOf<LrcLine>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            if (META_TAG.matches(line)) continue

            // Collect every leading [mm:ss.xx] timestamp; the text is whatever follows the last one.
            val timestamps = mutableListOf<Long>()
            var idx = 0
            while (idx < line.length && line[idx] == '[') {
                val close = line.indexOf(']', idx)
                if (close == -1) break
                val token = line.substring(idx + 1, close)
                val ms = parseTimestampMs(token) ?: break
                timestamps += ms
                idx = close + 1
            }
            if (timestamps.isEmpty()) continue
            val text = line.substring(idx).trimEnd()
            if (text.isBlank()) continue
            timestamps.forEach { ms -> out += LrcLine(ms, text) }
        }
        return out.sortedBy { it.timestampMs }
    }

    private val META_TAG = Regex("""^\[(ti|ar|al|length|by|offset|au|re|ve):.*]\s*$""", RegexOption.IGNORE_CASE)
    private val TIMESTAMP = Regex("""^(\d{1,2}):(\d{2})\.(\d{2,3})$""")

    private fun parseTimestampMs(token: String): Long? {
        val match = TIMESTAMP.matchEntire(token) ?: return null
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val frac = match.groupValues[3]
        val fracMs = when (frac.length) {
            2 -> frac.toLong() * 10
            3 -> frac.toLong()
            else -> return null
        }
        return (minutes * 60_000) + (seconds * 1_000) + fracMs
    }
}
