package com.stash.core.media.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * Standard options provider for Google Cast SDK. Sets the default media
 * receiver application ID suitable for standard audio/video casting.
 */
class StashCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Stop the Cast receiver when the session ends so the device
            // doesn't keep playing after the app disconnects or is killed.
            .setStopReceiverApplicationWhenEndingSession(true)
            // Don't auto-resume old sessions — the app kills stale sessions
            // on startup and the user taps Cast to start a fresh one.
            .setResumeSavedSession(false)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
