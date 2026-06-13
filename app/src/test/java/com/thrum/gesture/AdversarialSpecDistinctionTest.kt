package com.thrum.gesture

import com.thrum.deck.Dir
import com.thrum.deck.GestureSpec
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thrum.haptics.haptic
import com.thrum.deck.Glyph
import com.thuruummm.physics.Cell
import com.thuruummm.physics.Material
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — proves two DIFFERENT card specs are reliably distinguished, and that ambiguous /
 * boundary gestures do NOT silently commit to the wrong card. Every test here is authored to BREAK the
 * classifier's family-separation and tie-break logic, not to confirm the happy path.
 *
 * The load-bearing product claim under attack (DESIGN / Karl): every card is different; the classifier
 * must measure, then pick the right one — never poach, never tie-break into the wrong family.
 *
 * Pure JVM, synthetic streams (ARCHITECTURE.md §6). No Android, no coroutines: the classifier is
 * time-as-data and fully deterministic for a given window.
 */
class AdversarialSpecDistinctionTest {

    private val classifier = GestureClassifier()
    private val cell = Cell(3, 0)

    // Local card builders so a hostile test can pit two bespoke specs against ONE stream and assert
    // which wins — independent of the shipping deck's tuning.
    private fun card(id: String, movement: Movement, tol: Float = 0.15f, minFingers: Int = 4) =
        Thuruummm(
            id = id,
            gesture = GestureSpec(minFingers = minFingers, movement = movement, tolerance = tol),
            material = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2),
            rummmm = haptic("$id-r") { tick(scale = 0.5f) },
            glyph = Glyph.STUB_A,
        )

    // ── ATTACK 1: a gather and a twist over the SAME contraction must split on rotation alone ──────────
    //
    // Both cards see a shrinking cloud. The ONLY discriminator is rotation. If the classifier leaks
    // contraction credit into the twist branch (or vice versa) the wrong card fires.

    @Test fun a_pure_gather_must_not_fire_a_twist_card() {
        val gather = card("g", Movement.Gather(maxSpreadRatio = 0.6f))
        val twist = card("t", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = null))
        val clf = GestureClassifier(cards = listOf(gather, twist))
        // Contract hard, ZERO rotation.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 220f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        val r = clf.classify(stream, cell)
        assertNotNull(r, "a clean contraction must match SOMETHING")
        assertEquals("g", r.card.id, "no rotation ⇒ gather, never the twist")
    }

    @Test fun a_pure_twist_must_not_fire_the_gather_card() {
        val gather = card("g", Movement.Gather(maxSpreadRatio = 0.6f))
        val twist = card("t", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = null))
        val clf = GestureClassifier(cards = listOf(gather, twist))
        // Big rotation WITH contraction — both cards' contraction terms are satisfied, only rotation differs.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 110f, 1.2f)),
        )
        val r = clf.classify(stream, cell)
        assertNotNull(r, "a contracting twist must match SOMETHING")
        // The gather is penalised by spinStillness for the rotation; the twist is rewarded for it.
        assertEquals("t", r.card.id, "a strong rotation must select the twist, not the gather sitting under it")
    }

    // ── ATTACK 2: the strictly-greater tie-break. Two equal-spec cards over one stream. ───────────────
    //
    // classify keeps a card only when `score > bestScore` (STRICT). Two identically-specced cards score
    // EXACTLY equal on the same stream; the strict comparison means the FIRST in iteration order wins and
    // the second can never displace it. This is a real determinism trap: deck order silently decides the
    // winner of a tie. Prove the behaviour is at least DETERMINISTIC (first wins) — a flake here is a bug.

    @Test fun identical_specs_tie_break_is_deterministic_first_wins() {
        val a = card("alpha", Movement.Gather(maxSpreadRatio = 0.6f))
        val b = card("beta", Movement.Gather(maxSpreadRatio = 0.6f)) // byte-identical spec
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 220f, 0f), floatArrayOf(500f, 500f, 40f, 0f)),
        )
        val first = GestureClassifier(cards = listOf(a, b)).classify(stream, cell)
        val flipped = GestureClassifier(cards = listOf(b, a)).classify(stream, cell)
        assertNotNull(first); assertNotNull(flipped)
        assertEquals("alpha", first.card.id, "on an exact tie the first-iterated card must win")
        assertEquals("beta", flipped.card.id, "tie-break tracks iteration order, not card identity — must be stable")
    }

    // ── ATTACK 3: the omitted up-left direction. An up-left swipe must commit to NOTHING. ─────────────
    //
    // The deck ships swipeys for every direction EXCEPT up-left (Deck.kt). The two nearest neighbours are
    // UP (3π/2) and LEFT (π), each π/4 ≈ 0.785 rad away from up-left (5π/4). maxDirectionErrorRad is 0.4,
    // so neither can claim it. A partial/ambiguous gesture must NOT commit. If it does, the player gets a
    // brick they did not author — a silent misfire.

    @Test fun an_up_left_swipe_matches_no_shipping_swipey() {
        // Up-left = (-x, -y). Bearing in y-down screen coords = 5π/4.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(600f, 600f, 120f, 0f), floatArrayOf(380f, 380f, 120f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNull(r, "up-left has no card and sits >0.4rad from both UP and LEFT — it must not misfire")
    }

    // ── ATTACK 4: a swipe straddling two adjacent directional cards picks the nearer, never both. ─────
    //
    // A swipe pointed RIGHT-ish but skewed toward down-right. swipey-right (0) and swipey-down-right (π/4)
    // both have a 0.4 error window. At ~0.30 rad the swipe is inside RIGHT's window and outside DOWN-RIGHT's
    // — RIGHT must win, and exactly one card fires.

    @Test fun a_skewed_right_swipe_selects_the_nearer_direction_only() {
        // dx large +, dy small + → bearing ≈ atan2(60, 300) ≈ 0.197 rad (closer to RIGHT than DOWN_RIGHT).
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(300f, 480f, 120f, 0f), floatArrayOf(600f, 540f, 120f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a clear slide must commit to a swipey")
        assertEquals("swipey-right", r.card.id, "0.2rad off +x is RIGHT, not DOWN_RIGHT (which is 0.785rad away)")
    }

    // ── ATTACK 5: direction wrap-around at the 0 / 2π seam. ───────────────────────────────────────────
    //
    // A swipe up-and-slightly-right reads near 2π (just below). swipey-up (3π/2) and swipey-up-right (7π/4)
    // bracket it. The angular-error math must wrap correctly: a bearing of ~7π/4 + ε must read as up-right,
    // not be mis-distanced across the seam to RIGHT (0). A naive |a-b| without wrap would pick the wrong card.

    @Test fun an_up_right_swipe_near_the_2pi_seam_selects_up_right_not_right() {
        // Up-right-ish: dx +, dy more negative → bearing in upper-right quadrant near 7π/4.
        // dx=+120, dy=-260 → atan2(-260,120) → normalized ≈ 5.14 rad ≈ 1.64π, between UP(1.5π) and UP_RIGHT(1.75π).
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(400f, 600f, 120f, 0f), floatArrayOf(520f, 340f, 120f, 0f)),
        )
        val r = classifier.classify(stream, cell)
        assertNotNull(r, "a clear up-right slide must commit")
        assertTrue(
            r.card.id == "swipey-up" || r.card.id == "swipey-up-right",
            "a near-seam up-right bearing must select an upper card, never RIGHT(0) across the wrap — was ${r.card.id}",
        )
    }

    // ── ATTACK 6: direction-LOCK separates two same-family twists by handedness alone. ────────────────
    //
    // Build a CW-locked and a CCW-locked twist with otherwise identical specs. A CW stream must fire the CW
    // card and a CCW stream the CCW card — the lock is the only discriminator, and it must be exact.

    @Test fun handedness_lock_distinguishes_two_otherwise_identical_twists() {
        val cw = card("cw", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = true))
        val ccw = card("ccw", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = false))
        val clf = GestureClassifier(cards = listOf(cw, ccw))

        val cwStream = SyntheticStream.stroke(
            count = 5, keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 120f, 1.0f)),
        )
        val ccwStream = SyntheticStream.stroke(
            count = 5, keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 120f, -1.0f)),
        )
        assertEquals("cw", clf.classify(cwStream, cell)?.card?.id, "a +phase (CW, y-down) twist fires the CW card")
        assertEquals("ccw", clf.classify(ccwStream, cell)?.card?.id, "a -phase (CCW) twist fires the CCW card")
    }

    // ── ATTACK 7: a CCW twist against a CW-ONLY deck commits to nothing. ──────────────────────────────
    //
    // The shipping twisty is CW-locked. A vigorous CCW twist matches the rotation magnitude but the wrong
    // handedness — the direction gate returns 0 and, with no gather tight enough to catch it, NOTHING fires.

    @Test fun ccw_twist_against_cw_only_deck_does_not_commit() {
        val cwOnly = card("cw", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = true))
        val clf = GestureClassifier(cards = listOf(cwOnly))
        // Strong CCW rotation; contraction modest so a Gather (absent here anyway) couldn't poach it.
        val stream = SyntheticStream.stroke(
            count = 5, keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 150f, -1.2f)),
        )
        assertNull(clf.classify(stream, cell), "wrong-handed twist must not fire a direction-locked card")
    }

    // ── ATTACK 8: a slow translate just UNDER minDriftPx must not fire a swipey. ──────────────────────
    //
    // scoreTranslate ramps drift from minDriftPx*0.5 to minDriftPx. A drift below half of minDriftPx scores 0.
    // The shipping swipeys use minDriftPx=80, so a 30px nudge (< 40) must not register as a placement — a
    // tiny accidental slide during a gather must not mint a brick.

    @Test fun a_tiny_slide_below_half_min_drift_does_not_fire_a_swipey() {
        // Only swipeys in the deck so there is nothing else to absorb it; 30px drift, spread/rotation held.
        val swipeyDeck = listOf(
            card("sr", Movement.Translate(minDriftPx = 80f, directionRad = Dir.RIGHT, maxDirectionErrorRad = 0.4f)),
        )
        val clf = GestureClassifier(cards = swipeyDeck)
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 120f, 0f), floatArrayOf(530f, 500f, 120f, 0f)), // +30px
        )
        assertNull(clf.classify(stream, cell), "30px < minDriftPx*0.5 (40px) ⇒ zero drift credit ⇒ no swipey")
    }

    // ── ATTACK 9: a translate that also rotates is penalised out of the swipey family. ────────────────
    //
    // Slide the whole hand right AND spin it. spinStillness should drag the translate score down; with a
    // twist card present the twist (or nothing), never the swipey, should win — a swipe that spins is not a
    // clean swipe.

    @Test fun a_spinning_slide_is_not_a_clean_swipe() {
        val swipey = card("sr", Movement.Translate(minDriftPx = 80f, directionRad = Dir.RIGHT, maxDirectionErrorRad = 0.4f))
        val clf = GestureClassifier(cards = listOf(swipey))
        // Translate +300px right while rotating the cloud ~57° (1.0 rad) — heavy spin signal.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(300f, 500f, 120f, 0f), floatArrayOf(600f, 500f, 120f, 1.0f)),
        )
        val r = clf.classify(stream, cell)
        // With only the swipey present, a heavy spin should push it under its tolerance gate ⇒ null.
        assertNull(r, "a slide with a big net rotation is not a clean translate — must not fire the swipey")
    }
}
