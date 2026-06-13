package com.thrum.gesture

import com.thuruummm.physics.Cell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — proves a PARTIAL gesture does NOT commit before the flourish, and attacks the flourish
 * predicate's timing corners. The single non-negotiable invariant under attack (DESIGN, Karl): the
 * flourish is the ONLY commit signal; nothing mints until it reads.
 *
 * The classifier is time-as-data: it reads [PointerFrame.timeNanos], not a clock. So the flourish-HOLD
 * timing is driven here under **kotlinx-coroutines-test virtual time** — a [runTest] frame-loop harness
 * advances a virtual clock with [delay], stamps each [PointerFrame] from [currentTime], pushes it into a
 * real [PointerBuffer], and asks the classifier each tick — exactly as the production `withFrameNanos`
 * loop would. Virtual time lets a test hold "for 250ms" deterministically with no wall-clock sleep.
 * (kotlinx.coroutines.test API verified 2026-06-13: kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/.)
 *
 * currentTime / advanceTimeBy / advanceUntilIdle are @ExperimentalCoroutinesApi (verified 2026-06-13,
 * kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/current-time.html);
 * opt in at the class level so the harness compiles cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialFlourishGateTest {

    private val cell = Cell(3, 0)
    private val classifier = GestureClassifier()

    // ── PART A: pure synthetic-stream attacks on partial commit (no coroutines needed) ────────────────

    @Test fun a_geometrically_perfect_gather_still_in_progress_emits_nothing() {
        // Textbook gather, but fingers are STILL DOWN at the end of the window — the flourish has not fired.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 220f, 0f), floatArrayOf(500f, 500f, 30f, 0f)),
            withFlourish = false,
        )
        assertNull(classifier.classify(stream, cell), "a perfect gather mid-gesture must NOT commit — no flourish, no brick")
    }

    @Test fun a_settle_with_no_lift_emits_nothing() {
        // Hand lands, gathers, holds still for a long settle — but never lifts. AllLiftAfterSettle requires
        // the window to END empty; a held-still hand is "in progress", not "done".
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        repeat(6) { i ->
            val rad = 220f - i * 30f
            frames += PointerFrame(SyntheticStream.ring(500f, 500f, rad, 5), t); t += SyntheticStream.FRAME_NS
        }
        repeat(20) { // ~320ms of dead-still hold, fingers never leave
            frames += PointerFrame(SyntheticStream.ring(500f, 500f, 40f, 5), t); t += SyntheticStream.FRAME_NS
        }
        assertNull(classifier.classify(frames, cell), "a long still HOLD with the default lift-flourish must not commit")
    }

    @Test fun a_lift_with_no_prior_settle_emits_nothing() {
        // Fingers move fast right up to the instant they vanish — a fumble, not a finish. The settle gate
        // must reject it even though the window ends empty and at full count just before the lift.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        for (rad in listOf(230f, 150f, 80f, 30f)) { // fast contraction, no still tail
            frames += PointerFrame(SyntheticStream.ring(500f, 500f, rad, 5), t); t += SyntheticStream.FRAME_NS
        }
        frames += PointerFrame(SyntheticStream.lifted(5), t)
        assertNull(classifier.classify(frames, cell), "lift without a settle is not the uniform flourish")
    }

    @Test fun a_ragged_trickle_lift_within_the_window_IS_a_normal_human_lift_and_commits() {
        // CORRECTED SPEC (on-device placement bug). A real hand lifting 4–5 fingers does NOT clear the
        // glass within one 16ms frame — the pressed count trickles 5→3→1→0 across a few frames. The
        // original gate anchored on the LAST partial frame and demanded it be at full count, so it
        // rejected every realistic lift and NO brick ever placed on device. The together-lift is defined
        // by how fast the hand clears once it starts leaving (within liftWindowMs of the last full-count
        // frame), not by whether every finger vanishes inside a single sampled frame. A settled hand that
        // then trickles off over ~48ms is a normal finish and MUST commit.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        // settle still at 5 for ~160ms
        repeat(10) { frames += PointerFrame(SyntheticStream.ring(500f, 500f, 60f, 5), t); t += SyntheticStream.FRAME_NS }
        // trickle: 5 → 3 → 1 → 0, one frame each (~48ms total — well inside the lift window)
        frames += PointerFrame(pressedRing(500f, 500f, 60f, keep = 3), t); t += SyntheticStream.FRAME_NS
        frames += PointerFrame(pressedRing(500f, 500f, 60f, keep = 1), t); t += SyntheticStream.FRAME_NS
        frames += PointerFrame(SyntheticStream.lifted(5), t)
        val r = classifier.classify(frames, cell)
        assertNotNull(r, "a settled hand that trickles off within the lift window is a normal human lift")
        assertEquals("tappy", r.card.id, "a centred, settled 5-finger gather mints tappy")
    }

    @Test fun a_too_slow_trickle_lift_exceeding_the_window_does_not_commit() {
        // The boundary the corrected gate still enforces: if the hand peels off so SLOWLY that the span
        // from the last full-count frame to the all-gone frame exceeds liftWindowMs, it is no longer a
        // single together-lift — it is the hand wandering off, and must not commit.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        // settle still at 5 for ~160ms
        repeat(10) { frames += PointerFrame(SyntheticStream.ring(500f, 500f, 60f, 5), t); t += SyntheticStream.FRAME_NS }
        // very slow trickle: hold a partial count for ~13 frames (~208ms) before the hand finally clears.
        repeat(13) { frames += PointerFrame(pressedRing(500f, 500f, 60f, keep = 2), t); t += SyntheticStream.FRAME_NS }
        frames += PointerFrame(SyntheticStream.lifted(5), t)
        assertNull(classifier.classify(frames, cell), "a lift dragged out past the window is not a together-lift")
    }

    @Test fun trailing_empty_frames_push_the_lift_outside_the_together_window() {
        // ATTACK on the full-count anchor + liftDtMs: a clean settle and together-lift, but the buffer
        // then carries SEVERAL trailing all-empty frames (the player's hand is gone for a while). The
        // span from the last full-count frame to the final empty frame now far exceeds liftWindowMs
        // (180ms). committed() must NOT re-fire on these stale empty tails — a single gesture must commit
        // AT MOST in the window where the lift actually happened, not perpetually afterwards.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        repeat(10) { frames += PointerFrame(SyntheticStream.ring(500f, 500f, 60f, 5), t); t += SyntheticStream.FRAME_NS }
        frames += PointerFrame(SyntheticStream.lifted(5), t); t += SyntheticStream.FRAME_NS // the together-lift frame
        repeat(15) { frames += PointerFrame(SyntheticStream.lifted(5), t); t += SyntheticStream.FRAME_NS } // 240ms of empties
        // From the last full-count frame to the final empty frame ≈ 256ms > the 180ms together window.
        assertNull(
            classifier.classify(frames, cell),
            "once the lift is stale (empties span > liftWindowMs) the gesture must not still read as committed",
        )
    }

    @Test fun two_finger_lift_after_settle_is_below_the_lift_floor() {
        // Only TWO fingers ever touch, settle, and lift together. minFingersForLift (4) must reject it — a
        // two-finger flourish is never a flourish (DESIGN: single/two fingers do not register).
        val stream = SyntheticStream.stroke(
            count = 2,
            keys = listOf(floatArrayOf(500f, 500f, 120f, 0f), floatArrayOf(500f, 500f, 60f, 0f)),
        )
        assertNull(classifier.classify(stream, cell), "a two-finger settle-and-lift is below the flourish finger floor")
    }

    // ── PART B: the flourish HOLD boundary under virtual time ─────────────────────────────────────────
    //
    // DeliberateHold(holdMs) is the alternate flourish form (DESIGN open question). It commits only after
    // the hand has held still at full count for >= holdMs. We drive it through a real frame loop on a
    // virtual clock and assert the commit lands ON the boundary and not before — a partial hold must not
    // commit.

    @Test fun deliberate_hold_does_not_commit_before_the_hold_duration_elapses() = runTest {
        val scheduler = testScheduler
        val holdMs = 250L
        val flourish = DeliberateHold(holdMs = holdMs.toFloat(), minFingers = 4)
        val clf = GestureClassifier(flourish = flourish)
        val buffer = PointerBuffer(capacity = 240)

        var committedAtMs: Long? = null
        // A 30-frame still hold at 16ms/frame ≈ 464ms — enough to cross the 250ms boundary partway through.
        repeat(30) {
            // Stamp the frame with the CURRENT VIRTUAL TIME, exactly as withFrameNanos would stamp real nanos.
            buffer.push(PointerFrame(SyntheticStream.ring(500f, 500f, 120f, 5), scheduler.currentTime * 1_000_000L))
            if (committedAtMs == null && clf.classify(buffer.frames(), cell) != null) {
                committedAtMs = scheduler.currentTime
            }
            delay(16) // advance virtual time one frame
        }
        advanceUntilIdle()

        assertNotNull(committedAtMs, "a long still hold MUST eventually commit the hold-flourish")
        assertTrue(
            committedAtMs!! >= holdMs,
            "the hold flourish committed at ${committedAtMs}ms — it must NOT fire before holdMs=$holdMs elapsed",
        )
    }

    @Test fun deliberate_hold_of_exactly_under_the_threshold_never_commits() = runTest {
        // A still hold whose TOTAL span is just shy of holdMs must never commit, no matter how many ticks.
        val scheduler = testScheduler
        val holdMs = 300L
        val flourish = DeliberateHold(holdMs = holdMs.toFloat(), minFingers = 4)
        val clf = GestureClassifier(flourish = flourish)
        val buffer = PointerBuffer(capacity = 240)

        var everCommitted = false
        // 17 frames * 16ms = 272ms total still hold — strictly less than 300ms. Must NOT commit.
        repeat(17) {
            buffer.push(PointerFrame(SyntheticStream.ring(500f, 500f, 120f, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) everCommitted = true
            delay(16)
        }
        advanceTimeBy(50) // let any stray scheduled work run; there is none, but prove idleness
        advanceUntilIdle()

        assertFalse(everCommitted, "a 272ms hold is under the 300ms threshold — a partial hold must never commit")
    }

    @Test fun a_hold_interrupted_by_motion_resets_and_does_not_commit() = runTest {
        // The hand holds still for a while, then MOVES (the gesture is being re-aimed), all under the hold
        // duration after the move. The motion breaks the still-tail; the post-move still time is too short.
        // A moving-then-briefly-still hand must NOT commit.
        val scheduler = testScheduler
        val holdMs = 250L
        val flourish = DeliberateHold(holdMs = holdMs.toFloat(), minFingers = 4)
        val clf = GestureClassifier(flourish = flourish)
        val buffer = PointerBuffer(capacity = 240)

        var everCommitted = false
        var cx = 400f
        // 8 still frames (~128ms) — under threshold
        repeat(8) {
            buffer.push(PointerFrame(SyntheticStream.ring(cx, 500f, 120f, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) everCommitted = true
            delay(16)
        }
        // 6 MOVING frames — centroid sprints right, breaking any still tail
        repeat(6) {
            cx += 40f
            buffer.push(PointerFrame(SyntheticStream.ring(cx, 500f, 120f, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) everCommitted = true
            delay(16)
        }
        // 8 still frames again (~128ms) — under threshold AGAIN since the clock restarted after the move
        repeat(8) {
            buffer.push(PointerFrame(SyntheticStream.ring(cx, 500f, 120f, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) everCommitted = true
            delay(16)
        }
        advanceUntilIdle()

        assertFalse(everCommitted, "motion mid-hold resets the still-tail; neither still segment reaches holdMs ⇒ no commit")
    }

    @Test fun lift_flourish_commits_exactly_once_across_a_streaming_window() = runTest {
        // The default lift-flourish, driven through the live buffer on virtual time: the gesture should
        // commit on the frame the together-lift completes, and the game loop clears the buffer on commit.
        // We assert it fires at least once and report WHEN — proving the commit is tied to the lift instant,
        // not to mere geometry earlier in the stream.
        val scheduler = testScheduler
        val clf = GestureClassifier() // default AllLiftAfterSettle
        val buffer = PointerBuffer(capacity = 240)
        var commits = 0
        var firstCommitMs: Long? = null

        // gather down to a tight ring over 6 frames
        repeat(6) { i ->
            val rad = 220f - i * 30f
            buffer.push(PointerFrame(SyntheticStream.ring(500f, 500f, rad, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) { commits++; if (firstCommitMs == null) firstCommitMs = scheduler.currentTime }
            delay(16)
        }
        // settle still ~128ms
        repeat(8) {
            buffer.push(PointerFrame(SyntheticStream.ring(500f, 500f, 40f, 5), scheduler.currentTime * 1_000_000L))
            if (clf.classify(buffer.frames(), cell) != null) { commits++; if (firstCommitMs == null) firstCommitMs = scheduler.currentTime }
            delay(16)
        }
        // together-lift
        buffer.push(PointerFrame(SyntheticStream.lifted(5), scheduler.currentTime * 1_000_000L))
        val recognized = clf.classify(buffer.frames(), cell)
        if (recognized != null) { commits++; if (firstCommitMs == null) firstCommitMs = scheduler.currentTime; buffer.clear() }
        advanceUntilIdle()

        assertNotNull(recognized, "the together-lift frame must finally commit the gather")
        assertEquals("tappy", recognized.card.id, "a centred 5-finger gather commits tappy")
        assertEquals(1, commits, "the gesture must commit EXACTLY once — only on the lift frame, never during the hold")
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    /** A ring of 5 finger SLOTS where only [keep] are pressed (the rest report pressed=false at origin). */
    private fun pressedRing(cx: Float, cy: Float, radius: Float, keep: Int): List<Finger> {
        val full = SyntheticStream.ring(cx, cy, radius, 5)
        return full.mapIndexed { i, f -> if (i < keep) f else f.copy(pressed = false) }
    }
}
