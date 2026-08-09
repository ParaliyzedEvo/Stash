// DspChainRefresherTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [dspActivation] is the change signal for the mid-track pipeline re-apply:
 * equal signatures = no drain scheduled. These tests pin the boundary — stage
 * enable/unity crossings must change it, slider moves inside an active stage
 * must not (a drain per slider tick would stutter playback).
 */
class DspChainRefresherTest {

  private val loudOff = LoudnessState(enabled = false)

  @Test fun `everything off yields no active stages and no limiter`() {
    val a = dspActivation(EqState(), loudOff)
    assertThat(a).isEqualTo(DspActivation(preamp = false, eq = false, bass = false, loudness = false))
    assertThat(a.limiter).isFalse()
  }

  @Test fun `slider moves within an active stage do not change the signature`() {
    val at3 = dspActivation(EqState(enabled = true, gainsDb = floatArrayOf(3f, 0f, 0f, 0f, 0f)), loudOff)
    val at6 = dspActivation(EqState(enabled = true, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f)), loudOff)
    assertThat(at3).isEqualTo(at6)

    val bass5 = dspActivation(EqState(enabled = true, bassBoostDb = 5f), loudOff)
    val bass9 = dspActivation(EqState(enabled = true, bassBoostDb = 9f), loudOff)
    assertThat(bass5).isEqualTo(bass9)
  }

  @Test fun `enable flips and unity crossings change the signature`() {
    val off = dspActivation(EqState(), loudOff)
    val eqOn = dspActivation(EqState(enabled = true, gainsDb = floatArrayOf(3f, 0f, 0f, 0f, 0f)), loudOff)
    assertThat(eqOn).isNotEqualTo(off)

    val flat = dspActivation(EqState(enabled = true), loudOff)
    assertThat(flat).isEqualTo(off) // enabled-but-flat is unity: still no active stage

    val preamp = dspActivation(EqState(enabled = true, preampDb = -3f), loudOff)
    assertThat(preamp).isNotEqualTo(flat)

    val loudOn = dspActivation(EqState(), LoudnessState(enabled = true))
    assertThat(loudOn).isNotEqualTo(off)
  }

  @Test fun `any active stage requires the limiter`() {
    assertThat(dspActivation(EqState(enabled = true, preampDb = 2f), loudOff).limiter).isTrue()
    assertThat(dspActivation(EqState(enabled = true, bassBoostDb = 5f), loudOff).limiter).isTrue()
    assertThat(dspActivation(EqState(), LoudnessState(enabled = true)).limiter).isTrue()
  }
}
