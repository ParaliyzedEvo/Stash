package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the amz PoW derivation against a live HAR vector captured from
 * amz.squid.wtf on 2026-06-15. If this breaks, amz changed its captcha
 * scheme — re-capture a vector before "fixing" the solver.
 */
class AmzAltchaSolverTest {

    @Test
    fun `solve matches captured PBKDF2 vector`() {
        val sol = AmzAltchaSolver.solve(
            nonceHex = "f9f25960b45813de33fd107f596c3961",
            saltHex = "fa12ba0b892664a85bc038b9d370bdd6",
            keyPrefixHex = "0d0301ca60ab63b9e18c9dc2c288e183",
            cost = 1000,
            keyLength = 32,
        )

        assertThat(sol.counter).isEqualTo(563)
        assertThat(sol.derivedKey)
            .isEqualTo("0d0301ca60ab63b9e18c9dc2c288e183876d630ed7a0a2f8aea80e3642ee93bd")
    }
}
