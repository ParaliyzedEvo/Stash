package com.stash.feature.home

/**
 * ARCOD partner metadata + the Home strip's links. ARCOD-only, static; Home is the
 * sole consumer of these URLs (Settings only reuses the shared logo). Replace the
 * placeholder URLs/tagline with the operator-supplied values before release; blank
 * URLs are simply hidden (see [arcodPartnerLinks]).
 */
object ArcodPartner {
    const val NAME = "ARCOD"
    const val TAGLINE = "Lossless FLAC source · part of Stash's backbone"
    const val KOFI_URL = "https://ko-fi.com/arcod"
    const val DISCORD_URL = "https://discord.com/invite/hgC6ZegbKD"
}

enum class PartnerLinkKind { KOFI, DISCORD }

/** A resolved, openable partner link. */
data class PartnerLink(val kind: PartnerLinkKind, val url: String)

/**
 * The partner's openable links in display order (Ko-fi, then Discord), omitting any
 * whose URL is blank — so the strip degrades gracefully before the real URLs are set.
 */
fun arcodPartnerLinks(
    kofiUrl: String = ArcodPartner.KOFI_URL,
    discordUrl: String = ArcodPartner.DISCORD_URL,
): List<PartnerLink> = buildList {
    if (kofiUrl.isNotBlank()) add(PartnerLink(PartnerLinkKind.KOFI, kofiUrl.trim()))
    if (discordUrl.isNotBlank()) add(PartnerLink(PartnerLinkKind.DISCORD, discordUrl.trim()))
}
