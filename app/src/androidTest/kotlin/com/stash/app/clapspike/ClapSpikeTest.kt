package com.stash.app.clapspike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream

/**
 * SPIKE (spike/clap-on-device) — THROWAWAY measurement harness. Do NOT merge.
 *
 * Authored but NOT run here: the model + fixture assets under `clapspike/` come
 * from the desktop Python half of this spike (make_fixtures.py) and do not exist
 * in the repo. This test COMPILES; the human runs `connectedAndroidTest` on a
 * physical Pixel once the assets are dropped into `app/src/androidTest/assets/`.
 *
 * It measures the three exit criteria for "CLAP embeddings on-device":
 *   SPEED   — median per-window embedAudio() ms, and median*3 (per-track, since
 *             production mean-pools 3 windows).
 *   SIGNAL  — same-genre vs cross-genre mean cosine separation.
 *   FIDELITY— every on-device vector matches its desktop ground-truth > 0.999.
 *
 * ── Expected asset layout under `clapspike/` (the androidTest APK's assets) ──
 *   clap_audio.int8.onnx        audio encoder (input "pcm" f32[1,480000] -> "embed" f32[1,512])
 *   clap_text.int8.onnx         text  encoder (inputs "input_ids"/"attention_mask" i64[1,N] -> "embed" f32[1,512])
 *   <clip>.wav                  one 48kHz/mono/PCM16 10s clip per ground_truth "clips" entry
 *   ground_truth.json           {
 *                                 "clips":   ["bluesy_riff", "techno_loop", ...],   // file stems (no .wav)
 *                                 "genres":  { "bluesy_riff": "blues", ... },        // clip -> genre label
 *                                 "vectors": { "bluesy_riff": [512 floats], ... }     // desktop int8 embedding
 *                               }
 *   query_tokens.json           {
 *                                 "queries": [
 *                                   { "text": "energetic techno",
 *                                     "input_ids":      [101, ...],
 *                                     "attention_mask": [1, ...] },
 *                                   ...  // 3 queries
 *                                 ]
 *                               }
 *
 * NOTE on the 0.999 gate: ground truth is computed with int8 kernels on x86,
 * the device runs int8 kernels on ARM. If 0.999 proves too tight on a real run
 * that still cleanly separates genres, the human may relax this to ~0.99 and
 * record the chosen value in findings.
 */
@RunWith(AndroidJUnit4::class)
class ClapSpikeTest {

    private val tag = TAG

    // Use the *test* (androidTest APK) context — that's where clapspike/ assets live.
    private val context: Context = InstrumentationRegistry.getInstrumentation().context

