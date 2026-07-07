package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM (no Robolectric): QbdlxPoolCipher uses only javax.crypto + java.util.Base64. */
class QbdlxPoolCipherTest {

    private val pool = "tokA:FR,tokB:GB,tokC:NO"

    @Test
    fun `round-trips a multi-entry pool`() {
        assertThat(QbdlxPoolCipher.decrypt(QbdlxPoolCipher.encrypt(pool))).isEqualTo(pool)
    }

    /**
     * DRIFT GUARD. FIXTURE_BLOB was produced ONCE by the build-side scheme and is
     * checked in. If either copy of the scheme (this class OR build.gradle.kts)
     * changes without the other, this fails. Regenerate only by intentionally
     * re-running the encrypt and pasting the new blob here.
     */
    @Test
    fun `decrypts the checked-in fixture blob`() {
        assertThat(QbdlxPoolCipher.decrypt(FIXTURE_BLOB)).isEqualTo(FIXTURE_PLAINTEXT)
    }

    @Test
    fun `blank and malformed inputs return empty, never throw`() {
        assertThat(QbdlxPoolCipher.decrypt("")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("   ")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("not base64 @@@")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("QQ==")).isEmpty() // valid b64, too short for IV
    }

    @Test
    fun `a tampered tag fails to empty`() {
        val blob = QbdlxPoolCipher.encrypt(pool)
        val tampered = blob.dropLast(2) + (if (blob.last() == 'A') "BB" else "AA")
        assertThat(QbdlxPoolCipher.decrypt(tampered)).isEmpty()
    }

    private companion object {
        const val FIXTURE_PLAINTEXT = "fixtureTok:FR,otherTok:GB"
        // Generated once via QbdlxPoolCipher.encrypt(FIXTURE_PLAINTEXT) — see below.
        const val FIXTURE_BLOB = "HwY/iZ+USNGlU2kdTl8XeThGoQOo6RfEHFwaGGlOX1Tp0C3gCfu69dl1yffvv0of27b03GY="
    }
}
