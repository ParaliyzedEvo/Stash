package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Focused coverage of the lossless Settings wiring: the "no lossless path
 * configured" badge. The rest of this 30-dependency ViewModel is exercised via
 * its Compose screen + the per-pref unit tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val losslessPrefs = mockk<LosslessSourcePreferences>(relaxed = true)
    private val qbdlxStore = mockk<QbdlxCredentialStore>(relaxed = true)
    private val librarySizeHolder = mockk<LibrarySizeHolder>(relaxed = true)

    private fun newVm(losslessConfigured: Boolean = true) = SettingsViewModel(
        appContext = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        librarySizeHolder = librarySizeHolder,
        qualityPreference = mockk(relaxed = true),
        themePreference = mockk(relaxed = true),
        storagePreference = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        moveLibraryCoordinator = mockk(relaxed = true),
        youTubeCookieHelper = mockk(relaxed = true),
        lastFmApiClient = mockk(relaxed = true),
        lastFmSessionPreference = mockk(relaxed = true),
        lastFmCredentials = mockk(relaxed = true),
        listeningEventDao = mockk(relaxed = true),
        lastFmScrobbler = mockk(relaxed = true),
        youTubeHistoryPreference = mockk(relaxed = true),
        stashMixPreference = mockk(relaxed = true),
        youTubeHistoryScrobbler = mockk(relaxed = true),
        youTubeScrobblerState = mockk(relaxed = true),
        losslessPrefs = losslessPrefs,
        streamingQualityPrefs = mockk(relaxed = true),
        losslessRateLimiter = mockk(relaxed = true),
        qobuzSource = mockk(relaxed = true),
        arcodCredentialStore = mockk(relaxed = true),
        qbdlxCredentialStore = qbdlxStore,
        losslessAvailability = mockk<LosslessAvailability> {
            every { qbdlxEnabled } returns flowOf(losslessConfigured)
            every { routingRows } returns flowOf(emptyList())
        },
        qobuzAccountConnector = mockk(relaxed = true),
        likePreferences = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
        settingsDeepLinkController = mockk(relaxed = true),
        crashFileStore = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        crossfadePreference = mockk(relaxed = true),
        databaseBackupManager = mockk(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        homeDiscoveryPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        homeSectionsPreference = mockk(relaxed = true),
        listenBrainzPreference = mockk(relaxed = true),
        listenBrainzApiClient = mockk(relaxed = true),
        listenSinkCoordinator = mockk(relaxed = true),
        listenSubmissionDao = mockk(relaxed = true),
    )

    @Test fun `refreshStorageUsage requests a fresh filesystem calculation`() {
        val vm = newVm()

        vm.refreshStorageUsage()

        verify(exactly = 1) { librarySizeHolder.refresh() }
    }

    @Test fun `qbdlxExpired is true when no lossless path is configured`() = runTest {
        val vm = newVm(losslessConfigured = false)
        // WhileSubscribed: the flow only runs while collected.
        val job = launch { vm.qbdlxExpired.collect {} }
        advanceUntilIdle()
        assertThat(vm.qbdlxExpired.value).isTrue()
        job.cancel()
    }

    @Test fun `qbdlxExpired is false once any lossless path is configured`() = runTest {
        val vm = newVm(losslessConfigured = true)
        val job = launch { vm.qbdlxExpired.collect {} }
        advanceUntilIdle()
        assertThat(vm.qbdlxExpired.value).isFalse()
        job.cancel()
    }
}
