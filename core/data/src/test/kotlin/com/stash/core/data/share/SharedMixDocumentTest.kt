package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import org.junit.Test

class SharedMixDocumentTest {
    private val doc = SharedMixDocument(name = "Ambient", tracks = listOf(SharedTrack("T", "A")))

    @Test fun `server-set fields don't change the hash, content does`() {
        assertThat(doc.copy(id = "Kx7Qa2pL", version = 9, updatedAt = 123).contentHash()).isEqualTo(doc.contentHash())
        assertThat(doc.copy(tracks = listOf(SharedTrack("T", "B"))).contentHash()).isNotEqualTo(doc.contentHash())
        assertThat(doc.copy(name = "Sleep").contentHash()).isNotEqualTo(doc.contentHash())
    }
}
