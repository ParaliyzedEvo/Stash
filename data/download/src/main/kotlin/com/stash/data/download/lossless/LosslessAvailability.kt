package com.stash.data.download.lossless

import com.stash.data.download.lossless.arcod.ArcodCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The ONE place that answers "is lossless available, and whose is it?" — three
 * as a Flow (for Home's combine) each with a suspend getter (for the download
 * pipeline), plus one per-call check ([fileUrlAvailableNow] — relay cooldowns are
 * in-memory and not observable). Defined once so the source, the download
 * deferral reason and the Home banner can never disagree.
 *
 *  - [qbdlxEnabled]       BYO login || relay configured || custom endpoint set
 *  - [fileUrlAvailableNow] login LIVE (not merely present) || custom endpoint not
 *                         cooled || any config relay not cooled — per call
 *  - [anyConfigured]      qbdlxEnabled || ARCOD connected  → download deferral reason
 *  - [anyUserOwned]       BYO login || custom endpoint || ARCOD → the Home banner
 *                         (a dead PUBLIC relay must not hide the "connect your
 *                         own account" banner — that is the outage it exists for)
 */
@Singleton
class LosslessAvailability @Inject constructor(
    private val credentialStore: QbdlxCredentialStore,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
    private val relayClient: LosslessRelayClient,
    private val arcod: ArcodCredentialStore,
) {
    // ponytail: config.relays is empty until loadCached() completes, so a tap in the
    // first tens of ms after a cold start can see false and fall to the next rung once.
    val qbdlxEnabled: Flow<Boolean> =
        combine(credentialStore.hasLogin, config.relays, prefs.customLosslessEndpoint) { login, relays, custom ->
            login || relays.isNotEmpty() || custom != null
        }

    suspend fun qbdlxEnabledNow(): Boolean = qbdlxEnabled.first()

    /** Point-in-time: is there a file-URL path that is not cooled right now? */
    suspend fun fileUrlAvailableNow(): Boolean {
        if (credentialStore.loginLive()) return true
        prefs.customLosslessEndpointNow()?.let { if (!relayClient.isCooled(it)) return true }
        return config.relays.value.any { !relayClient.isCooled(it.base) }
    }

    // ponytail: anyConfigured / anyUserOwned have no consumer yet — Plan A2 (download
    // deferral reason) and Plan C (Home banner) are what read them.
    val anyConfigured: Flow<Boolean> = combine(qbdlxEnabled, arcod.accessToken) { q, a -> q || a != null }
    suspend fun anyConfiguredNow(): Boolean = anyConfigured.first()

    val anyUserOwned: Flow<Boolean> =
        combine(credentialStore.hasLogin, prefs.customLosslessEndpoint, arcod.accessToken) { l, c, a ->
            l || c != null || a != null
        }
    suspend fun anyUserOwnedNow(): Boolean = anyUserOwned.first()
}
