package com.stash.core.data.repository

import com.stash.core.data.db.dao.DownloadedFileRef
import com.stash.core.data.files.LocalFileState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadReconcileTest {

    private fun refs(vararg pairs: Pair<Long, String?>) =
        pairs.map { (id, path) -> DownloadedFileRef(id = id, filePath = path) }

    @Test fun `keeps OK, resets+deletes TOO_SMALL, resets MISSING, leaves INCONCLUSIVE`() {
        val states = mapOf(
            "/real.flac" to LocalFileState.OK,
            "/junk.webm" to LocalFileState.TOO_SMALL,
            "/gone.flac" to LocalFileState.MISSING,
            "content://saf/flaky" to LocalFileState.INCONCLUSIVE,
        )
        val result = classifyDownloadedRefs(
            refs(
                1L to "/real.flac",          // OK        -> keep
                2L to "/junk.webm",          // TOO_SMALL -> reset + delete
                3L to "/gone.flac",          // MISSING   -> reset, no delete
                4L to "content://saf/flaky", // INCONCLUSIVE -> leave alone
                5L to null,                  // null path -> MISSING -> reset, counted
            ),
        ) { if (it.isNullOrBlank()) LocalFileState.MISSING else states.getValue(it) }

        // Inconclusive SAF row (id 4) is deliberately absent from BOTH lists.
        assertEquals(listOf(2L, 3L, 5L), result.resetIds)
        assertEquals(listOf("/junk.webm"), result.junkPaths)
        assertEquals(1, result.nullPath)
    }

    @Test fun `INCONCLUSIVE rows are never reset or deleted (flaky SAF cold-start safety)`() {
        // The core I1 guard: a transient SAF read must not damage a real
        // external-storage library.
        val result = classifyDownloadedRefs(
            refs(1L to "content://a", 2L to "content://b"),
        ) { LocalFileState.INCONCLUSIVE }

        assertEquals(emptyList<Long>(), result.resetIds)
        assertEquals(emptyList<String>(), result.junkPaths)
    }

    @Test fun `all-OK library touches nothing`() {
        val result = classifyDownloadedRefs(
            refs(1L to "/a.flac", 2L to "/b.flac"),
        ) { LocalFileState.OK }

        assertEquals(emptyList<Long>(), result.resetIds)
        assertEquals(emptyList<String>(), result.junkPaths)
        assertEquals(0, result.nullPath)
    }
}
