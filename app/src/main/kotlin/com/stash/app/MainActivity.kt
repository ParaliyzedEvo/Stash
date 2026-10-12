package com.stash.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.stash.app.navigation.StashScaffold
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.data.share.ShareApiClient
import com.stash.core.data.share.ShareResult
import com.stash.core.data.weblink.SyncKeys
import com.stash.core.model.ThemeMode
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.ui.components.ListenTogetherRole
import com.stash.core.ui.components.LocalCreateTrackLink
import com.stash.core.ui.components.LocalListenTogetherRole
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import dagger.hilt.android.AndroidEntryPoint
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /** [pendingDeepLink] prefix for a shared-track link; the link (long, or short `/t/{id}`) follows. */
        const val DEEP_LINK_SHARED_TRACK_PREFIX = "shared_track:"

        /** [pendingDeepLink] prefix for a shared-mix link; the share id follows. */
        const val DEEP_LINK_SHARED_MIX_PREFIX = "shared_mix:"

        /** [pendingDeepLink] prefix for a Listen Together invite; the room code follows. */
        const val DEEP_LINK_LISTEN_PREFIX = "listen:"

        /** [pendingDeepLink] prefix for a Link Stash on the web pairing link (`https://stashfm.app/link#1.…`); the link follows. */
        const val DEEP_LINK_WEB_LINK_PREFIX = "weblink:"

        /** A pairing link is about 160 characters; anything far longer isn't one. */
        private const val MAX_WEB_LINK = 512

        /** Last theme the app rendered, read synchronously for the first frame; see [onCreate]. */
        private const val THEME_SNAPSHOT_PREFS = "theme_snapshot"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED_DARK = "amoled_dark"
        private const val KEY_UI_SCALE = "ui_scale"
    }

    @Inject
    lateinit var themePreference: ThemePreference

    @Inject
    lateinit var localImportCoordinator: LocalImportCoordinator

    @Inject
    lateinit var listenTogether: ListenTogetherController

    @Inject
    lateinit var homeSectionsPreference: HomeSectionsPreference

    @Inject
    lateinit var shareApiClient: ShareApiClient

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var likeCoordinator: LikeCoordinator

    /**
     * What every song Share sheet's "Stash link" uses to make a short link (via [LocalCreateTrackLink]). Null on any
     * failure: the sheet then shares the long link, so sharing still works.
     */
    private val createTrackLink: suspend (SharedTrack) -> String? = { track ->
        when (val r = shareApiClient.createTrackLink(track)) {
            is ShareResult.Ok -> r.value.url
            else -> { Log.w("ShareTrack", "short link failed ($r); sharing the long link"); null }
        }
    }

    /**
     * Pending deep-link target read from the launch / new-intent extras.
     * Compose observes this via [StashScaffold]'s `pendingDeepLink`
     * parameter, navigates once, then calls back to clear it via
     * [clearPendingDeepLink].
     */
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val themeModeFlow: Flow<ThemeMode> = themePreference.themeMode

        // The theme's first frames used to render with SYSTEM / not-AMOLED until
        // the DataStore read landed (~0.4 s measured on a Pixel 5), so a
        // light-theme user on a dark-mode phone saw dark flash to light, and an
        // AMOLED user grey to black, on every cold start. Start from the last
        // theme rendered, kept in plain SharedPreferences like the start tab.
        val themeSnapshot = getSharedPreferences(THEME_SNAPSHOT_PREFS, MODE_PRIVATE)
        val initialThemeMode = themeSnapshot.getString(KEY_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        val initialAmoledDark = themeSnapshot.getBoolean(KEY_AMOLED_DARK, false)
        val initialUiScale = themeSnapshot.getFloat(KEY_UI_SCALE, 1f)

        setContent {
            val themeMode by themeModeFlow.collectAsState(initial = initialThemeMode)
            val amoledDark by themePreference.amoledDark.collectAsState(initial = initialAmoledDark)
            val uiScale by themePreference.uiScale.collectAsState(initial = initialUiScale)
            LaunchedEffect(themeMode, amoledDark, uiScale) {
                themeSnapshot.edit()
                    .putString(KEY_THEME_MODE, themeMode.name)
                    .putBoolean(KEY_AMOLED_DARK, amoledDark)
                    .putFloat(KEY_UI_SCALE, uiScale)
                    .apply()
            }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }

            val together by listenTogether.state.collectAsState()
            val role = (together as? ListenTogetherState.InRoom)
                ?.let { if (it.isHost) ListenTogetherRole.HOST else ListenTogetherRole.LISTENER }
                ?: ListenTogetherRole.NONE
            // Session notices ("Suggested to the host", …) toast on whichever screen is showing.
            val lifecycleOwner = LocalLifecycleOwner.current
            val context = LocalContext.current
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    listenTogether.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            }
            // Player-level notices, on whichever screen is showing. They used to be forwarded
            // into each NowPlayingViewModel's own message flow, which only Now Playing or the
            // open queue sheet collected: a SharedFlow with no subscriber drops what it emits,
            // so a song tapped offline from Library ("Couldn't play this track right now."),
            // "End of offline Mix" and the streaming halt did nothing visible anywhere else.
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // The halt flow replays its last event (replay = 1): skip that one, or every
                    // return to the app would re-announce an outage that is long over.
                    val halts = playerRepository.streamingHaltedEvents
                    merge(
                        playerRepository.userMessages,
                        halts.drop(halts.replayCache.size)
                            .map { "Streaming is failing — try a downloaded track or check your connection" },
                        likeCoordinator.mirrorFailures,
                    ).collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            }
            // Open Stash on (#428): the tab the NavHost starts on. Saved across recreation so a
            // restored back stack always sits on the graph it was saved with.
            val startTab = rememberSaveable { homeSectionsPreference.startTabNow }
            val baseDensity = LocalDensity.current
            StashTheme(darkTheme = darkTheme, amoled = amoledDark) {
                CompositionLocalProvider(
                    // Scaling density (not just fontScale) resizes dp and sp together.
                    LocalDensity provides Density(baseDensity.density * uiScale, baseDensity.fontScale),
                    LocalListenTogetherRole provides role,
                    LocalCreateTrackLink provides createTrackLink,
                ) {
                    StashScaffold(
                        pendingDeepLink = pendingDeepLink.value,
                        onDeepLinkConsumed = { pendingDeepLink.value = null },
                        startTab = startTab,
                    )
                }
            }
        }

        // Handle the initial intent (share target cold-start path,
        // notification deep-link cold-start path, etc). Not on a restore
        // (process death / config change) or a Recents relaunch: those
        // replay the original intent, and the nav back stack is already restored.
        val relaunch = savedInstanceState != null ||
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!relaunch) {
            handleShareIntent(intent)
            handleDeepLinkIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Reads [CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET] from the
     * launch intent and stashes it for Compose to consume on the next
     * frame. No-op when the extra is absent (the normal launcher case).
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent == null) return

        // Shared mix / track links (https App Links on stashfm.app or the old workers.dev host, and legacy
        // stash://track). Spec §6.
        if (intent.action == Intent.ACTION_VIEW) {
            // A pairing code opened by the camera app (or adb): its secrets are in the fragment. Only while the feature is
            // on (its intent filter is on an alias that release builds keep disabled); the confirm sheet checks the link.
            val data = intent.data?.toString()
            if (BuildConfig.WEB_LINK_ENABLED && data != null && data.startsWith(SyncKeys.LINK_BASE + "#")) {
                pendingDeepLink.value = DEEP_LINK_WEB_LINK_PREFIX + data.take(MAX_WEB_LINK)
                intent.data = null
                return
            }
            val handled = when (val parsed = ShareLinks.parse(intent.data?.toString())) {
                is ShareLinks.Parsed.Mix -> {
                    pendingDeepLink.value = DEEP_LINK_SHARED_MIX_PREFIX + parsed.shareId
                    true
                }
                is ShareLinks.Parsed.Track -> {
                    pendingDeepLink.value = DEEP_LINK_SHARED_TRACK_PREFIX + ShareLinks.trackUrl(parsed.track) // bounded, normalised
                    true
                }
                is ShareLinks.Parsed.TrackRef -> {
                    // A short song link: the card fetches the song by its id.
                    pendingDeepLink.value = DEEP_LINK_SHARED_TRACK_PREFIX + ShareLinks.trackShortUrl(parsed.id)
                    true
                }
                is ShareLinks.Parsed.Room -> {
                    pendingDeepLink.value = DEEP_LINK_LISTEN_PREFIX + parsed.code
                    true
                }
                null -> false
            }
            if (handled) { intent.data = null; return } // don't reprocess on config change
        }

        val target = intent.getStringExtra(CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET)
            ?: return
        // Only the one target Stash's own notification sends. This activity is
        // exported, so any app can set the extra: passed through as-is, a
        // "weblink:"/"listen:"/"shared_track:" value skipped every check the VIEW
        // path above applies (the feature switch, the link prefix, the length
        // cap, the room-code shape) on its way to the confirm screens.
        if (target == CaptchaExpiredNotifier.DEEP_LINK_TARGET) pendingDeepLink.value = target
        // Clear the extra so a config change doesn't re-trigger the
        // navigate. setIntent() above already replaces the activity's
        // intent reference; this just guards against the same Intent
        // instance being processed twice.
        intent.removeExtra(CaptchaExpiredNotifier.INTENT_EXTRA_NAV_TARGET)
    }

    /**
     * Extracts audio URIs from a share-sheet intent and hands them to the
     * [LocalImportCoordinator]. Silently ignores non-share intents so the
     * normal launcher flow is untouched.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ).orEmpty().toList()
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            localImportCoordinator.start(uris)
        }
    }
}
