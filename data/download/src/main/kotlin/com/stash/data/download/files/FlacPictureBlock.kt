package com.stash.data.download.files

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Builds a FLAC Picture metadata block from raw JPEG bytes for use as a
 * METADATA_BLOCK_PICTURE Vorbis comment value (base64-encoded). Spec:
 * https://xiph.org/flac/format.html#metadata_block_picture
 */
object FlacPictureBlock {

    /** Parse image bytes to get dimensions. Supports JPEG and PNG. Returns (width, height). */
    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size >= 24 &&
            bytes[0].toInt() and 0xFF == 0x89 &&
            bytes[1].toInt() and 0xFF == 0x50 &&
            bytes[2].toInt() and 0xFF == 0x4E &&
            bytes[3].toInt() and 0xFF == 0x47
        ) {
            // PNG format: IHDR chunk width starts at offset 16, height at 20
            val width = ((bytes[16].toInt() and 0xFF) shl 24) or
                        ((bytes[17].toInt() and 0xFF) shl 16) or
                        ((bytes[18].toInt() and 0xFF) shl 8) or
                        (bytes[19].toInt() and 0xFF)
            val height = ((bytes[20].toInt() and 0xFF) shl 24) or
                         ((bytes[21].toInt() and 0xFF) shl 16) or
                         ((bytes[22].toInt() and 0xFF) shl 8) or
                         (bytes[23].toInt() and 0xFF)
            return width to height
        }

        // Default to JPEG scanning
        var i = 2  // skip SOI marker
        while (i < bytes.size - 1) {
            if (bytes[i].toInt() and 0xFF != 0xFF) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2) — baseline / extended / progressive
            if (marker in 0xC0..0xC2) {
                if (i + 8 >= bytes.size) return 0 to 0  // truncated SOF segment
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width  = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return width to height
            }
            // Skip segment
            if (i + 3 >= bytes.size) return 0 to 0  // truncated segment header
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (segLen < 2) return 0 to 0  // malformed segment length
            i += 2 + segLen
        }
        return 0 to 0  // unknown
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 4 &&
            bytes[0].toInt() and 0xFF == 0x89 &&
            bytes[1].toInt() and 0xFF == 0x50 &&
            bytes[2].toInt() and 0xFF == 0x4E &&
            bytes[3].toInt() and 0xFF == 0x47
        ) {
            return "image/png"
        }
        return "image/jpeg"
    }

    fun build(bytes: ByteArray): ByteArray {
        val (width, height) = imageDimensions(bytes)
        val mime = detectMimeType(bytes).toByteArray(Charsets.US_ASCII)
        val description = ByteArray(0)
        val bos = ByteArrayOutputStream(bytes.size + 64)
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
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        return bos.toByteArray()
    }
}
