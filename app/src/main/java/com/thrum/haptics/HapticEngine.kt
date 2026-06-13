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
class HapticEngine(context: Context) : HapticSink {

    // getSystemService(Vibrator::class.java) is the clean accessor the official haptics guide
    // uses; on API 31+ it returns the system default vibrator. Null where there is no motor.
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    override val capabilities: Capabilities = probe()

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
    override fun play(haptic: Haptic, priority: Boolean) {
        val v = vibrator ?: return
        if (!capabilities.hasVibrator) return
        if (priority) v.cancel()
        val effect = compile(haptic) ?: return
        v.vibrate(effect)
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    private fun compile(haptic: Haptic): VibrationEffect? = when (haptic) {
        is Haptic.Composed -> compileComposed(haptic)
        is Haptic.Wave -> compileWave(haptic)
    }

    /**
     * Compile a [Haptic.Wave], gated on the motor's amplitude capability.
     *
     * On a motor WITH amplitude control: play the hand-drawn envelope as authored —
     * createWaveform(timings, amplitudes, repeat=-1) renders each segment at its amplitude.
     *
     * On a motor WITHOUT amplitude control: the official guidance is that playing an amplitude
     * waveform there makes the device "vibrate at the maximum amplitude for each positive entry in
     * the amplitude array" — i.e. the whole shaped tumble→slam envelope flattens to full-strength
     * on/off buzzes, an undifferentiated brrt, defeating the magnitude→richness map. The doc's
     * remedy is the on/off variant createWaveform(onOffTimings, repeat), where the timing array is
     * an OFF/ON sequence (index 0 = off duration, index 1 = on, index 2 = off, …). So we collapse the
     * envelope to its on/off SKELETON keyed on AMPLITUDE (positive = on, 0 = off), not on index
     * parity — merging consecutive same-state segments so the off/on slots stay correctly aligned
     * even for a wave that does not strictly alternate. This preserves the *rhythm* of the crash
     * (the legible structure: tumble pulses → slam) when its amplitude shaping cannot be rendered.
     * Verified: developer.android.com/develop/ui/views/haptics/custom-haptic-effects
     * ("Pattern with fallback": hasAmplitudeControl() gate + createWaveform(onOffTimings, ...);
     * "the ON/OFF pattern is actually specified in the API as a OFF/ON sequence of durations") and
     * developer.android.com/reference/android/os/VibrationEffect (createWaveform on/off overload),
     * both 2026-06-13.
     */
    private fun compileWave(h: Haptic.Wave): VibrationEffect? {
        if (capabilities.hasAmplitudeControl) {
            return VibrationEffect.createWaveform(h.timings, h.amplitudes, -1)
        }
        // No amplitude control: build the OFF/ON skeleton. Slot 0 is always OFF; we accumulate
        // each authored segment's duration into the current slot, flipping slots only when the
        // on/off state actually changes (amplitude 0 = off, >0 = on). A wave that starts with a
        // buzz (state = on) therefore opens with a 0ms off slot, satisfying the API's OFF/ON shape.
        val onOff = ArrayList<Long>(h.timings.size + 1)
        var slotIsOn = false              // index 0 is OFF by the API contract
        var acc = 0L
        for (i in h.timings.indices) {
            val segIsOn = h.amplitudes[i] > 0
            if (segIsOn == slotIsOn) {
                acc += h.timings[i]       // same state — extend the current slot
            } else {
                onOff += acc              // close the current slot
                acc = h.timings[i]        // open the next (flipped) slot with this segment
                slotIsOn = segIsOn
            }
        }
        onOff += acc                      // close the final slot
        return VibrationEffect.createWaveform(onOff.toLongArray(), -1)
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
