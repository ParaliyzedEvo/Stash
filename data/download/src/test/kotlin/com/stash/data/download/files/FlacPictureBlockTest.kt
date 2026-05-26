package com.stash.data.download.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class FlacPictureBlockTest {

    /**
     * Hand-crafted minimal JPEG byte stream containing a valid SOF0 marker
     * with width=4, height=4. Not a decodable image (no Huffman tables or
     * SOS/EOI), but FlacPictureBlock only needs to parse the SOF marker and
     * round-trip the bytes — it does not validate the JPEG payload. We avoid
     * generating a real JPEG via java.awt/javax.imageio because those classes
     * are not on the Android compile classpath used by :data:download tests.
     *
     * Layout:
     *   FF D8                       SOI
     *   FF E0 00 04 00 00           APP0 segment (length 4, two byte payload)
     *   FF C0 00 11 08 00 04 00 04  SOF0: length=17, precision=8, H=4, W=4
     *   00 00 00 00 00 00 00 00     remaining SOF0 payload (8 zero bytes,
     *                               since length includes the two length bytes
     *                               themselves but not the marker, so 17-2-7=8)
     */
    private fun makeJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),                                     // SOI
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00,             // APP0 stub
        0xFF.toByte(), 0xC0.toByte(),                                     // SOF0 marker
        0x00, 0x11,                                                       // segment length 17
        0x08,                                                             // sample precision
        0x00, 0x04,                                                       // height = 4
        0x00, 0x04,                                                       // width = 4
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,                   // SOF0 payload tail
    )

    @Test fun `block contains MIME type and JPEG payload`() {
        val jpeg = makeJpeg()
        val block = FlacPictureBlock.build(jpeg)
        val input = DataInputStream(ByteArrayInputStream(block))

        val pictureType = input.readInt()
        val mimeLength = input.readInt()
        val mime = ByteArray(mimeLength).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        val descLength = input.readInt()
        val desc = ByteArray(descLength).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        val width = input.readInt()
        val height = input.readInt()
        val depth = input.readInt()
        val colors = input.readInt()
        val payloadLen = input.readInt()
        val payload = ByteArray(payloadLen).also { input.readFully(it) }

        assertEquals(3, pictureType)
        assertEquals(10, mimeLength)
        assertEquals("image/jpeg", mime)
        assertEquals(0, descLength)
        assertEquals("", desc)
        assertEquals(4, width)
        assertEquals(4, height)
        assertEquals(24, depth)
        assertEquals(0, colors)
        assertEquals(jpeg.size, payloadLen)
        assertArrayEquals(jpeg, payload)
        assertTrue(input.available() == 0)
    }

    @Test fun `truncated input returns dimensions zero zero`() {
        // SOI + start of SOF0 marker, then cut off
        val truncated = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),  // SOI
            0xFF.toByte(), 0xC0.toByte(),  // SOF0
            0x00, 0x05,                    // segment length (5 — but bytes don't exist)
            // truncated — no precision/height/width
        )
        val block = FlacPictureBlock.build(truncated)
        val input = DataInputStream(ByteArrayInputStream(block))
        input.readInt()  // pictureType
        val mimeLen = input.readInt(); input.readFully(ByteArray(mimeLen))
        val descLen = input.readInt(); input.readFully(ByteArray(descLen))
        val width = input.readInt()
        val height = input.readInt()
        assertEquals(0, width)
        assertEquals(0, height)
    }

    @Test fun `malformed segLen does not infinite loop`() {
        // SOI + APP0 with segLen=0 (malformed). Must terminate, not loop.
        val malformed = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                  // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x00,      // APP0 with segLen=0
            0xFF.toByte(), 0xC0.toByte(),                  // SOF0
            0x00, 0x0B, 0x08, 0x00, 0x04, 0x00, 0x04, 0x03, 0x01, 0x22, 0x00,
        )
        // If guard misses, this hangs. Just ensuring build() returns.
        val block = FlacPictureBlock.build(malformed)
        assertTrue(block.isNotEmpty())
    }

    @Test fun `progressive JPEG SOF2 marker is recognised`() {
        val sof2Jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                  // SOI
            0xFF.toByte(), 0xC2.toByte(),                  // SOF2 (progressive)
            0x00, 0x0B, 0x08, 0x00, 0x07, 0x00, 0x09, 0x03, 0x01, 0x22, 0x00,
        )
        val block = FlacPictureBlock.build(sof2Jpeg)
        val input = DataInputStream(ByteArrayInputStream(block))
        input.readInt()  // pictureType
        val mimeLen = input.readInt(); input.readFully(ByteArray(mimeLen))
        val descLen = input.readInt(); input.readFully(ByteArray(descLen))
        val width = input.readInt()
        val height = input.readInt()
        assertEquals(9, width)
        assertEquals(7, height)
    }
}
