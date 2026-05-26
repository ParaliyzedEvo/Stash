package com.stash.data.download.files

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Builds a FLAC Picture metadata block from raw JPEG bytes for use as a
 * METADATA_BLOCK_PICTURE Vorbis comment value (base64-encoded). Spec:
 * https://xiph.org/flac/format.html#metadata_block_picture
 *
 * Only handles JPEG input; embedding non-JPEG art is out of scope.
 */
object FlacPictureBlock {

    /** Parse JPEG SOF marker to get dimensions. Returns (width, height). */
    private fun jpegDimensions(jpeg: ByteArray): Pair<Int, Int> {
        var i = 2  // skip SOI marker
        while (i < jpeg.size - 1) {
            if (jpeg[i].toInt() and 0xFF != 0xFF) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2) — baseline / extended / progressive
            if (marker in 0xC0..0xC2) {
                if (i + 8 >= jpeg.size) return 0 to 0  // truncated SOF segment
                val height = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val width  = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return width to height
            }
            // Skip segment
            if (i + 3 >= jpeg.size) return 0 to 0  // truncated segment header
            val segLen = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (segLen < 2) return 0 to 0  // malformed segment length
            i += 2 + segLen
        }
        return 0 to 0  // unknown — most readers tolerate this
    }

    fun build(jpeg: ByteArray): ByteArray {
        val (width, height) = jpegDimensions(jpeg)
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val description = ByteArray(0)
        val bos = ByteArrayOutputStream(jpeg.size + 64)
        DataOutputStream(bos).use { out ->
            out.writeInt(3)             // picture_type: front cover
            out.writeInt(mime.size)
            out.write(mime)
            out.writeInt(description.size)
            out.write(description)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(24)            // colour depth
            out.writeInt(0)             // indexed colours
            out.writeInt(jpeg.size)
            out.write(jpeg)
        }
        return bos.toByteArray()
    }
}
