package com.thrum.haptics

import android.os.SystemClock
import com.thuruummm.physics.CollapseResult

/**
 * The play-side haptic facade for the toy — the single thing the game loop talks to.
 *
 * It owns the three acts of feedback, in the exact firing order the pipeline commits them
 * (ARCHITECTURE.md §2 `commit(r)` / §4 "The two-beat firing points"):
 *
 *   1. [thur]      — beat one. Fired the INSTANT the flourish reads, *before* physics runs.
 *                    Uniform across every card. "The gesture landed."
 *   2. [rummmm]    — beat two. Fired immediately after physics.place() returns. This card's
 *                    character (the [com.thrum.deck.Thuruummm.rummmm] the Deck authored).
 *                    "The brick minted."
 *   3. [thruummm]  — the reward. Fired after a placement that triggered a collapse. The BIG one;
 *                    it priority-locks the motor so the thur/rummmm of subsequent rapid taps cannot
 *                    cut the crash short.
 *
 * It does NOT author the per-card rummmm (the Deck owns that) and it does NOT author the thur or
 * the thruummm shape ([CommitHaptics] and [Thruummm] do). It is the *conductor*: right beat, right
 * moment, right priority, with the crash-wins-the-motor coalescing the single motor demands.
 *
 * ── Why this sits over [HapticEngine] and does not replace it ────────────────────────────────
 *
 * [HapticEngine] is the device layer: it owns the one [android.os.Vibrator], probes capabilities
 * once, compiles a [Haptic] to a VibrationEffect, gates every primitive on real hardware support
 * (substituting near-equivalents), and exposes `play(haptic, priority)` where `priority = true`
 * calls Vibrator.cancel() before playing — the verified crash-wins primitive (RESEARCH-NATIVE.md
 * §5; cancel() is API 1, vibrate(VibrationEffect) is API 26). This facade adds only the *game's*
 * semantics on top: the named three acts, their order, and the coalescing window. The capability
 * probe, the fallback table, and the actual motor are reused, never duplicated.
 *
 * ── Threading ────────────────────────────────────────────────────────────────────────────────
 *
 * Vibrator.vibrate(...) is non-blocking (fire-and-forget to the vibrator service), so all three
 * methods are safe to call directly from the withFrameNanos game loop with no threading
 * (RESEARCH-NATIVE.md §5). Each method is one synchronous play; the loop calls them in order.
 *
 * SINGLE-THREAD CONTRACT: thur/rummmm/thruummm/cancel are designed to be called only from the one
 * withFrameNanos game loop (ARCHITECTURE.md §3/§4). The coalescing state [motorLockedUntilMs] is
 * not guarded by a lock — the loop is the serialization. It is marked @Volatile so that even if a
 * future wiring calls a beat from a pointerInput callback on a different dispatcher, the 64-bit
 * read/write is at least atomic and visible across threads (a plain `long` is not guaranteed
 * atomic). That makes a stray off-loop call safe-ish but still racy on the read-modify-write in
 * [motorReserved]; if real concurrency is ever introduced, promote this to an AtomicLong CAS or
 * confine all calls to a single dispatcher. Do not call these methods concurrently today.
 *
 * Construct once and hold it (the GameScreen `remember`s it, like the bench remembers its engine):
 *   val haptics = remember { ThuruummmHaptics(context.applicationContext) }
 */
