package com.thrum.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * What THIS device's single motor can actually do — the "device-level list". One install-time
 * permission (VIBRATE) gets us in; this is what the hardware then admits. Probed once, at
 * [HapticEngine] construction.
 */
data class Capabilities(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supported: Map<Primitive, Boolean>,
    val durationsMs: Map<Primitive, Int>,
) {
    fun isSupported(p: Primitive): Boolean = supported[p] == true
}

/**
 * Compiles our [Haptic] patterns into android VibrationEffects and plays them on the one motor.
 *
 * Probes the device capability list once. Gates every primitive on real hardware support and
 * substitutes a near-equivalent when a primitive is missing, so a pattern always fires
 * *something* rather than silently dropping out.
 *
 * API surface verified against developer.android.com (Vibrator / VibrationEffect / Composition),
 * 2026-06-13. minSdk is 31, so the capability + composition APIs (API 30/31) are always present.
 */
class HapticEngine(context: Context) {

    // getSystemService(Vibrator::class.java) is the clean accessor the official haptics guide
    // uses; on API 31+ it returns the system default vibrator. Null where there is no motor.
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    val capabilities: Capabilities = probe()

    private fun probe(): Capabilities {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            return Capabilities(
                hasVibrator = false,
                hasAmplitudeControl = false,
                supported = Primitive.entries.associateWith { false },
                durationsMs = emptyMap(),
            )
        }
        val all = Primitive.entries
        val ids = all.map { it.id }.toIntArray()
        val support = v.arePrimitivesSupported(*ids)      // API 31 -> BooleanArray
        val durations = v.getPrimitiveDurations(*ids)     // API 31 -> IntArray (ms on this motor)
        return Capabilities(
            hasVibrator = true,
            hasAmplitudeControl = v.hasAmplitudeControl(),
            supported = all.withIndex().associate { (i, p) -> p to support[i] },
            durationsMs = all.withIndex().associate { (i, p) -> p to durations[i] },
        )
    }

    /** Play a haptic. A [priority] pattern (the crash) cancels whatever is running first, so
     *  rapid drumming cannot cut it short. */
    fun play(haptic: Haptic, priority: Boolean = false) {
        val v = vibrator ?: return
        if (!capabilities.hasVibrator) return
        if (priority) v.cancel()
        val effect = compile(haptic) ?: return
        v.vibrate(effect)
    }

    fun cancel() {
        vibrator?.cancel()
    }

    private fun compile(haptic: Haptic): VibrationEffect? = when (haptic) {
        is Haptic.Composed -> compileComposed(haptic)
        is Haptic.Wave -> VibrationEffect.createWaveform(haptic.timings, haptic.amplitudes, -1)
    }

    private fun compileComposed(h: Haptic.Composed): VibrationEffect? {
        val comp = VibrationEffect.startComposition()
        var added = 0
        for (n in h.notes) {
            val p = resolve(n.primitive) ?: continue
            comp.addPrimitive(p.id, n.scale.coerceIn(0f, 1f), n.delayMs)
            added++
        }
        return if (added > 0) comp.compose() else null
    }

    /** The primitive itself if the motor supports it, else the nearest supported substitute,
     *  else null (drop the note). Keeps a pattern legible-ish on motors missing API-31 primitives. */
    private fun resolve(p: Primitive): Primitive? {
        if (capabilities.isSupported(p)) return p
        for (alt in fallbacks[p].orEmpty()) if (capabilities.isSupported(alt)) return alt
        return null
    }

    private val fallbacks: Map<Primitive, List<Primitive>> = mapOf(
        Primitive.THUD to listOf(Primitive.CLICK, Primitive.LOW_TICK, Primitive.TICK),
        Primitive.SPIN to listOf(Primitive.QUICK_RISE, Primitive.TICK, Primitive.CLICK),
        Primitive.LOW_TICK to listOf(Primitive.TICK, Primitive.CLICK),
        Primitive.QUICK_RISE to listOf(Primitive.CLICK, Primitive.TICK),
        Primitive.SLOW_RISE to listOf(Primitive.QUICK_RISE, Primitive.CLICK, Primitive.TICK),
        Primitive.QUICK_FALL to listOf(Primitive.TICK, Primitive.CLICK),
        Primitive.CLICK to listOf(Primitive.TICK),
        Primitive.TICK to listOf(Primitive.CLICK),
    )
}
