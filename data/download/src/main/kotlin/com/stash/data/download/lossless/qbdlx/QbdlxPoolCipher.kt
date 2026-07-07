package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the bundled qbdlx token pool. The plaintext `token:country,...`
 * pool is encrypted at BUILD time (data/download/build.gradle.kts runs the same
 * scheme) and decrypted here at runtime, so `strings`/`dex-grep` on the released
 * APK don't surface the tokens — the casual attack, and literally how we found
 * the pool ourselves.
 *
 * NOT a security wall: the key ships in the app (assembled from fragments below),
 * so Frida / static analysis still recover it. Goal = raise cost past casual grep.
 *
 * ⚠️ TWO COPIES OF THIS SCHEME. The encrypt half is duplicated in
 * data/download/build.gradle.kts (Gradle can't depend on this module's classes).
 * If you change key derivation, IV size, tag length, or byte layout you MUST
 * change BOTH and regenerate FIXTURE_BLOB in QbdlxPoolCipherTest — otherwise
 * shipped builds embed a blob this runtime can't decrypt → silent empty pool.
 * Note the fixture test guards only THIS (runtime) half; the FP gate hashes the
 * PLAINTEXT so it won't notice a wrong encrypt key. The actual catches for a
 * build-side-only change are the runtime fp-mismatch Log.w (QbdlxModule) and the
 * mandatory on-device verify (empty pool → the Settings paste prompt).
 *
 * ponytail: fragment-concat key, not real key management — matches the
 * "past casual grep" bar; a real KMS is the deferred broker's job.
 */
object QbdlxPoolCipher {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKeySpec {
        val pass = "stash" + "-qbdlx-" + "pool-" + "v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray())
        return SecretKeySpec(digest, "AES")
    }

    /** Decrypt a base64(IV‖ciphertext‖GCM-tag) blob. Blank/malformed → "" (never throws). */
    fun decrypt(blob: String): String {
        if (blob.isBlank()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(blob)
            if (bytes.size <= IV_LEN) return ""
            val iv = bytes.copyOfRange(0, IV_LEN)
            val body = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            "" // malformed base64, bad tag, wrong key → empty pool → Settings paste path
        }
    }

    /**
     * Reference encrypt — MUST match data/download/build.gradle.kts's encryptPool.
     * Used by tests (and handy for regenerating the fixture). Java GCM appends the
     * 16-byte tag to the ciphertext, so the layout is IV ‖ (ciphertext‖tag).
     */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }
}
