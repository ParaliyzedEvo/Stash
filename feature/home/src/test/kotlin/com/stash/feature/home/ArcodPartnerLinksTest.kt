package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArcodPartnerLinksTest {

    @Test fun `both urls present yields kofi then discord`() {
        val links = arcodPartnerLinks(kofiUrl = "https://ko-fi.com/arcod", discordUrl = "https://discord.gg/arcod")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.KOFI, PartnerLinkKind.DISCORD).inOrder()
        assertThat(links.first().url).isEqualTo("https://ko-fi.com/arcod")
    }

    @Test fun `blank discord is omitted`() {
        val links = arcodPartnerLinks(kofiUrl = "https://ko-fi.com/arcod", discordUrl = "")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.KOFI)
    }

    @Test fun `blank kofi is omitted`() {
        val links = arcodPartnerLinks(kofiUrl = "  ", discordUrl = "https://discord.gg/arcod")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.DISCORD)
    }

    @Test fun `both blank yields empty`() {
        assertThat(arcodPartnerLinks(kofiUrl = "", discordUrl = "")).isEmpty()
    }
}
