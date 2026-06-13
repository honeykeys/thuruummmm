package com.thrum.haptics

/**
 * A Haptic is one playable pattern — our unit of feel. The thing a thumb learns to name.
 *
 * The hardware gives two authoring tracks, and a Haptic is exactly one of them:
 *
 *  - [Composed] — a score of device PRIMITIVES (CLICK, TICK, THUD, SPIN, ...). The rich,
 *    perceptually-tuned track: each primitive is calibrated by the device for its own motor.
 *    The five block signatures live here. This is the "composing music" track — notes, each
 *    with a velocity ([Note.scale]) and a timing ([Note.delayMs]).
 *
 *  - [Wave] — a hand-drawn (duration, amplitude) envelope. The raw track: sustained feel the
 *    primitives cannot draw (the crash rumble), and the fallback where a device exposes no
 *    primitives at all.
 *
 * A Haptic compiles to a single android VibrationEffect (see [HapticEngine]). Primitives and
 * waveforms cannot be interleaved inside one effect — hence two concrete shapes, never a mix.
 */
sealed interface Haptic {
    val label: String

    /** Primitive score — the legible track. */
    data class Composed(override val label: String, val notes: List<Note>) : Haptic

    /** Hand-drawn amplitude envelope — alternating (duration ms, amplitude 0..255) segments. */
    class Wave(
        override val label: String,
        val timings: LongArray,
        val amplitudes: IntArray,
    ) : Haptic
}

/**
 * One primitive note: which primitive, how hard ([scale] 0..1 — the velocity), and the pause
 * before it in ms ([delayMs] — the timing / rest before this note sounds).
 */
data class Note(val primitive: Primitive, val scale: Float = 1f, val delayMs: Int = 0)

/**
 * Our names over the Android primitive constants. Each carries the two device-level facts that
 * decide whether it can play: its integer [id] and the minimum API ([minApi]) that defines it.
 * Whether a given motor *actually* renders it is hardware-dependent and queried at runtime —
 * that query is the "device-level list" ([HapticEngine.capabilities]).
 *
 * Int values + API levels verified against
 * developer.android.com/reference/android/os/VibrationEffect.Composition (2026-06-13).
 */
enum class Primitive(val id: Int, val minApi: Int) {
    CLICK(1, 30),
    QUICK_RISE(4, 30),
    SLOW_RISE(5, 30),
    QUICK_FALL(6, 30),
    TICK(7, 30),
    THUD(2, 31),
    SPIN(3, 31),
    LOW_TICK(8, 31),
}

/* ---------- authoring DSL — write a haptic like a bar of music ---------- */

/** Author a [Haptic.Composed] from primitive notes. */
fun haptic(label: String, build: HapticScore.() -> Unit): Haptic.Composed =
    Haptic.Composed(label, HapticScore().apply(build).notes.toList())

/** Author a [Haptic.Wave] from (duration ms, amplitude 0..255) segments. */
fun wave(label: String, vararg segments: Pair<Long, Int>): Haptic.Wave {
    val timings = LongArray(segments.size) { segments[it].first }
    val amps = IntArray(segments.size) { segments[it].second }
    return Haptic.Wave(label, timings, amps)
}

/** The score being built. Each call appends a note; the sugar names read as the gesture. */
class HapticScore {
    val notes = mutableListOf<Note>()

    /** A primitive at [scale] velocity (0..1), [delay] ms after the previous note. */
    fun note(primitive: Primitive, scale: Float = 1f, delay: Int = 0) {
        notes += Note(primitive, scale, delay)
    }

    fun click(scale: Float = 1f, delay: Int = 0) = note(Primitive.CLICK, scale, delay)
    fun tick(scale: Float = 1f, delay: Int = 0) = note(Primitive.TICK, scale, delay)
    fun lowTick(scale: Float = 1f, delay: Int = 0) = note(Primitive.LOW_TICK, scale, delay)
    fun thud(scale: Float = 1f, delay: Int = 0) = note(Primitive.THUD, scale, delay)
    fun spin(scale: Float = 1f, delay: Int = 0) = note(Primitive.SPIN, scale, delay)
    fun quickRise(scale: Float = 1f, delay: Int = 0) = note(Primitive.QUICK_RISE, scale, delay)
    fun slowRise(scale: Float = 1f, delay: Int = 0) = note(Primitive.SLOW_RISE, scale, delay)
    fun quickFall(scale: Float = 1f, delay: Int = 0) = note(Primitive.QUICK_FALL, scale, delay)
}
