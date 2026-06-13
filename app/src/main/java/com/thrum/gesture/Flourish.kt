package com.thrum.gesture

import com.thrum.deck.GestureSpec
import com.thrum.deck.Thuruummm
import kotlin.math.hypot

/**
 * The uniform commit signal. The ONE finish the player learns once and applies to every card
 * (DESIGN.md §"The flourish"): a gesture emits nothing until the flourish reads, then the brick is
 * SET. The flourish is deliberately separated from every card's [com.thrum.deck.Movement] so the
 * classifier and the deck never know which form is in use.
 *
 * DESIGN keeps the input form OPEN (a deliberate hold vs an all-fingers-lift-off). This is therefore
 * a strategy, not a constant: the form can be retuned — even swapped at runtime via settings — without
 * touching [GestureClassifier] or the deck (ARCHITECTURE.md §4: "Flourish is a single isolable
 * predicate so the form can be retuned"). The classifier asks ONE question: `flourish.committed(frames)`.
 *
 * Pure Kotlin, time-as-data ([PointerFrame.timeNanos]); no clock read, fully unit-testable.
 */
fun interface Flourish {
    /**
     * True iff the window ends on a completed flourish. The classifier calls this each tick; while it
     * returns false the gesture is still in progress and nothing commits.
     */
    fun committed(frames: List<PointerFrame>): Boolean

    companion object {
        /**
         * The default form: **lift-off after a settle.** The hand performs the choreography, the
         * fingers go still, then ALL fingers lift together within a short window. Reads naturally as
         * "I am done," is distinct from gesture motion (the settle requirement prevents a moving hand
         * from misfiring), and is legible eyes-off. This is the first-guess default; final form +
         * durations are tuned on the thumb (DESIGN open question).
         *
         * Its finger floor falls back to [GestureSpec]'s own default `minFingers` (the canonical 4–5
         * floor) so a deck-less caller still gets the DESIGN floor from the single place that defines
         * it. Prefer [forDeck] when a deck is in hand — that ties the floor to the actual cards.
         */
        val Default: Flourish get() = AllLiftAfterSettle(minFingersForLift = GestureSpec.DEFAULT_MIN_FINGERS)

        /**
         * The default form, with its structural finger floor DERIVED from [cards] — the minimum
         * `minFingers` any card in the deck requires. This is the single source of truth for the floor
         * (FRAME: integrity over duplication): the per-card gate in [GestureClassifier.classify] and the
         * flourish's together-lift threshold read the SAME number, so they can never drift apart when a
         * card's `minFingers` is retuned or the flourish form is swapped. An empty deck (no cards yet)
         * falls back to the [GestureSpec] default floor.
         */
        fun forDeck(cards: List<Thuruummm>): Flourish =
            AllLiftAfterSettle(
                minFingersForLift = cards.minOfOrNull { it.gesture.minFingers } ?: GestureSpec.DEFAULT_MIN_FINGERS,
            )
    }
}

/**
 * Flourish form: **all fingers lift together after the hand settles.**
 *
 * Commit fires when, reading from the end of the window backward:
 *   1. the latest frame has zero pressed fingers (the hand has left the glass), AND
 *   2. immediately before the lift the hand was at full count and STILL (centroid speed below
 *      [settleSpeedPxPerMs]) for at least [settleMs], AND
 *   3. the lift completed quickly — all fingers gone within [liftWindowMs] of the last pressed frame
 *      (a together-lift, not fingers trickling off one at a time, which would be a fumble not a finish).
 *
 * The settle requirement is what makes the flourish "distinct from gesture motion (no misfire)"
 * (DESIGN requirement): a hand mid-swipe whose fingers happen to leave the sensor will not commit,
 * because it was not still first.
 *
 * @param settleMs            How long the hand must hold still before lifting to count as a finish.
 * @param settleSpeedPxPerMs  Centroid speed under which the hand is "still". First guess; tuned on device.
 * @param liftWindowMs        Max time from the last pressed frame to the all-lifted frame for the lift
 *                            to read as "together". Beyond this, fingers left raggedly → not a flourish.
 * @param minFingersForLift   STRUCTURAL floor: the hand must have reached at least this many fingers
 *                            before the lift for the lift to count (a stray single/low-finger lift is
 *                            never a together-lift flourish). This is NOT an independent design number —
 *                            it should be DERIVED from the deck via [Flourish.forDeck] so there is one
 *                            source of truth for the 4–5 floor (see [GestureClassifier]). The default
 *                            here mirrors [GestureSpec]'s default `minFingers` only so a directly-
 *                            constructed instance still gets a sane floor; production wiring passes the
 *                            deck-derived value.
 */
