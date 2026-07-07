package com.stash.core.auth.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library-mutation persisted-query hash is extracted from the web-player
 * bundle so it self-heals when Spotify rotates it (a PersistedQueryNotFound).
 * These pin the pure extractor against the exact minified shape observed in
 * web-player.687461f7.js (2026-07-04).
 */
class SpotifyLibraryMutationHashTest {

    @Test fun `extracts hash from the addToLibrary constructor`() {
        val js = """...)}return e===l8.E3?"spotify:local-files":e}let cb=new tV.l(""" +
            """"addToLibrary","mutation","1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",null),""" +
            """cE=new tV.l("removeFromLibrary","mutation","1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",null)"""

        assertEquals(
            "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",
            SpotifyAuthManager.extractLibraryMutationHash(js),
        )
    }

    @Test fun `returns null when the operation is absent`() {
        assertNull(SpotifyAuthManager.extractLibraryMutationHash("no library ops here"))
    }

    @Test fun `ignores a non-hex or wrong-length token`() {
        val js = """new X("addToLibrary","mutation","NOTAHASH",null)"""
        assertNull(SpotifyAuthManager.extractLibraryMutationHash(js))
    }
}