class ThuruummmHaptics(
    private val engine: HapticSink,
    /** Time source — injectable so the coalescing window is unit-testable without a real clock. */
    private val nowMs: () -> Long = { SystemClock.uptimeMillis() },
) {

    /** Convenience constructor: build the device engine from a Context (the production path). */
    constructor(context: android.content.Context) : this(HapticEngine(context))

    /** Exposed so the game/render layer can show the visual fallback when there is no motor, and
     *  so a bench/diagnostic can read what this device admits. Reuses the engine's single probe. */
    val capabilities: Capabilities get() = engine.capabilities

    /** uptime (ms) until which the motor is reserved by an in-flight THRUUMMMM. While reserved,
     *  commit beats are coalesced away so they cannot bleed into / cut short the crash. 0 = free.
     *  @Volatile: atomic 64-bit read/write so a stray off-loop call cannot read a torn value (see the
     *  single-thread contract in the class KDoc). Not a full concurrency guard. */
    @Volatile
    private var motorLockedUntilMs: Long = 0L

    // ── The three acts ───────────────────────────────────────────────────────────────────────

    /**
     * Beat one — the uniform "thur". Fire the instant the flourish registers, before physics.
     * Coalesced away if a THRUUMMMM currently owns the motor (a tap during a crash must not
     * interrupt the reward).
     */
    fun thur() {
        if (motorReserved()) return
        engine.play(CommitHaptics.THUR)
    }

    /**
     * Beat two — this card's "rummmm". Fire immediately after physics.place() returns. Pass the
     * card's own [Haptic] (`card.rummmm`); this facade never invents it.
     * Coalesced away while a THRUUMMMM owns the motor, same as [thur].
     */
    fun rummmm(cardRummmm: Haptic) {
        if (motorReserved()) return
        engine.play(cardRummmm)
    }

    /**
     * The reward — THRUUMMMM. Fire after a placement that caused a [collapse]. Priority-locks the
     * motor: [HapticEngine.play] with `priority = true` cancels whatever is buzzing, then plays the
     * crash; and we reserve the motor for the crash's own duration so the very next tap's thur/
     * rummmm is coalesced away instead of cutting the crash short.
     *
     * The crash's richness is mapped from the physics by [Thruummm.forCollapse] — bigger, more
     * tangled collapses ring like a bell; a single-brick slip still gets a small honest THRUUMMMM.
     */
    fun thruummm(collapse: CollapseResult) {
        val wave = Thruummm.forCollapse(collapse)
        // Reserve the motor for the crash's total length so commit beats during it are dropped.
        // The lock is computed from the wave we are about to play — exact, not a guess.
        //
        // FEEL TRADEOFF — Karl's knob (DESIGN.md): this locks for the crash's ENTIRE duration
        // (tumble + slam, up to ~700ms+ on a deep cascade). A player who places a brick immediately
        // after triggering a collapse gets NO thur/rummmm during that window — the heartbeat they
        // navigate by goes dark for most of a second. Default chosen here = "crash is sacrosanct,
        // never cut short" (ARCHITECTURE.md §4). The alternative — lock only through the slam's
        // leading edge so a tap can layer over the tumble tail — is a legitimate eyes-off-legibility
        // choice. Deliberately surfaced here, not buried: change the lock window if the thumb test
        // says the dark second hurts navigation more than a cut crash hurts the reward.
        motorLockedUntilMs = nowMs() + wave.totalDurationMs()
        // Crash wins the motor — at the SINK layer, for ANY sink. The priority flag tells a device-
        // backed sink to cancel-first, but an injected sink (a test FakeMotor, or any sink that does not
        // itself cancel inside play) would otherwise never see a cancel, and the heartbeat buzzing under
        // the crash would not be stopped. So we issue the cancel EXPLICITLY here, strictly before the
        // crash play: the "crash wins" guarantee is observable on the sink's own timeline (cancel → crash),
        // not buried inside one sink implementation. (On the real HapticEngine this and play(priority)'s
        // internal cancel are the same idempotent v.cancel() — a harmless double no-op.)
        engine.cancel()
        engine.play(wave, priority = true)   // crash, played at priority — verified §5
    }

    /** Stop any vibration immediately and release the motor lock (e.g. on screen exit / reset). */
    fun cancel() {
        engine.cancel()
        motorLockedUntilMs = 0L
    }

    // ── Coalescing ───────────────────────────────────────────────────────────────────────────

    /** True while a THRUUMMMM still owns the motor. Lazily clears the lock once it has elapsed. */
    private fun motorReserved(): Boolean {
        if (motorLockedUntilMs == 0L) return false
        if (nowMs() >= motorLockedUntilMs) {
            motorLockedUntilMs = 0L
            return false
        }
        return true
    }
}

/**
 * Total wall-clock length of a [Haptic.Wave] envelope in ms — the sum of every segment duration
 * (both the on-buzzes and the amplitude-0 gaps are real time on the motor). Used to size the
 * crash's motor-lock window so commit beats are coalesced for exactly as long as the crash plays.
 *
 * Composed primitives are not Waves; the THRUUMMMM is always a Wave (see [Thruummm]), so this is
 * the only shape that needs a duration, and it is exact (a Wave carries its own timings).
 */
internal fun Haptic.Wave.totalDurationMs(): Long = timings.sum()
