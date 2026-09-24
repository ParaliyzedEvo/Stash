package com.stash.data.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {

    private fun tt(body: String, timing: String = "Word") =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" """ +
            """xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="$timing"><body><div>$body</div></body></tt>"""

    @Test fun `word-level timing keeps per-syllable times and glues split words`() {
        val xml = tt(
            """<p begin="1.000" end="3.000" ttm:agent="v1">""" +
                """<span begin="1.000" end="1.400">Hel</span><span begin="1.400" end="1.800">lo</span> """ +
                """<span begin="2.000" end="3.000">world</span></p>""",
        )

        val lyrics = TtmlParser.parse(xml)!!
        val lead = lyrics.lines.single().lead
        assertEquals(listOf("Hel", "lo", "world"), lead.syllables.map { it.text })
        assertTrue("'Hel' glues to 'lo'", lead.syllables[0].partOfWord)
        assertFalse(lead.syllables[1].partOfWord)
        assertEquals(1_400L, lead.syllables[1].startMs)
        assertEquals(1_800L, lead.syllables[1].endMs)
        assertEquals("Hello world", lead.text)
        assertEquals("[00:01.00]Hello world", lyrics.toLrc())
    }

    @Test fun `line-level only timing splits the line into words across its window`() {
        val xml = tt(
            """<p begin="00:10.000" end="00:12.000">Only line timing</p>""" +
                """<p begin="00:13.500" end="00:15.000">Second line</p>""",
            timing = "Line",
        )

        val lyrics = TtmlParser.parse(xml)!!
        assertEquals(2, lyrics.lines.size)
        val first = lyrics.lines[0].lead
        assertEquals(listOf("Only", "line", "timing"), first.syllables.map { it.text })
        assertEquals(10_000L, first.syllables.first().startMs)
        assertEquals(12_000L, first.syllables.last().endMs)
        assertEquals("[00:10.00]Only line timing\n[00:13.50]Second line", lyrics.toLrc())
        assertEquals("Only line timing\nSecond line", lyrics.toPlainText())
    }

    @Test fun `malformed or unsynced input returns null without throwing`() {
        assertNull(TtmlParser.parse(""))
        assertNull(TtmlParser.parse("not xml at all"))
        assertNull(TtmlParser.parse("<tt><body><div><p begin=\"1.0\""))           // truncated
        assertNull(TtmlParser.parse(tt("")))                                     // no lines
        assertNull(TtmlParser.parse(tt("<p>no timing</p>", timing = "None")))    // unsynced
        assertNull(TtmlParser.parse("<!DOCTYPE x [<!ENTITY e \"boom\">]><tt>&e;</tt>"))
    }
}
