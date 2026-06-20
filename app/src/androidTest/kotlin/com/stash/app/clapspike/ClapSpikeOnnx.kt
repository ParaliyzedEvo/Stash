package com.stash.app.clapspike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * SPIKE (spike/clap-on-device) — throwaway. Minimal ONNX Runtime wrapper over
 * the two int8-quantized CLAP encoders exported by the desktop half of this
 * spike. Wraps a single shared [OrtEnvironment] plus an audio + a text session.
 *
 * ONNX contract (fixed by the export step):
 *  - audio model: input  tensor "pcm"          float32 [1, 480000]  (10s @ 48k mono)
 *                 output tensor "embed"        float32 [1, 512]
 *  - text  model: inputs "input_ids" + "attention_mask"  int64 [1, seqLen]
 *                 output tensor "embed"        float32 [1, 512]
 *
 * The constructor takes FILE PATHS to the two .onnx models (the test extracts
 * them from the androidTest APK assets to cache files first). ORT memory-maps a
 * model opened by path into NATIVE memory, so a large (e.g. 285 MB fp32) model
 * never has to fit in the Dalvik/ART Java heap — loading the bytes as a Java
 * `byte[]` blows the app heap (OutOfMemoryError) for models this size.
 */
class ClapSpikeOnnx(audioModelPath: String, textModelPath: String) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val audioSession: OrtSession = env.createSession(audioModelPath, OrtSession.SessionOptions())
    private val textSession: OrtSession = env.createSession(textModelPath, OrtSession.SessionOptions())

    /** Embed one 10s window: [pcm] length must be 480000. Returns a 512-dim vector. */
    fun embedAudio(pcm: FloatArray): FloatArray {
        require(pcm.size == AUDIO_SAMPLES) { "Expected $AUDIO_SAMPLES samples, got ${pcm.size}" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, AUDIO_SAMPLES.toLong())).use { tensor ->
            audioSession.run(mapOf(AUDIO_INPUT to tensor)).use { result ->
                return readEmbed(result)
            }
        }
    }

    /**
     * Embed a tokenized text query. [ids] + [mask] are the `input_ids` and
     * `attention_mask` arrays from query_tokens.json (same length). Returns 512-dim.
     */
    fun embedTextTokens(ids: LongArray, mask: LongArray): FloatArray {
        require(ids.size == mask.size) { "ids/mask length mismatch: ${ids.size} vs ${mask.size}" }
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                val inputs = mapOf(TEXT_IDS to idsTensor, TEXT_MASK to maskTensor)
                textSession.run(inputs).use { result ->
                    return readEmbed(result)
                }
            }
        }
    }

    /** Pull the single 512-dim float output out of a session result. */
    private fun readEmbed(result: OrtSession.Result): FloatArray {
        val tensor = result[0].value
        // ONNX shape [1, 512] surfaces as Array<FloatArray> (a 1-row matrix).
        @Suppress("UNCHECKED_CAST")
        return when (tensor) {
            is Array<*> -> (tensor[0] as FloatArray).copyOf()
            is FloatArray -> tensor.copyOf()
            else -> throw IllegalStateException("Unexpected output type: ${tensor::class.java}")
        }
    }

    override fun close() {
        audioSession.close()
        textSession.close()
        env.close()
    }

    companion object {
        const val AUDIO_SAMPLES = 480_000 // 10s @ 48 kHz mono
        private const val AUDIO_INPUT = "pcm"
        private const val TEXT_IDS = "input_ids"
        private const val TEXT_MASK = "attention_mask"

        /** Cosine similarity of two equal-length vectors. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vector length mismatch: ${a.size} vs ${b.size}" }
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0.0) 0f else (dot / denom).toFloat()
        }
    }
}
