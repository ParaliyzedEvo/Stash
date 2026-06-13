package com.stash.core.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NullOutputStream] replaces the API-33-only `OutputStream.nullOutputStream()`
 * that crashed the ffmpeg stdout drainer on Android < 13 with
 * `NoSuchMethodError`. These tests pin the discard-sink behaviour the drainer
 * relies on (the API-level crash itself only reproduces on an API < 33 device).
 */
class NullOutputStreamTest {

    @Test
    fun `copyTo fully drains the input and discards it without throwing`() {
        val payload = ByteArray(100_000) { (it % 256).toByte() }
        val input = payload.inputStream()

        val copied = input.copyTo(NullOutputStream)

        assertEquals(payload.size.toLong(), copied)
        assertEquals(-1, input.read()) // input fully consumed — pipe stays drained
    }

    @Test
    fun `single-byte write is a no-op`() {
        NullOutputStream.write(42) // must not throw
    }

    @Test
    fun `bulk write is a no-op`() {
        NullOutputStream.write(ByteArray(1024), 0, 1024) // must not throw
    }
}
