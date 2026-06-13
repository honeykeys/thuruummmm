package com.thrum.gesture

import com.thrum.deck.Glyph
import com.thrum.deck.GestureSpec
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thrum.haptics.haptic
import com.thuruummm.physics.Cell
import com.thuruummm.physics.Material
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — attacks the corner cases of [GestureFeatureExtractor] and the spec-scoring math through
 * the classifier. These are the inputs a real multi-finger sensor produces that a happy-path test never
 * authors: a single peak-count frame, fingers that swap identity, coincident fingers (degenerate radius),
 * NaN-bait geometry, and degenerate spec parameters. Every test is designed to surface a wrong commit or a
 * crash, not to confirm correctness.
 */
class AdversarialFeatureCornerTest {

    private val cell = Cell(3, 0)

    private fun card(id: String, movement: Movement, tol: Float = 0.2f, minFingers: Int = 4) =
        Thuruummm(
            id = id,
            gesture = GestureSpec(minFingers = minFingers, movement = movement, tolerance = tol),
            material = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2),
            rummmm = haptic("$id-r") { tick(scale = 0.5f) },
            glyph = Glyph.STUB_A,
        )

    // ── ATTACK 1: the single-peak-frame static fallback under-counts a real gather. ───────────────────
    //
    // GestureFeatureExtractor: when peak finger count occurs in exactly ONE frame, it returns a STATIC
    // feature set (spreadChange = 1f, drift 0, rotation 0) measured from that one frame — it CANNOT see the
    // contraction. A gather authored so the tightest pose is unique (the hand kept squeezing to the last
    // pressed frame, and that frame alone holds 5 fingers) would then read spreadChange = 1.0 and MISS the
    // gather entirely. This proves whether the extractor's static branch silently drops a legitimate gather.

    @Test fun a_late_fifth_finger_inflates_peak_count_and_collapses_the_gather_to_a_single_peak_frame() {
        // ATTACK on the peak-count-driven endpoint choice: 4 fingers do the whole gather; a 5th finger lands
        // ONLY on the final tight frame, raising peak count to 5 in exactly ONE frame. The extractor's
        // endpoints are chosen by PEAK count, so startIdx == endIdx == that lone frame → the STATIC branch
        // fires and the visible 4-finger contraction is NEVER measured (spreadChange reads 1.0). A clean
        // gather is thereby invisible — a real misfire risk when a thumb brushes the glass late.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        frames += PointerFrame(SyntheticStream.ring(500f, 500f, 200f, 4), t); t += SyntheticStream.FRAME_NS
        frames += PointerFrame(SyntheticStream.ring(500f, 500f, 120f, 4), t); t += SyntheticStream.FRAME_NS
        // tight 5-finger ring — the 5th finger (id=4) appears here for the ONLY time → peak=5 in one frame
        frames += PointerFrame(SyntheticStream.ring(500f, 500f, 40f, 5), t)

        val feat = GestureFeatureExtractor.extract(frames)
        assertNotNull(feat)
        // The bug this exposes: the gather's contraction is lost. The static branch reports held spread.
        // If the extractor is FIXED to measure the dominant 4-finger contraction, this assertion will fail —
        // which is the signal to retune the endpoint-selection heuristic, not a false alarm.
        assertEquals(
            1f, feat.spreadChange, 0.01f,
            "a late 5th finger collapses the window to the static branch — the 4-finger contraction is lost (spreadChange should be ~0.2 if measured)",
        )
    }

    @Test fun extractor_single_peak_frame_returns_static_features_not_a_crash() {
        // Direct attack on the static branch: peak count (5) in exactly one frame, surrounded by 4-finger
        // frames. The extractor must return a STATIC feature set (spreadChange 1, drift 0, rotation 0) and
        // never divide-by-zero or NaN.
        val frames = listOf(
            PointerFrame(SyntheticStream.ring(500f, 500f, 100f, 4), 0L),
            PointerFrame(SyntheticStream.ring(500f, 500f, 100f, 5), SyntheticStream.FRAME_NS), // lone peak
            PointerFrame(SyntheticStream.ring(500f, 500f, 100f, 4), 2 * SyntheticStream.FRAME_NS),
        )
        val feat = GestureFeatureExtractor.extract(frames)
        assertNotNull(feat, "a lone-peak window must still produce features")
        assertEquals(1f, feat.spreadChange, 0.001f, "the static branch reports held spread")
        assertEquals(0f, feat.centroidDriftPx, 0.001f)
        assertEquals(0f, feat.rotationRad, 0.001f)
        assertFalse(feat.spreadChange.isNaN(), "no NaN may leak from the static branch")
        assertEquals(5, feat.fingerCount, "the static branch counts the distinct pressed ids in the lone peak frame")
    }

    // ── ATTACK 2: fingers that swap identity between endpoints break id-tracking. ─────────────────────
    //
    // Rotation/spread are tracked per Finger.id. If a sensor re-numbers fingers (id NOT stable), the
    // start∩end id-set could be empty even with 5 pressed at both ends → extract returns null → no commit.
    // A real device keeps ids stable, but the classifier must DEGRADE SAFELY (null, no crash) if it does not.

    @Test fun fully_renumbered_fingers_yield_no_features_not_a_phantom_match() {
        val start = SyntheticStream.ring(500f, 500f, 200f, 5) // ids 0..4
        // End ring at a tight radius but with ids 100..104 — NO id overlap with start.
        val end = (0 until 5).map { i ->
            val a = (2.0 * Math.PI * i / 5).toFloat()
            Finger(id = 100L + i, x = 500f + 40f * kotlin.math.cos(a), y = 500f + 40f * kotlin.math.sin(a), pressed = true)
        }
        val frames = listOf(
            PointerFrame(start, 0L),
            PointerFrame(end, SyntheticStream.FRAME_NS),
        )
        val feat = GestureFeatureExtractor.extract(frames)
        assertNull(feat, "no finger tracked through both endpoints ⇒ no measurable gesture (must be null, not a phantom)")
    }

    @Test fun classifier_with_renumbered_fingers_commits_nothing() {
        // Same renumbering, but routed through the full classifier WITH a flourish appended. The features are
        // unmeasurable ⇒ classify must return null even though the flourish gate passes.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        frames += PointerFrame(SyntheticStream.ring(500f, 500f, 200f, 5), t); t += SyntheticStream.FRAME_NS
        // settle still at full count but with NEW ids each frame (sensor churn), then lift
        repeat(8) {
            val ids = (0 until 5).map { i -> 1000L * it + i }
            val ring = SyntheticStream.ring(500f, 500f, 40f, 5).mapIndexed { i, f -> f.copy(id = ids[i]) }
            frames += PointerFrame(ring, t); t += SyntheticStream.FRAME_NS
        }
        frames += PointerFrame(SyntheticStream.lifted(5), t)
        val clf = GestureClassifier(cards = listOf(card("g", Movement.Gather(maxSpreadRatio = 0.6f))))
        // The flourish (AllLiftAfterSettle) may or may not pass depending on id churn; the contract we assert
        // is the safe one: an unmeasurable feature set must never produce a confident commit.
        val r = clf.classify(frames, cell)
        assertNull(r, "churning finger ids leaves no trackable cloud — the classifier must not invent a match")
    }

    // ── ATTACK 3: coincident fingers (degenerate radius) must not NaN the rotation/spread. ────────────
    //
    // If every finger lands on the SAME point, startSpread ≈ 0 → spreadChange is defined-as-1, and the
    // rotation loop skips fingers within 1e-3 of the centroid. The classifier must not crash or emit NaN.

    @Test fun all_fingers_coincident_then_spreading_does_not_crash_or_nan() {
        // Start: 5 fingers stacked on one point. End: a wide ring. Expanding, not gathering.
        val coincident = (0 until 5).map { Finger(id = it.toLong(), x = 500f, y = 500f, pressed = true) }
        val wide = SyntheticStream.ring(500f, 500f, 200f, 5)
        val feat = GestureFeatureExtractor.extract(listOf(PointerFrame(coincident, 0L), PointerFrame(wide, SyntheticStream.FRAME_NS)))
        assertNotNull(feat)
        assertFalse(feat.spreadChange.isNaN(), "degenerate start spread must be guarded (defined as 1), not NaN")
        assertFalse(feat.rotationRad.isNaN(), "coincident fingers must not inject NaN rotation")
        assertFalse(feat.centroidDriftPx.isNaN(), "no NaN drift")
    }

    @Test fun a_gather_card_must_not_fire_on_an_expansion() {
        // An EXPANSION (spreadChange > 1) is the OPPOSITE of a gather. invLerpFalling(spreadChange, maxRatio, 1f)
        // returns 0 once spreadChange >= 1, so the gather score must be 0 → no commit. A spreading hand must
        // never mint the gather brick.
        val clf = GestureClassifier(cards = listOf(card("g", Movement.Gather(maxSpreadRatio = 0.6f))))
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 60f, 0f), floatArrayOf(500f, 500f, 220f, 0f)), // expand
        )
        assertNull(clf.classify(stream, cell), "an expansion is not a gather — the gather card must not fire")
    }

    // ── ATTACK 4: exact-zero net rotation skips the direction gate but must still not over-credit. ────
    //
    // scoreRotateContract guards the direction lock with `if (f.rotationRad != 0f && ...)`. A twist measured
    // at EXACTLY 0 rotation (a perfect non-spin) skips the lock — but then invLerpRising(0, 0, minRot) = 0,
    // so the rotation factor is 0 and the total is 0. A non-spinning "twist" must score 0, never sneak a
    // match via the skipped gate.

    @Test fun a_zero_rotation_contraction_does_not_fire_a_clockwise_locked_twist() {
        val clf = GestureClassifier(
            cards = listOf(card("t", Movement.RotateContract(minRotationRad = 0.5f, maxSpreadRatio = 0.8f, clockwise = true))),
        )
        // Contract with ZERO rotation — the contraction term is satisfied, the rotation term is 0.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 100f, 0f)),
        )
        assertNull(clf.classify(stream, cell), "no rotation ⇒ rotation score 0 ⇒ the twist must not fire on contraction alone")
    }

    // ── ATTACK 5: a finger that joins late must not inject a phantom angle. ────────────────────────────
    //
    // Only fingers present in BOTH endpoint frames contribute to rotation. A 5th finger that appears only at
    // the end must be excluded from the rotation mean, so a clean 4-finger gather is not turned into a false
    // twist by the latecomer.

    @Test fun a_swapped_finger_is_excluded_from_the_tracked_set() {
        // Peak count is 5 at BOTH endpoints (so the normal, non-static path runs). Both frames are clean,
        // symmetric, centred 5-rings — but in the END frame one finger's ID changed (id 4 → id 9): a sensor
        // re-numbering one contact mid-stroke. Only ids {0,1,2,3} are tracked through both endpoints; the
        // re-numbered finger must be excluded, so fingerCount drops to 4 and no phantom angle is injected.
        val start = SyntheticStream.ring(500f, 500f, 150f, 5)                       // ids 0..4
        val end = SyntheticStream.ring(500f, 500f, 150f, 5).map {                   // same geometry...
            if (it.id == 4L) it.copy(id = 9L) else it                               // ...but id 4 re-numbered to 9
        }
        val feat = GestureFeatureExtractor.extract(listOf(PointerFrame(start, 0L), PointerFrame(end, SyntheticStream.FRAME_NS)))
        assertNotNull(feat)
        assertEquals(4, feat.fingerCount, "only the 4 ids present in BOTH endpoints are tracked; the re-numbered finger is excluded")
        assertTrue(abs(feat.rotationRad) < 0.05f, "identical geometry must read ~0 rotation regardless of one id swap, was ${feat.rotationRad}")
        assertEquals(1f, feat.spreadChange, 0.02f, "identical spread ⇒ no spread change")
    }

    // ── ATTACK 6: a degenerate spec (minRotationRad = 0) must not make every contraction a twist. ─────
    //
    // invLerpRising(mag, 0f, 0f) hits the hi<=lo branch → returns 1 if mag >= 0 (ALWAYS true). A card with
    // minRotationRad = 0 would therefore award full rotation credit to ANY contraction, including a pure
    // gather. This is a spec corner the deck must avoid; this test documents the trap so a future card with
    // minRotationRad=0 is caught as the misconfiguration it is.

    @Test fun a_twist_spec_with_zero_min_rotation_degenerately_matches_any_contraction() {
        val clf = GestureClassifier(
            cards = listOf(card("t0", Movement.RotateContract(minRotationRad = 0f, maxSpreadRatio = 0.8f, clockwise = null))),
        )
        // Pure gather, no rotation.
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 500f, 200f, 0f), floatArrayOf(500f, 500f, 80f, 0f)),
        )
        val r = clf.classify(stream, cell)
        // This SHOULD be considered a misconfiguration: a 0-threshold twist swallows gathers. We pin the
        // current behaviour so a deck author is warned the moment they set minRotationRad to 0.
        assertNotNull(r, "minRotationRad=0 degenerately matches any contraction — a spec smell, pinned here")
        assertEquals("t0", r.card.id, "the degenerate twist greedily claims a pure gather — do NOT ship minRotationRad=0")
    }

    // ── ATTACK 7: a translate spec with an unset direction matches ANY swipe — separability risk. ─────
    //
    // Movement.Translate(directionRad = null) accepts any bearing. Two such cards in a deck would be
    // indistinguishable. We prove a single direction-agnostic swipey fires for both a rightward AND a
    // downward slide — confirming the GestureSpec doc warning that an undirected swipey needs high
    // specificity elsewhere.

    @Test fun an_undirected_translate_matches_every_direction() {
        val clf = GestureClassifier(
            cards = listOf(card("any", Movement.Translate(minDriftPx = 80f, directionRad = null))),
        )
        val right = SyntheticStream.stroke(count = 5, keys = listOf(floatArrayOf(300f, 500f, 120f, 0f), floatArrayOf(600f, 500f, 120f, 0f)))
        val down = SyntheticStream.stroke(count = 5, keys = listOf(floatArrayOf(500f, 300f, 120f, 0f), floatArrayOf(500f, 600f, 120f, 0f)))
        assertEquals("any", clf.classify(right, cell)?.card?.id, "an undirected swipey accepts a rightward slide")
        assertEquals("any", clf.classify(down, cell)?.card?.id, "an undirected swipey accepts a downward slide too — direction is not a discriminator here")
    }
}
