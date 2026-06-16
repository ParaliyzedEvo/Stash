package com.stash.data.download.lossless.amz

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Solves amz.squid.wtf's captcha proof-of-work.
 *
 * **Despite the shared "altcha" lineage with qobuz.squid.wtf, amz uses a
 * DIFFERENT derivation** and the squid [com.stash.data.download.lossless.squid.AltchaSolver]
 * does NOT work here. amz's challenge `algorithm` is `"PBKDF2/SHA-256"` — real
 * PBKDF2-HMAC-SHA256, not squid's iterated-truncated-SHA256 chain. Reversed
 * from a live HAR vector (locked in `AmzAltchaSolverTest`):
 *
 * ```
 * derivedKey = PBKDF2-HMAC-SHA256(
 *     password   = nonceBytes || uint32_BE(counter),
 *     salt       = saltBytes,
 *     iterations = cost,
 *     dkLen      = keyLength bytes)
 * ```
 *
 * A `counter` is the solution when `derivedKey` starts with `keyPrefix`. The
 * server picked a small counter, so a linear scan from 0 finds it quickly
 * (the captured vector solved at counter=563).
 *
 * Pure JVM (`Mac`/HMAC only, no Android imports) so it runs in host unit tests
 * and on-device alike.
 */
internal object AmzAltchaSolver {

    data class Solution(val counter: Int, val derivedKey: String)

    /**
     * Brute-force the counter whose PBKDF2 derived key starts with
     * [keyPrefixHex]. All hex inputs are the verbatim values from amz's
     * `/api/captcha/challenge` `parameters` block.
     *
     * @throws IllegalStateException if no counter <= [maxCounter] matches
     *   (should never happen for a well-formed challenge — the cap is a safety
     *   net against pathological server input).
     */
    fun solve(
        nonceHex: String,
        saltHex: String,
        keyPrefixHex: String,
        cost: Int,
        keyLength: Int,
        maxCounter: Int = 1_000_000,
    ): Solution {
        val nonce = hexToBytes(nonceHex)
        val salt = hexToBytes(saltHex)
        val prefix = hexToBytes(keyPrefixHex)
        val iterations = cost.coerceAtLeast(1)

        // Reuse one password buffer (nonce || uint32_BE(counter)), overwriting
        // only the trailing 4 counter bytes each iteration.
        val pwd = ByteArray(nonce.size + COUNTER_BYTES)
        System.arraycopy(nonce, 0, pwd, 0, nonce.size)

        var counter = 0
        while (counter <= maxCounter) {
            pwd[nonce.size] = (counter ushr 24).toByte()
            pwd[nonce.size + 1] = (counter ushr 16).toByte()
            pwd[nonce.size + 2] = (counter ushr 8).toByte()
            pwd[nonce.size + 3] = counter.toByte()

            val dk = pbkdf2HmacSha256(pwd, salt, iterations, keyLength)
            if (startsWith(dk, prefix)) return Solution(counter, bytesToHex(dk))
            counter++
        }
        error("AmzAltchaSolver: no solution under counter=$maxCounter")
    }

    /**
     * PBKDF2-HMAC-SHA256 over a RAW-BYTE password. Implemented directly with
     * [Mac] rather than `PBEKeySpec`/`SecretKeyFactory` because the JCE
     * `PBKDF2WithHmacSHA256` takes a `char[]` password and re-encodes it — our
     * password is arbitrary bytes (nonce + counter), which a char[] can't
     * round-trip. This matches Python's `hashlib.pbkdf2_hmac` byte-for-byte.
     */
    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        var offset = 0
        for (i in 1..blocks) {
            // U1 = HMAC(pwd, salt || INT_32_BE(blockIndex))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(),
                ),
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = mac.doFinal(u) // HMAC(pwd, U_{j-1}); key persists across doFinal
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, offset, hLen)
            offset += hLen
        }
        return out.copyOf(dkLen)
    }

    private fun startsWith(haystack: ByteArray, needle: ByteArray): Boolean {
        if (haystack.size < needle.size) return false
        for (i in needle.indices) if (haystack[i] != needle[i]) return false
        return true
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }

    private const val COUNTER_BYTES = 4
}
