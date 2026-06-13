package com.thrum.gesture

import com.thuruummm.physics.Cell
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — runs against the REAL [Deck.CARDS] (tappy, twisty, the 7 swipeys). This is the literal
 * task contract under attack: two DIFFERENT shipping cards must be distinguished, and a PARTIAL gesture
 * must NOT commit before the flourish. These are integration-grade — a regression in deck tuning OR in the
 * classifier surfaces here.
 *
 * Pure JVM, synthetic streams (ARCHITECTURE.md §6).
 */
class AdversarialShippingDeckTest {

    private val classifier = GestureClassifier() // real Deck.CARDS, real default flourish
    private val cell = Cell(3, 0)

    // ── THE core requirement: tappy and twisty are distinguished over near-identical contractions. ───

    @Test fun tappy_vs_twisty_split_on_rotation_alone_over_the_real_deck() {
        // Identical contraction; the ONLY difference is rotation. tappy must win the still one, twisty the spin.
        val gatherKeys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 80f, 0f))
        val twistKeys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 120f, 1.0f)) // CW spin + contract

        val tappy = classifier.classify(SyntheticStream.stroke(count = 5, keys = gatherKeys), cell)
        val twisty = classifier.classify(SyntheticStream.stroke(count = 5, keys = twistKeys), cell)

        assertNotNull(tappy); assertNotNull(twisty)
        assertEquals("tappy", tappy.card.id, "a still contraction is tappy")
        assertEquals("twisty", twisty.card.id, "a spinning contraction is twisty")
        assertTrue(tappy.card.id != twisty.card.id, "the two specs MUST resolve to different cards")
    }

    // ── THE other core requirement: a partial gesture does not commit before the flourish. ───────────

    @Test fun a_partial_twisty_in_progress_commits_nothing() {
        // A perfect clockwise contracting twist — but the fingers are still down (no flourish).
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 110f, 1.1f)),
            withFlourish = false,
        )
        assertNull(classifier.classify(stream, cell), "a perfect twisty mid-gesture must not commit — no flourish, no brick")
    }

    @Test fun a_partial_swipey_in_progress_commits_nothing() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(300f, 500f, 120f, 0f), floatArrayOf(600f, 500f, 120f, 0f)),
            withFlourish = false,
        )
        assertNull(classifier.classify(stream, cell), "a clean rightward slide still in progress must not commit")
    }

    // ── target cell passthrough — the classifier owns recognition, NOT board state. ──────────────────

    @Test fun the_supplied_target_cell_is_returned_untouched_never_recomputed() {
        val weird = Cell(-7, 42) // an off-board cell the classifier must NOT sanitise or recompute
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 80f, 0f)),
        )
        val r = classifier.classify(stream, weird)
        assertNotNull(r)
        assertEquals(weird, r.targetCell, "the classifier passes the supplied cell through verbatim; board state is not its job")
    }

    // ── the score that produced the match cleared the card's own tolerance gate. ─────────────────────

    @Test fun a_returned_recognized_carries_a_score_at_or_above_its_cards_gate() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 220f, 0f), floatArrayOf(500f, 500f, 30f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r)
        val gate = 1f - r.card.gesture.tolerance
        assertTrue(r.score >= gate, "a Recognized must have cleared its card's tolerance gate: score ${r.score} >= gate $gate")
        assertTrue(r.score in 0f..1f, "score must be normalised to [0,1], was ${r.score}")
    }

    // ── structural malformed-input attacks — must degrade safely (null), never crash. ────────────────

    @Test fun an_empty_window_commits_nothing() {
        assertNull(classifier.classify(emptyList(), cell), "an empty window has nothing to recognise")
    }

    @Test fun a_window_that_never_touched_commits_nothing() {
        // Frames that are all-lifted from the start (no finger ever pressed). No flourish, no features.
        val frames = (0 until 10).map { PointerFrame(SyntheticStream.lifted(5), it * SyntheticStream.FRAME_NS) }
        assertNull(classifier.classify(frames, cell), "a window with no touch must not commit")
    }

    @Test fun a_single_frame_window_commits_nothing() {
        val frames = listOf(PointerFrame(SyntheticStream.ring(500f, 500f, 100f, 5), 0L))
        assertNull(classifier.classify(frames, cell), "a one-frame window cannot satisfy the flourish (needs >= 2 frames)")
    }

    @Test fun duplicate_timestamp_frames_do_not_crash_the_flourish_timing() {
        // Several frames stamped at the SAME timeNanos (a stuttering frame clock). dt = 0 must not divide-by-
        // zero or hang; the gesture should simply fail to accumulate settle span and commit nothing.
        val frames = mutableListOf<PointerFrame>()
        repeat(6) { frames += PointerFrame(SyntheticStream.ring(500f, 500f, 80f, 5), 0L) } // all t=0
        frames += PointerFrame(SyntheticStream.lifted(5), 0L)                              // lift also t=0
        // No assertion on the result value beyond "it returns without throwing"; null is the expected safe outcome
        // because zero elapsed time cannot cover settleMs.
        assertNull(classifier.classify(frames, cell), "zero-dt frames cannot cover the settle window ⇒ no commit, no crash")
    }

    @Test fun a_three_finger_gesture_against_the_whole_real_deck_is_rejected() {
        // Every shipping card is minFingers=4. A 3-finger gesture must clear NO card.
        val stream = SyntheticStream.stroke(
            count = 3,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        assertNull(classifier.classify(stream, cell), "3 fingers is below every shipping card's minFingers=4 floor")
    }
}
