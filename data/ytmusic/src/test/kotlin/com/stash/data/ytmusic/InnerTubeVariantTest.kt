package com.stash.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure enum/config assertions for [InnerTubeVariant] and the audio-variant
 * attempt order. Locks the proven-working IOS request shape (www host,
 * current version, numeric client-name id, keyless) so a future edit that
 * regresses any of those facts breaks the build rather than the fast lane.
 */
class InnerTubeVariantTest {

    @Test fun ios_uses_www_host_and_current_version() {
        val ios = InnerTubeVariant.IOS
        assertEquals("21.02.3", ios.clientVersion)
        assertEquals("https://www.youtube.com/youtubei/v1", ios.apiBase)
        assertEquals("5", ios.clientNameId)
        assertFalse(ios.sendsApiKey)
    }

    @Test fun web_remix_stays_on_music_host_and_sends_key() {
        val web = InnerTubeVariant.WEB_REMIX
        assertEquals("https://music.youtube.com/youtubei/v1", web.apiBase)
        assertEquals("67", web.clientNameId)
        assertTrue(web.sendsApiKey)
    }

    @Test fun audio_variant_order_is_ios_and_android_music() {
        assertEquals(listOf(InnerTubeVariant.IOS, InnerTubeVariant.ANDROID_MUSIC), InnerTubeClient.AUDIO_VARIANT_ORDER)
    }
}