class AllLiftAfterSettle(
    private val settleMs: Float = 90f,
    private val settleSpeedPxPerMs: Float = 0.6f,
    private val liftWindowMs: Float = 180f,   // full-count → all-gone budget; covers a real trickle-lift
    private val minFingersForLift: Int = GestureSpec.DEFAULT_MIN_FINGERS,
) : Flourish {

    override fun committed(frames: List<PointerFrame>): Boolean {
        if (frames.size < 2) return false

        // (1) The window must END with the hand gone.
        val last = frames.last()
        if (last.pressedCount != 0) return false

        // Anchor on the last frame the hand was at FULL count — the instant the lift began — NOT on the
        // last frame with *any* finger down.
        //
        // WHY (the on-device placement bug): a real hand lifting 4–5 fingers does not clear the glass
        // inside one ~16ms frame. The pressed count TRICKLES (5→3→1→0) across a few frames as the
        // fingers peel off. The old code anchored on `indexOfLast { pressedCount > 0 }` — the LAST
        // partial frame (the "1 finger left" frame) — and then required THAT frame to be at full count,
        // so every realistic lift was rejected as a "ragged fumble" and no brick ever placed. The
        // together-lift is properly defined by how FAST the hand clears once it STARTS leaving (the
        // window below), not by whether every finger happens to vanish within a single sampled frame.
        val fullIdx = frames.indexOfLast { it.pressedCount >= minFingersForLift }
        if (fullIdx < 0) return false                        // never reached the finger floor

        // (3) The lift must have been TOGETHER: from the last full-count frame to the all-gone end of
        // the window, within liftWindowMs. A normal human trickle-lift clears well inside the window; a
        // stale buffer whose empty tail stretches long past the lift (the trailing-empties attack) does
        // not, so a single gesture still commits at most once, only in the window the lift happened.
        val liftDtMs = (last.timeNanos - frames[fullIdx].timeNanos) / 1_000_000f
        if (liftDtMs > liftWindowMs) return false

        // (2) And it had SETTLED: walk back from the last full-count frame collecting the still tail;
        // require its span to cover settleMs with centroid speed staying under threshold the whole way.
        // A hand still moving right up to the lift (a fumble, not a finish) never accrues the still span.
        var settledSpan = 0f
        var i = fullIdx
        while (i > 0) {
            val cur = frames[i]
            val prev = frames[i - 1]
            if (prev.pressedCount < minFingersForLift) break  // before the hand was fully down
            val speed = centroidSpeedPxPerMs(prev, cur)
            if (speed > settleSpeedPxPerMs) break             // hand was still moving here — settle broken
            settledSpan += (cur.timeNanos - prev.timeNanos) / 1_000_000f
            if (settledSpan >= settleMs) return true
            i--
        }
        return false
    }

    private fun centroidSpeedPxPerMs(a: PointerFrame, b: PointerFrame): Float {
        val ca = centroid(a) ?: return Float.MAX_VALUE
        val cb = centroid(b) ?: return Float.MAX_VALUE
        val dt = (b.timeNanos - a.timeNanos) / 1_000_000f
        if (dt <= 0f) return 0f
        return hypot(cb.first - ca.first, cb.second - ca.second) / dt
    }

    private fun centroid(frame: PointerFrame): Pair<Float, Float>? {
        val p = frame.pressed
        if (p.isEmpty()) return null
        var sx = 0f; var sy = 0f
        for (f in p) { sx += f.x; sy += f.y }
        return (sx / p.size) to (sy / p.size)
    }
}

/**
 * Alternate flourish form: **a deliberate hold.** Commit fires when the hand has been at full count
 * and still for at least [holdMs] — the player presses and waits, no lift needed. Provided so the
 * form can be A/B-tuned against [AllLiftAfterSettle] (DESIGN open question) by swapping the strategy
 * the classifier is constructed with; nothing else changes.
 *
 * Note: with a hold-flourish the buffer must retain enough span to cover [holdMs]; the classifier
 * resets the buffer on commit so a single hold fires once.
 *
 * @param holdMs            How long the still hold must last to commit.
 * @param stillSpeedPxPerMs Centroid speed under which the hand counts as held still.
 * @param minFingers        STRUCTURAL floor: minimum fingers that must be down throughout the hold.
 *                          Like [AllLiftAfterSettle.minFingersForLift] this should be deck-derived in
 *                          production (the default mirrors [GestureSpec]'s default `minFingers` so a
 *                          directly-constructed instance still has the 4–5 floor); it is NOT a second
 *                          independent design number.
 */
class DeliberateHold(
    private val holdMs: Float = 280f,
    private val stillSpeedPxPerMs: Float = 0.6f,
    private val minFingers: Int = GestureSpec.DEFAULT_MIN_FINGERS,
) : Flourish {

    override fun committed(frames: List<PointerFrame>): Boolean {
        if (frames.size < 2) return false
        if (frames.last().pressedCount < minFingers) return false   // must still be holding

        var heldSpan = 0f
        var i = frames.lastIndex
        while (i > 0) {
            val cur = frames[i]
            val prev = frames[i - 1]
            if (cur.pressedCount < minFingers || prev.pressedCount < minFingers) break
            val ca = centroidOf(prev); val cb = centroidOf(cur)
            val dt = (cur.timeNanos - prev.timeNanos) / 1_000_000f
            val speed = if (dt <= 0f) 0f else hypot(cb.first - ca.first, cb.second - ca.second) / dt
            if (speed > stillSpeedPxPerMs) break
            heldSpan += dt
            if (heldSpan >= holdMs) return true
            i--
        }
        return false
    }

    private fun centroidOf(frame: PointerFrame): Pair<Float, Float> {
        val p = frame.pressed
        var sx = 0f; var sy = 0f
        for (f in p) { sx += f.x; sy += f.y }
        val n = if (p.isEmpty()) 1 else p.size
        return (sx / n) to (sy / n)
    }
}
