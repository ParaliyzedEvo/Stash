package com.stash.core.data.navigation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cross-feature handoff for "switch to the Library tab and land on a
 * specific sub-tab". Same shape and rationale as
 * [SettingsDeepLinkController]: `LibraryRoute` is a `data object` and the
 * bottom bar matches tabs by route type, so no route argument — the caller
 * queues a one-shot focus here, then performs a normal tab switch, and
 * LibraryScreen reads + clears it on entry.
 */
@Singleton
class LibraryDeepLinkController @Inject constructor() {
    private val _focus = MutableStateFlow<LibraryFocus?>(null)

    /** Caller-side: queue a focus request just before switching tabs. */
    fun request(focus: LibraryFocus) {
        _focus.value = focus
    }

    /** Library-side: read the pending focus (if any) and clear it atomically. */
    fun consume(): LibraryFocus? {
        var taken: LibraryFocus? = null
        _focus.update { current ->
            taken = current
            null
        }
        return taken
    }
}

/** Library surfaces a deep-link can target. */
enum class LibraryFocus {
    LIKED,
}
