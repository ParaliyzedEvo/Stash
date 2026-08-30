package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayMint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE seam that turns a Qobuz track id into a signed FLAC URL. Tries, in
 * order: the user's own connected account (signed locally — never spends relay
 * budget), the user's custom endpoint, then the relays from runtime config by
 * priority. Returns null when no path is available right now (the source then
 * falls to the next rung).
 *
 * BYO outcomes do not fall through on the resolve that discovers them: a dead
 * login is surfaced as [QbdlxResolveResult.TokenDead] and marked dead; for the
 * rest of its dead-cooldown the relays take over, then the login is re-probed.
 */
@Singleton
class QbdlxFileUrlRouter @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
    private val relayClient: LosslessRelayClient,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
) {
    suspend fun getFileUrl(trackId: Long, formatId: Int): QbdlxResolveResult? {
        val login = credentialStore.loginCredential()
        if (login != null && credentialStore.loginLive()) {
            val result = try {
                apiClient.getFileUrl(trackId, formatId, login.token)
            } catch (e: QbdlxAuthException) {
                QbdlxResolveResult.TokenDead
            }
            if (result is QbdlxResolveResult.TokenDead) {
                Log.w(TAG, "connected account rejected — marking dead")
                credentialStore.markDead(login.token)
            } else {
                credentialStore.recordAlive(login.token)
            }
            return result
        }

        val bases = buildList {
            prefs.customLosslessEndpointNow()?.let { add(it) }
            config.relays.value.forEach { add(it.base) }
        }.distinct()
        for (base in bases) {
            if (relayClient.isCooled(base)) continue
            when (val m = relayClient.mint(base, trackId, formatId)) {
                is RelayMint.Ok ->
                    // Same downgrade rule as the direct path: a lossy format_id (<6) is not a match.
                    return if (m.formatId < 6) QbdlxResolveResult.RegionLocked
                    else QbdlxResolveResult.Ok(m.url, "flac", m.bitDepth, m.sampleRateHz)
                RelayMint.NoMatch -> return QbdlxResolveResult.RegionLocked
                RelayMint.Unavailable -> Unit // cooled by the client; try the next base
            }
        }
        return null
    }

    private companion object { const val TAG = "QbdlxFileUrlRouter" }
}
