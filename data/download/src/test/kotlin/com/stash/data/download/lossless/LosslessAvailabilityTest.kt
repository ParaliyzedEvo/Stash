package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.arcod.ArcodCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxLoginCredential
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessAvailabilityTest {
    private val login = MutableStateFlow(false)
    private val relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val custom = MutableStateFlow<String?>(null)
    private val arcod = MutableStateFlow<String?>(null)
    private val store: QbdlxCredentialStore = mockk { every { hasLogin } returns login }
    private val config: LosslessConfigFetcher = mockk { every { this@mockk.relays } returns this@LosslessAvailabilityTest.relays }
    private val prefs: LosslessSourcePreferences = mockk { every { customLosslessEndpoint } returns custom }
    private val relayClient: LosslessRelayClient = mockk()
    private val arcodStore: ArcodCredentialStore = mockk { every { accessToken } returns arcod }
    private val a = LosslessAvailability(store, config, prefs, relayClient, arcodStore)

    private fun stubNow(loginLive: Boolean = false, customNow: String? = null, cooled: Set<String> = emptySet()) {
        coEvery { store.loginLive() } returns loginLive
        coEvery { store.loginCredential() } returns if (loginLive) QbdlxLoginCredential("t", "a", "s") else null
        coEvery { prefs.customLosslessEndpointNow() } returns customNow
        every { relayClient.isCooled(any()) } answers { firstArg<String>() in cooled }
    }

    @Test fun `nothing configured - every predicate false`() = runTest {
        stubNow()
        assertThat(a.qbdlxEnabled.first()).isFalse()
        assertThat(a.fileUrlAvailableNow()).isFalse()
        assertThat(a.anyConfigured.first()).isFalse()
        assertThat(a.anyUserOwned.first()).isFalse()
    }

    @Test fun `relay configured - enabled and configured, but not user-owned`() = runTest {
        relays.value = listOf(RelayEntry("https://r.example", 1)); stubNow()
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyConfigured.first()).isTrue()
        assertThat(a.anyUserOwned.first()).isFalse()
    }

    @Test fun `a cooled relay is still configured but not available now`() = runTest {
        relays.value = listOf(RelayEntry("https://r.example", 1)); stubNow(cooled = setOf("https://r.example"))
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `BYO login is user-owned and available`() = runTest {
        login.value = true; stubNow(loginLive = true)
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyUserOwned.first()).isTrue()
    }

    @Test fun `custom endpoint is user-owned - cooled custom is not available`() = runTest {
        custom.value = "https://mine.example"; stubNow(customNow = "https://mine.example", cooled = setOf("https://mine.example"))
        assertThat(a.anyUserOwned.first()).isTrue()
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `ARCOD alone counts as configured and user-owned but not qbdlx`() = runTest {
        arcod.value = "arcod-token"; stubNow()
        assertThat(a.qbdlxEnabled.first()).isFalse()
        assertThat(a.anyConfigured.first()).isTrue()
        assertThat(a.anyUserOwned.first()).isTrue()
    }
}
