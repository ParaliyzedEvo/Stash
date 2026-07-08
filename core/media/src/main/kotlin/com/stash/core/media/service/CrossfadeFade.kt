package com.stash.core.media.service

import kotlin.math.cos
import kotlin.math.sin

/** (outgoing, incoming) gain for ramp progress [t] in [0,1]; equal-power. */
fun equalPowerVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    val rad = c * (Math.PI.toFloat() / 2f)
    return cos(rad) to sin(rad)
}

/** Inputs to the [shouldArm] decision, evaluated each position-poll tick. */
data class ArmInputs(
    val enabled: Boolean,
    val repeatOne: Boolean,
    val hasResolvedNext: Boolean,
    val remainingMs: Long,
    val trackDurationMs: Long,
    val crossfadeMs: Long,
)

/** Arm the fade only when every condition holds (see spec §Trigger). */
fun shouldArm(i: ArmInputs): Boolean =
    i.enabled &&
        !i.repeatOne &&
        i.hasResolvedNext &&
        i.trackDurationMs > 2 * i.crossfadeMs &&
        i.remainingMs in 1..i.crossfadeMs
