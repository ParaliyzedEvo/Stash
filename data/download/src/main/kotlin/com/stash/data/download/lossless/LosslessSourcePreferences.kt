package com.stash.data.download.lossless

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.db.dao.DownloadQueueDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore for lossless-source preferences (priority order, min quality, etc). */
private val Context.losslessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_source_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists user preferences for the lossless-source resolver chain:
 *
 *  - Priority order (which source the registry tries first, second, etc.)
 *  - Minimum quality threshold (skip sources whose stated format is lower)
 *
 * Per-source enable/disable lives on the [LosslessSource] implementation
 * itself (typically gated on whether the user has provided required
 * credentials or a configured URL template). This preference store is
 * only for the cross-source ordering and threshold.
 *
 * Order is stored as a comma-joined string of source ids — survives across
 * app versions, tolerates unknown ids gracefully (registry filters them
 * out before resolving), and keeps the schema trivially debuggable.
 */
@Singleton
class LosslessSourcePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
) {
    private val priorityKey = stringPreferencesKey("priority_order")
    private val minQualityKey = stringPreferencesKey("min_quality")
    private val enabledKey = booleanPreferencesKey("enabled")
    private val qbdlxEnabledKey = booleanPreferencesKey("qbdlx_enabled")
    private val captchaCookieKey = stringPreferencesKey("squid_wtf_captcha_verified_at")
    private val captchaCookieSetAtKey = longPreferencesKey("squid_wtf_captcha_set_at_ms")
    private val bannerDismissedKey = booleanPreferencesKey("home_banner_dismissed")
    private val qualityTierKey = stringPreferencesKey("lossless_quality_tier")
    private val youtubeFallbackKey = booleanPreferencesKey("youtube_fallback_enabled")
    // Retained only so [purgeAntraCredentials] can delete the harvested
    // antra.hoshi.cfd session from existing installs; the antra source was
    // removed (see fix/remove-antra).
    private val antraSessionKey = stringPreferencesKey("antra_session_cookie")
    private val antraCfClearanceKey = stringPreferencesKey("antra_cf_clearance_cookie")
    private val antraUsernameKey = stringPreferencesKey("antra_username")

    /**
     * Master switch for the lossless-source pipeline. When false, the
     * download path skips the registry entirely and goes straight to
     * yt-dlp — same behaviour as before the lossless feature shipped.
     *
     * Defaults to true (v0.9.8+): fresh installs land lossless-ready;
     * existing v0.9.7 users who explicitly toggled it off keep their
     * saved value (DataStore preserves explicit writes). Users who
     * never opened the toggle pick up the new default — functionally
     * identical to v0.9.7 behaviour because the captcha is unverified
     * (silent yt-dlp/MP3 fallback via SquidWtfCaptchaInterceptor).
     *
     * The toggle lives on this preferences class (rather than its own
     * DataStore) so all lossless-related settings stay in one place
     * and the schema can evolve together.
     */
    val enabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    suspend fun enabledNow(): Boolean = enabled.first()

    /**
     * Per-source enable toggle for the qbdlx direct-Qobuz source. Defaults
     * to true — fresh installs land it ready (it has a bundled token pool).
     * Gates BOTH download ([QbdlxQobuzSource.isEnabled]) and streaming
     * ([QbdlxQobuzSource.isEnabledForStreaming]); turning it off blocks the
     * source everywhere. Mirrors [enabled]/[enabledNow]; no requeue side
     * effect (it's one source among several, not the master switch).
     */
    val qbdlxEnabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[qbdlxEnabledKey] ?: true
    }

    suspend fun qbdlxEnabledNow(): Boolean = qbdlxEnabled.first()

    suspend fun setQbdlxEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[qbdlxEnabledKey] = value }
    }

    suspend fun setEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[enabledKey] = value }
        // WAITING_FOR_LOSSLESS only makes sense under "lossless on +
        // fallback off". Disabling lossless releases all deferred rows
        // back to PENDING for the standard yt-dlp worker chain.
        if (!value) downloadQueueDao.requeueWaitingForLossless()
    }

    /**
     * When the lossless registry can't serve a track, whether to fall
     * through to the yt-dlp pipeline (m4a / Opus) instead of deferring
     * the download. Defaults to `false` (v0.9.17+) — strict FLAC is the
     * new contract.
     *
     * Existing v0.9.16 users who flip this to true match the previous
     * silent-fallback behaviour exactly; the legacy yt-dlp tier picker
     * (MAX/BEST/HIGH/NORMAL/LOW) governs the fallback's quality.
     */
    val youtubeFallbackEnabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[youtubeFallbackKey] ?: false
    }

    suspend fun youtubeFallbackEnabledNow(): Boolean = youtubeFallbackEnabled.first()

    suspend fun setYoutubeFallbackEnabled(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[youtubeFallbackKey] = value }
        // Enabling YouTube fallback means deferred rows can now be
        // satisfied via the legacy yt-dlp pipeline — release them back
        // to PENDING. Disabling fallback is a no-op for the queue.
        if (value) downloadQueueDao.requeueWaitingForLossless()
    }

    /**
     * Value of the `captcha_verified_at` cookie that qobuz.squid.wtf sets
     * after a successful ALTCHA solve. The download-music endpoint reads
     * this cookie (HttpOnly, ~30-min sliding window) to gate access; our
     * OkHttp client attaches it to every squid.wtf request via
     * [com.stash.data.download.lossless.squid.SquidWtfCaptchaInterceptor].
     *
     * Workflow until WebView automation lands:
     *   1. User opens qobuz.squid.wtf in their phone's browser
     *   2. Solves the captcha by clicking Download on any track
     *   3. Copies the cookie value (e.g. via a "Cookie Editor" extension)
     *   4. Pastes it into the Settings field that backs this preference
     *
     * Cookie expires after ~30 min server-side; user repastes when
     * downloads start failing. WebView automation will replace this
     * manual flow but the storage shape is the same.
     *
     * Stored as a plain string — it's not a security secret (HttpOnly
     * means the BROWSER doesn't expose it to JS, but server treats it
     * as a freely-pasteable token).
     */
    val captchaCookieValue: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[captchaCookieKey]?.takeIf { it.isNotBlank() }
    }

    /**
     * Wall-clock epoch-millis when the current captcha cookie was set.
     * Emits 0L when no cookie has ever been stored or the most recent
     * value was cleared. Used by SquidCookieAutoRefresher to derive
     * cookie age and schedule the next refresh ~25 min after set.
     */
    val captchaCookieSetAtMs: Flow<Long> = context.losslessDataStore.data.map { prefs ->
        prefs[captchaCookieSetAtKey] ?: 0L
    }

    suspend fun captchaCookieValueNow(): String? = captchaCookieValue.first()

    suspend fun setCaptchaCookieValue(value: String?) {
        val now = System.currentTimeMillis()
        context.losslessDataStore.edit { prefs ->
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) {
                prefs.remove(captchaCookieKey)
                prefs.remove(captchaCookieSetAtKey)
            } else {
                prefs[captchaCookieKey] = trimmed
                prefs[captchaCookieSetAtKey] = now
            }
        }
    }

    /**
     * One-shot cleanup for the removed antra source: deletes the harvested
     * antra.hoshi.cfd session cookie, cf_clearance cookie, and username from
     * existing installs so no stale login session lingers on disk. Called
     * once at startup (see StashApplication). No-op when the keys are absent.
     */
    suspend fun purgeAntraCredentials() {
        context.losslessDataStore.edit { prefs ->
            prefs.remove(antraSessionKey)
            prefs.remove(antraCfClearanceKey)
            prefs.remove(antraUsernameKey)
        }
    }

    /**
     * Whether the user has dismissed the "Try lossless audio" Home
     * banner. Once dismissed, the banner never shows again — same
     * forever-dismissed semantics as `LastFmSessionPreference.bannerDismissed`.
     *
     * Defaults to false. Only read by [com.stash.feature.home.HomeViewModel];
     * Settings has no UI for un-dismissing.
     */
    val bannerDismissed: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[bannerDismissedKey] ?: false
    }

    suspend fun setBannerDismissed(dismissed: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[bannerDismissedKey] = dismissed }
    }

    /**
     * User-selected lossless quality tier. Default behaviour:
     *   - Fresh installs (no `enabled` key written) → [LosslessQualityTier.HI_RES]
     *     (sensible-storage middle ground; ~70 MB / 4 min vs MAX's ~140 MB).
     *   - v0.9.8+ users with `enabled` explicitly true → [LosslessQualityTier.MAX]
     *     (matches v0.9.10 historical behaviour for users who opted in).
     *   - v0.9.8+ users with `enabled` explicitly false → [LosslessQualityTier.HI_RES]
     *     (they're not downloading lossless anyway; sensible default if they
     *     later flip the switch).
     *
     * Once the user picks a tier in Settings, [setQualityTier] writes the key
     * and this default logic is no longer consulted.
     */
    val qualityTier: Flow<LosslessQualityTier> = context.losslessDataStore.data.map { prefs ->
        prefs[qualityTierKey]
            ?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: defaultQualityTier(prefs)
    }

    suspend fun qualityTierNow(): LosslessQualityTier = qualityTier.first()

    suspend fun setQualityTier(tier: LosslessQualityTier) {
        context.losslessDataStore.edit { prefs -> prefs[qualityTierKey] = tier.name }
    }

    private fun defaultQualityTier(prefs: Preferences): LosslessQualityTier {
        val enabledExplicitlyTrue = prefs[enabledKey] == true
        return if (enabledExplicitlyTrue) LosslessQualityTier.MAX else LosslessQualityTier.HI_RES
    }

    /**
     * Emits the user's source-id priority order. Falls back to
     * [DEFAULT_PRIORITY] when no order has been explicitly saved —
     * fresh installs get a known, stable resolution order rather than
     * relying on Hilt registration order.
     */
    val priorityOrder: Flow<List<String>> = context.losslessDataStore.data.map { prefs ->
        prefs[priorityKey]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { DEFAULT_PRIORITY }
            ?: DEFAULT_PRIORITY
    }

    /** One-shot read for callers that want the current value without subscribing. */
    suspend fun priorityOrderNow(): List<String> = priorityOrder.first()

    suspend fun setPriorityOrder(order: List<String>) {
        context.losslessDataStore.edit { prefs ->
            prefs[priorityKey] = order.joinToString(",")
        }
    }

    /**
     * Minimum acceptable quality. Sources whose [SourceResult.format]
     * doesn't meet this threshold are skipped during resolve, so the
     * chain never accidentally swaps a user's AAC 128 for an AAC 128
     * from a different host. Defaults to [MinQuality.LOSSLESS] — opt-in
     * to lossy sources via Settings if you want them.
     */
    val minQuality: Flow<MinQuality> = context.losslessDataStore.data.map { prefs ->
        val raw = prefs[minQualityKey]
        raw?.let { runCatching { MinQuality.valueOf(it) }.getOrNull() }
            ?: MinQuality.LOSSLESS
    }

    suspend fun minQualityNow(): MinQuality = minQuality.first()

    suspend fun setMinQuality(value: MinQuality) {
        context.losslessDataStore.edit { prefs -> prefs[minQualityKey] = value.name }
    }

    /**
     * Threshold an [AudioFormat] must meet to be accepted by the chain.
     *
     *  - [LOSSLESS]: codec must be in [AudioFormat.LOSSLESS_CODECS]
     *  - [HIGH_LOSSY]: bitrate >= 256 kbps OR lossless
     *  - [ANY]: anything goes (lossy or lossless)
     */
    enum class MinQuality {
        LOSSLESS, HIGH_LOSSY, ANY;

        fun accepts(format: AudioFormat): Boolean = when (this) {
            LOSSLESS -> format.isLossless
            HIGH_LOSSY -> format.isLossless || format.bitrateKbps >= 256
            ANY -> true
        }
    }

    companion object {
        /**
         * Default priority order used by fresh installs (no DataStore
         * entry yet). Existing users with explicitly-saved priority
         * preserve their value.
         *
         * Order:
         * 1. qbdlx_qobuz — Qobuz Hi-Res FLAC via a direct www.qobuz.com call
         *    (MD5 request signing + a rotating token pool). Ranked FIRST: it's
         *    the fastest lossless path — plain Range-seekable FLAC, no proxy
         *    operator and no client-side decryption (unlike amz).
         * 2. squid_qobuz — Qobuz Hi-Res FLAC via qobuz.squid.wtf.
         * 3. kennyy_qobuz — Qobuz Hi-Res FLAC via qobuz.kennyy.com.br.
         * 4. arcod — Qobuz Hi-Res FLAC via arcod.xyz (per-user Supabase session).
         *    2–4 are currently PARKED (hosts down for us) — see
         *    [LosslessSourceRegistry.PARKED_SOURCE_IDS]; the code + this ranking
         *    stay so re-enabling is a one-line change when they recover.
         * 5. amz — Amazon Music FLAC via amz.squid.wtf. Ranked LAST: its stream
         *    path decrypts the whole file client-side (tens of seconds), so it's
         *    the slow, different-catalog fallback after every Qobuz source.
         */
        val DEFAULT_PRIORITY: List<String> = listOf(
            "qbdlx_qobuz",
            "squid_qobuz",
            "kennyy_qobuz",
            "amz",
        )
    }
}
