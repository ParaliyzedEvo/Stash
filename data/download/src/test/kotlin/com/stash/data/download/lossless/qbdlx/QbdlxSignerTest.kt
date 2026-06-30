package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QbdlxSignerTest {
    private val secret = "abb21364945c0583309667d13ca3d93a"

    @Test fun `getFileUrl signature matches HAR vector (fmt27)`() {
        val signer = QbdlxSigner(secret) { 1782781652L }
        val sig = signer.signGetFileUrl(trackId = 2841459, formatId = 27)
        assertThat(sig).isEqualTo("013c10042c5e15ca5f1d85610bdd62ad")
    }

    @Test fun `getFileUrl signature matches HAR vector (fmt6)`() {
        val signer = QbdlxSigner(secret) { 1782781565L }
        val sig = signer.signGetFileUrl(trackId = 3144087, formatId = 6)
        assertThat(sig).isEqualTo("ff083dedd464374d86affbb22daeae01")
    }

    @Test fun `lyricsUrl signature matches HAR vector`() {
        val signer = QbdlxSigner(secret) { 1782781552L }
        val sig = signer.signLyricsUrl(trackId = 2841459)
        assertThat(sig).isEqualTo("e8149d392b9654ade72856fa3150d5a9")
    }

    @Test fun `ts comes from the injected clock`() {
        val signer = QbdlxSigner(secret) { 1782781652L }
        assertThat(signer.requestTs()).isEqualTo(1782781652L)
    }
}
