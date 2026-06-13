package com.thrum.gesture

import com.thuruummm.physics.Cell
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial classifier suite — attacks the recogniser as plain Kotlin against synthetic finger
 * tracks (ARCHITECTURE.md §6). Proves: the right card fires, families do not poach each other,
 * sub-flourish streams emit nothing, the finger gate rejects 1/2/3 fingers, and the swipe angle
 * selects the right swipey.
 *
 * The classifier runs against the real [Deck.CARDS] — these are integration-grade tests of the
 * shipping deck, not a toy fixture.
 */
class GestureClassifierTest {

    private val classifier = GestureClassifier()
    private val cell = Cell(3, 0)

    // ── tappy: a gather fires the gather card ──────────────────────────────────────────────────────

    @Test fun gather_of_five_fingers_recognizes_tappy() {
        // Five fingers in a wide ring contract to a tight ring at the same centre — pure gather.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(500f, 500f, 200f, 0f),   // wide
                floatArrayOf(500f, 500f, 40f, 0f),    // tight — spreadChange = 0.2
            ),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a five-finger gather must recognise a card")
        assertEquals("tappy", r.card.id, "a pure gather is tappy")
        assertEquals(cell, r.targetCell)
    }

    // ── twisty: a clockwise rotate-with-contraction fires twisty, not tappy ──────────────────────────

    @Test fun clockwise_twist_with_contraction_recognizes_twisty() {
        // Rotate the ring CW (+phase, y-down screen coords) while shrinking — the twisty signature.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(500f, 500f, 200f, 0f),
                floatArrayOf(500f, 500f, 110f, 1.1f),  // ~63° CW + spread 0.55
            ),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a twist must recognise a card")
        assertEquals("twisty", r.card.id, "rotation+contraction is twisty, not tappy")
    }

    @Test fun counterclockwise_twist_is_rejected_by_direction_lock() {
        // twisty is clockwise-locked (Deck: RotateContract(clockwise = true)). A CCW twist must NOT
        // match twisty; with no CCW card in the deck it matches nothing.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(500f, 500f, 200f, 0f),
                floatArrayOf(500f, 500f, 110f, -1.1f),  // CCW
            ),
        )
        val r = classifier.classify(stream, cell)
        assertTrue(r == null || r.card.id != "twisty", "a CCW twist must not fire the CW-locked twisty")
    }

    // ── swipey: family separation + direction selection ──────────────────────────────────────────────

    @Test fun rightward_slide_recognizes_swipey_right_not_a_gather() {
        // Whole ring translates +x, spread held — a clean rightward swipe.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(300f, 500f, 120f, 0f),
                floatArrayOf(600f, 500f, 120f, 0f),   // +300px right, radius unchanged
            ),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a slide must recognise a card")
        assertEquals("swipey-right", r.card.id, "a +x slide is swipey-right")
    }

    @Test fun downward_slide_selects_swipey_down() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(500f, 300f, 120f, 0f),
                floatArrayOf(500f, 600f, 120f, 0f),   // +y is DOWN in screen coords
            ),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a downward slide must recognise a card")
        assertEquals("swipey-down", r.card.id, "a +y slide is swipey-down")
    }

    @Test fun leftward_slide_selects_swipey_left() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(600f, 500f, 120f, 0f),
                floatArrayOf(300f, 500f, 120f, 0f),   // -x = left = π
            ),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r)
        assertEquals("swipey-left", r.card.id)
    }

    // ── flourish gating: nothing commits without the finish ──────────────────────────────────────────

    @Test fun gather_without_flourish_emits_nothing() {
        // Same gather, but the fingers never lift and never settle-then-lift — gesture in progress.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(
                floatArrayOf(500f, 500f, 200f, 0f),
                floatArrayOf(500f, 500f, 40f, 0f),
            ),
            withFlourish = false,
        )
        assertNull(classifier.classify(stream, cell), "no flourish ⇒ no commit, even for a perfect gather")
    }

    @Test fun a_lift_without_a_prior_settle_does_not_commit() {
        // Fingers are still MOVING (a fast gather) right up to the instant they vanish — a fumble, not
        // a flourish. The settle requirement must reject it.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        // Rapid contraction every frame (centroid stationary but radius collapsing fast → fingers move fast).
        val radii = listOf(220f, 150f, 90f, 40f)
        for (rad in radii) { frames += PointerFrame(SyntheticStream.ring(500f, 500f, rad, 5), t); t += SyntheticStream.FRAME_NS }
        frames += PointerFrame(SyntheticStream.lifted(5), t)   // lift immediately, no settle
        assertNull(classifier.classify(frames, cell), "lift without a settle is not the uniform flourish")
    }

    // ── finger gate: 1/2/3 fingers do not register ───────────────────────────────────────────────────

    @Test fun single_finger_gather_is_rejected() {
        val stream = SyntheticStream.stroke(
            count = 1,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        assertNull(classifier.classify(stream, cell), "a single finger must not register (DESIGN)")
    }

    @Test fun two_finger_gesture_is_rejected() {
        val stream = SyntheticStream.stroke(
            count = 2,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        assertNull(classifier.classify(stream, cell), "two fingers do not register (DESIGN: 4–5)")
    }

    @Test fun three_finger_gesture_is_rejected_below_the_four_floor() {
        val stream = SyntheticStream.stroke(
            count = 3,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        assertNull(classifier.classify(stream, cell), "three fingers are below the minFingers=4 gate")
    }

    @Test fun four_fingers_satisfy_the_floor() {
        val stream = SyntheticStream.stroke(
            count = 4,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "four fingers clear the gate (DESIGN: 4–5)")
        assertEquals("tappy", r.card.id)
    }

    // ── score sanity ────────────────────────────────────────────────────────────────────────────────

    @Test fun a_clean_gesture_scores_high() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 220f, 0f), floatArrayOf(500f, 500f, 30f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r)
        assertTrue(r.score >= 0.5f, "a textbook gather should score well above the floor, was ${r.score}")
    }
}