    // The *target* app context (com.stash.app.debug) — its data/cache dir is
    // fully materialized (the app is installed), unlike the test APK's, so model
    // extraction writes here.
    private val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measureClapOnDevice() {
        // ── 1. Load models + fixtures ───────────────────────────────────────
        // Extract the (large) model assets to cache files and open by PATH, so
        // ORT mmaps them into native memory instead of forcing a multi-hundred-MB
        // Java byte[] through the app heap (OutOfMemoryError otherwise).
        val audioModelPath = extractAssetToCache("clapspike/clap_audio.int8.onnx")
        val textModelPath = extractAssetToCache("clapspike/clap_text.int8.onnx")

        val groundTruth = JSONObject(readAssetText("clapspike/ground_truth.json"))
        val clipNames = groundTruth.getJSONArray("clips").let { arr ->
            List(arr.length()) { arr.getString(it) }
        }
        val genresJson = groundTruth.getJSONObject("genres")
        val vectorsJson = groundTruth.getJSONObject("vectors")
        val genres = clipNames.associateWith { genresJson.getString(it) }
        val groundVectors = clipNames.associateWith { parseVector(vectorsJson.getJSONArray(it)) }

        Log.i(tag, "Loaded ${clipNames.size} clips: $clipNames")
        Log.i(tag, "Genres: $genres")

        ClapSpikeOnnx(audioModelPath, textModelPath).use { onnx ->
            // Decode all WAV clips up front (decode cost excluded from timing).
            val pcmByClip = clipNames.associateWith { name ->
                readAsset("clapspike/$name.wav").let { WavReader.read(it.inputStream()) }
            }
            pcmByClip.forEach { (name, pcm) ->
                require(pcm.size == ClapSpikeOnnx.AUDIO_SAMPLES) {
                    "$name decoded to ${pcm.size} samples, expected ${ClapSpikeOnnx.AUDIO_SAMPLES}"
                }
            }

            // ── 2. Warm up + time each embedAudio, compute median ──────────
            // One throwaway run excludes one-time ORT init / graph-warm cost.
            onnx.embedAudio(pcmByClip.getValue(clipNames.first()))

            val deviceVectors = LinkedHashMap<String, FloatArray>()
            val perClipMs = LinkedHashMap<String, Double>()
            for (name in clipNames) {
                val start = SystemClock.elapsedRealtimeNanos()
                val vec = onnx.embedAudio(pcmByClip.getValue(name))
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                deviceVectors[name] = vec
                perClipMs[name] = ms
                Log.i(tag, "embedAudio[$name] = %.1f ms".format(ms))
            }
            val medianMs = median(perClipMs.values.toList())
            Log.i(tag, "SPEED: median window = %.1f ms ; projected per-track (median*3) = %.1f ms"
                .format(medianMs, medianMs * 3))

            // ── 3. NxN cosine matrix + same vs cross genre means ───────────
            Log.i(tag, "Cosine matrix (rows/cols in clip order; labels = genre):")
            Log.i(tag, "          " + clipNames.joinToString(" ") { genres.getValue(it).take(6).padEnd(7) })
            var sameSum = 0.0; var sameCount = 0
            var crossSum = 0.0; var crossCount = 0
            for (a in clipNames) {
                val row = StringBuilder("%-9s".format(genres.getValue(a).take(8)))
                for (b in clipNames) {
                    val c = ClapSpikeOnnx.cosine(deviceVectors.getValue(a), deviceVectors.getValue(b))
                    row.append(" %6.3f ".format(c))
                    if (a != b) {
                        if (genres.getValue(a) == genres.getValue(b)) { sameSum += c; sameCount++ }
                        else { crossSum += c; crossCount++ }
                    }
                }
                Log.i(tag, row.toString())
            }
            val sameMean = if (sameCount > 0) sameSum / sameCount else Double.NaN
            val crossMean = if (crossCount > 0) crossSum / crossCount else Double.NaN
            Log.i(tag, "SIGNAL: mean same-genre cosine = %.4f ; mean cross-genre cosine = %.4f ; separation = %.4f"
                .format(sameMean, crossMean, sameMean - crossMean))

            // ── 4. Fidelity assertion (the one real gate) ──────────────────
            // 1e-2 abs tolerance noted in plan for any matrix-entry comparisons;
            // the cosine-to-ground-truth gate is the binding correctness check.
            for (name in clipNames) {
                val sim = ClapSpikeOnnx.cosine(deviceVectors.getValue(name), groundVectors.getValue(name))
                Log.i(tag, "fidelity[$name] device vs ground-truth cosine = %.5f".format(sim))
                assertThat(sim).isGreaterThan(0.999f)
            }

            // ── 5. Text queries: query x clip cosines + top-3 per query ────
            val queries = JSONObject(readAssetText("clapspike/query_tokens.json")).getJSONArray("queries")
            for (q in 0 until queries.length()) {
                val query = queries.getJSONObject(q)
                val text = query.optString("text", "query#$q")
                val ids = parseLongArray(query.getJSONArray("input_ids"))
                val mask = parseLongArray(query.getJSONArray("attention_mask"))
                val qVec = onnx.embedTextTokens(ids, mask)

                val scored = clipNames.map { it to ClapSpikeOnnx.cosine(qVec, deviceVectors.getValue(it)) }
                scored.forEach { (name, c) ->
                    Log.i(tag, "query[\"$text\"] x $name (${genres.getValue(name)}) = %.4f".format(c))
                }
                val top3 = scored.sortedByDescending { it.second }.take(3)
                    .joinToString(", ") { "${it.first}(${genres.getValue(it.first)})=%.3f".format(it.second) }
                Log.i(tag, "query[\"$text\"] TOP-3: $top3")
            }

            // ── 6. One-line PASS/FAIL summary per exit criterion ───────────
            // Size is logged by the human from the desktop export step.
            Log.i(tag, "SUMMARY SPEED: median window %.1f ms (per-track ~%.1f ms)".format(medianMs, medianMs * 3))
            val separation = sameMean - crossMean
            val signalVerdict = if (separation > 0.0) "PASS" else "FAIL"
            Log.i(tag, "SUMMARY SIGNAL [$signalVerdict]: same %.4f vs cross %.4f (separation %.4f)"
                .format(sameMean, crossMean, separation))
            Log.i(tag, "SUMMARY FIDELITY: all ${clipNames.size} clips passed > 0.999 cosine-to-ground-truth gate")
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readAsset(path: String): ByteArray =
        context.assets.open(path).use(InputStream::readBytes)

    /**
     * Stream an asset to a cache file and return its absolute path. Used for the
     * large .onnx models so ORT can mmap them by path (native memory) instead of
     * holding the whole model in a Java byte[] (heap OOM for big fp32 models).
     */
    private fun extractAssetToCache(path: String): String {
        targetContext.cacheDir.mkdirs()
        val outFile = java.io.File(targetContext.cacheDir, path.substringAfterLast('/'))
        context.assets.open(path).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        }
        return outFile.absolutePath
    }

    private fun readAssetText(path: String): String =
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parseVector(arr: org.json.JSONArray): FloatArray =
        FloatArray(arr.length()) { arr.getDouble(it).toFloat() }

    private fun parseLongArray(arr: org.json.JSONArray): LongArray =
        LongArray(arr.length()) { arr.getLong(it) }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    companion object {
        private const val TAG = "ClapSpike"
    }
}
