package com.stash.core.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogcatCaptureTest {
    private lateinit var context: Context
    private lateinit var dir: File

    // Small-cap subclass so rotation triggers quickly in the test.
    private class SmallCap(context: Context) : LogcatCapture(context) {
        override val maxBytes: Long = 8L * 1024
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "diagnostics")
        dir.deleteRecursively()
    }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `recentLogs returns the last N lines across rotation`() {
        val capture = SmallCap(context)
        repeat(50) { capture.append("line-$it") }
        val lines = capture.recentLogs(maxLines = 10).trim().lines()
        assertEquals(10, lines.size)
        assertEquals("line-49", lines.last())
    }

    @Test fun `append rotates when the active file exceeds the cap`() {
        val capture = SmallCap(context)
        val big = "x".repeat(1000)
        repeat(20) { capture.append(big) } // ~20 KB > 8 KB cap
        assertTrue(File(dir, "applog.1.txt").exists()) // rotation happened
        capture.append("freshest")
        assertTrue(capture.recentLogs(5).contains("freshest"))
    }
}
