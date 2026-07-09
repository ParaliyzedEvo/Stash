package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The artist-discography "View all" button carries a `params` token that scopes
 * the grid to albums-only vs singles-only. [parseCarouselMore] must capture it
 * (dropping it returns the artist's full unfiltered discography, dumping
 * singles/EPs into the album grid).
 */
class CarouselMoreParserTest {
    private fun carousel(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun `extracts browseId and params from moreContentButton`() {
        val more = parseCarouselMore(
            carousel(
                """
                {"header":{"musicCarouselShelfBasicHeaderRenderer":{"moreContentButton":
                {"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":
                {"browseId":"MPADUCabc","params":"ggMEChI...="}}}}}}}
                """.trimIndent(),
            ),
        )
        assertEquals("MPADUCabc", more?.browseId)
        assertEquals("ggMEChI...=", more?.params)
    }

    @Test fun `params null when the endpoint has no params (graceful)`() {
        val more = parseCarouselMore(
            carousel(
                """
                {"header":{"musicCarouselShelfBasicHeaderRenderer":{"moreContentButton":
                {"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":
                {"browseId":"MPADUCabc"}}}}}}}
                """.trimIndent(),
            ),
        )
        assertEquals("MPADUCabc", more?.browseId)
        assertNull(more?.params)
    }

    @Test fun `null when there is no more button`() {
        assertNull(
            parseCarouselMore(carousel("""{"header":{"musicCarouselShelfBasicHeaderRenderer":{}}}""")),
        )
    }
}
