package com.stash.core.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unit tests for [CrashFileStore].
 *
 * Covers the three behaviours that the Settings UI depends on: writing
 * a fresh report, rotating older files past the cap, and surfacing the
 * newest report via [CrashFileStore.latestCrashFile]. FileProvider URI
 * generation is not asserted here — that requires the FileProvider to
 * be merged into the test manifest, which it isn't (the provider lives
 * in the `app` module's manifest only).
 */
@RunWith(RobolectricTestRunner::class)
class CrashFileStoreTest {

    private lateinit var context: Context
    private lateinit var store: CrashFileStore
    private lateinit var crashDir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = CrashFileStore(context)
        crashDir = File(context.cacheDir, "crashes")
        crashDir.deleteRecursively()
    }

    @After fun tearDown() { crashDir.deleteRecursively() }

    @Test fun `latestCrashFile is null when no files exist`() {
        assertNull(store.latestCrashFile())
        assertTrue(store.allCrashFiles().isEmpty())
    }

    @Test fun `writeCrash creates a file in cacheDir-crashes`() {
        store.writeCrash(Thread.currentThread(), RuntimeException("boom"))

        val all = store.allCrashFiles()
        assertEquals(1, all.size)
        assertTrue(all[0].name.startsWith("crash-"))
        assertTrue(all[0].name.endsWith(".txt"))
        assertEquals(all[0], store.latestCrashFile())
    }

    @Test fun `report body contains version, device, thread, and stack trace`() {
        val report = store.formatReport(
            Thread.currentThread(),
            RuntimeException("boom-marker-xyz"),
        )

        assertTrue("expected app version line", report.contains("App version:"))
        assertTrue("expected device line", report.contains("Device:"))
        assertTrue("expected android line", report.contains("Android:"))
        assertTrue("expected thread line", report.contains("Thread:"))
        assertTrue(
            "expected stack trace header",
            report.contains("Stack trace") && report.contains("-----------"),
        )
        assertTrue("expected exception message in trace", report.contains("boom-marker-xyz"))
        assertTrue("expected RuntimeException in trace", report.contains("RuntimeException"))
    }

    @Test fun `deviceMetadataBlock contains version and android lines but not crash-specific lines`() {
        val block = store.deviceMetadataBlock()
        assertTrue(block.contains("App version:"))
        assertTrue(block.contains("Android:"))
        assertFalse(block.contains("Thread:"))
        assertFalse(block.contains("Stack trace"))
    }

    @Test fun `caused-by chain is preserved in the dump`() {
        val root = IllegalStateException("root-cause-msg")
        val wrapped = RuntimeException("wrapper-msg", root)

        val report = store.formatReport(Thread.currentThread(), wrapped)

        assertTrue(report.contains("wrapper-msg"))
        assertTrue(report.contains("Caused by"))
        assertTrue(report.contains("root-cause-msg"))
    }

    @Test fun `rotate keeps the most recent 10 files`() {
        // Seed 12 files with strictly increasing lastModified timestamps so
        // sortedByDescending in allCrashFiles produces a deterministic order.
        if (!crashDir.exists()) crashDir.mkdirs()
        repeat(12) { idx ->
            val f = File(crashDir, "crash-2026-05-15_00-00-${"%02d".format(idx)}.txt")
            f.writeText("dummy $idx")
            f.setLastModified(1_000_000L + idx * 1_000L)
        }

        assertEquals(12, store.allCrashFiles().size)
        store.rotate()

        val remaining = store.allCrashFiles()
        assertEquals(10, remaining.size)
        // The two oldest (idx 0 and idx 1) should be gone — newest first
        // ordering means the survivors are 11..2.
        assertTrue(remaining.all { f ->
            !f.name.endsWith("_00.txt") && !f.name.endsWith("_01.txt")
        })
    }

    @Test fun `writeCrash triggers rotation past the cap`() {
        if (!crashDir.exists()) crashDir.mkdirs()
        // Pre-populate 10 stale files older than what writeCrash will produce.
        repeat(10) { idx ->
            val f = File(crashDir, "crash-2026-05-15_00-00-${"%02d".format(idx)}.txt")
            f.writeText("stale $idx")
            f.setLastModified(1_000_000L + idx * 1_000L)
        }
        assertEquals(10, store.allCrashFiles().size)

        store.writeCrash(Thread.currentThread(), RuntimeException("fresh"))

        // Still capped at 10 after the write + rotate cycle. The freshly
        // written file should be the newest survivor.
        val remaining = store.allCrashFiles()
        assertEquals(10, remaining.size)
        assertEquals(store.latestCrashFile(), remaining[0])
        assertTrue(remaining[0].readText().contains("fresh"))
    }

    @Test fun `allCrashFiles ignores non-txt entries`() {
        if (!crashDir.exists()) crashDir.mkdirs()
        File(crashDir, "crash-real.txt").writeText("ok")
        File(crashDir, "noise.log").writeText("noise")
        File(crashDir, "stray").writeText("stray")

        val all = store.allCrashFiles()
        assertEquals(1, all.size)
        assertEquals("crash-real.txt", all[0].name)
    }

    @Test fun `rotate is a no-op when count is at or below the cap`() {
        if (!crashDir.exists()) crashDir.mkdirs()
        repeat(5) { idx ->
            File(crashDir, "crash-$idx.txt").writeText("$idx")
        }
        store.rotate()
        assertEquals(5, store.allCrashFiles().size)
    }

    @Test fun `writeCrash on a missing crash dir creates it on demand`() {
        assertFalse(crashDir.exists())
        store.writeCrash(Thread.currentThread(), RuntimeException("first"))
        assertTrue(crashDir.exists())
        assertNotNull(store.latestCrashFile())
    }
}
