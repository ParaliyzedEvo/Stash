package com.stash.data.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test fun `parses single line with hundredths`() {
        val body = "[00:12.34]hello world"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals(12_340L, lines[0].timestampMs)
        assertEquals("hello world", lines[0].text)
    }

    @Test fun `parses single line with milliseconds`() {
        val body = "[00:12.345]hello world"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals(12_345L, lines[0].timestampMs)
    }

    @Test fun `sorts lines by timestamp`() {
        val body = "[01:00.00]b\n[00:00.00]a\n[00:30.00]middle"
        val lines = LrcParser.parse(body)
        assertEquals(listOf("a", "middle", "b"), lines.map { it.text })
    }

    @Test fun `expands multi-timestamp lines`() {
        val body = "[00:10.00][00:30.00]chorus"
        val lines = LrcParser.parse(body)
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timestampMs)
        assertEquals(30_000L, lines[1].timestampMs)
        assertTrue(lines.all { it.text == "chorus" })
    }

    @Test fun `strips standard metadata tags`() {
        val body = "[ti:Title]\n[ar:Artist]\n[al:Album]\n[length:03:45]\n[by:Author]\n[offset:+0]\n[00:10.00]line"
        val lines = LrcParser.parse(body)
        assertEquals(1, lines.size)
        assertEquals("line", lines[0].text)
    }

    @Test fun `skips malformed lines without throwing`() {
        val body = "garbage\n[00:10.00]ok\nalso garbage\n[bad timestamp]nope\n[00:20.00]ok2"
        val lines = LrcParser.parse(body)
        assertEquals(listOf("ok", "ok2"), lines.map { it.text })
    }

    @Test fun `returns empty list for completely malformed input`() {
        assertTrue(LrcParser.parse("not lyrics at all").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test fun `trims trailing whitespace but preserves internal spacing`() {
        val body = "[00:10.00]  hello  world   "
        val lines = LrcParser.parse(body)
        assertEquals("  hello  world", lines[0].text)
    }
}
