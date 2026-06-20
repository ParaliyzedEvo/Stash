package com.stash.app.amzgate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.audio.FFmpegBridgeImpl
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE (one-shot, on-device): confirms the bundled youtubedl-android ffmpeg
 * binary accepts the `-decryption_key` option — the linchpin of the amz
 * client-side CMAF decrypt recipe
 * (`ffmpeg -decryption_key <hex> -i <enc> -c copy out.flac`).
 *
 * We don't need a real encrypted CMAF file to answer the gate question:
 * ffmpeg parses its argument list BEFORE touching any input, so feeding a
 * nonexistent input still exercises flag parsing. If the build lacks the
 * option, ffmpeg prints `Unrecognized option 'decryption_key'` +
 * `Error splitting the argument list` and never reaches input handling. If
 * the option IS supported, parsing succeeds and ffmpeg fails LATER, opening
 * the missing input ("No such file or directory" / "Error opening input").
 *
 * So: absence of "Unrecognized option" == the gate passes. Run via
 * `:app:connectedDebugAndroidTest` (or a single-test connected run) with a
 * device attached. Throwaway probe — not part of the shipping surface.
 */
@RunWith(AndroidJUnit4::class)
class FFmpegDecryptionKeyGateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun initFfmpeg() {
        // Unpack the ffmpeg binary + its support libs the same way the app does
        // at startup (YtDlpManager.initialize). Idempotent; safe to call here.
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    @Test
    fun bundledFfmpeg_accepts_decryptionKey_option() = runBlocking {
        val bridge = FFmpegBridgeImpl(context)

        // 16-byte AES-128 key in hex (sample from a live /api/track drm.key).
        // Input deliberately does not exist — we're probing arg parsing only.
        val key = "8164fe2db5ebd498c8265b3e873462c1"
        val missingInput = File(context.cacheDir, "amz_gate_nonexistent_input.cmaf").absolutePath
        val output = File(context.cacheDir, "amz_gate_out.flac").absolutePath

        val stderr = bridge.runWithStderrCapture(
            listOf("-y", "-decryption_key", key, "-i", missingInput, "-c", "copy", output),
        )

        Log.i(TAG, "ffmpeg -decryption_key probe stderr:\n$stderr")

        assertWithMessage(
            "ffmpeg produced no stderr — binary may not have run at all",
        ).that(stderr).isNotEmpty()

        val unrecognized = stderr.contains("Unrecognized option", ignoreCase = true) ||
            stderr.contains("Error splitting the argument list", ignoreCase = true) ||
            stderr.contains("Option decryption_key", ignoreCase = true)

        assertWithMessage(
            "Bundled ffmpeg REJECTED -decryption_key — client-side amz decrypt is NOT " +
                "viable with this build. Full stderr:\n$stderr",
        ).that(unrecognized).isFalse()
    }

    private companion object {
        const val TAG = "AmzDecryptGate"
    }
}
