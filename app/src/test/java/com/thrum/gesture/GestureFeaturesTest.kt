package com.thrum.gesture

import kotlin.math.PI
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit proof for [GestureFeatureExtractor] — the geometry beneath recognition. Attacks each of the
 * three measured signals (drift, spread, rotation) in isolation against hand-authored frames, so a
 * regression in the math is caught here before it can confuse the classifier.
 */
class GestureFeaturesTest {

    private fun frames(vararg f: Pair<List<Finger>, Long>): List<PointerFrame> =
        f.map { PointerFrame(it.first, it.second) }

    @Test fun empty_or_single_frame_yields_no_features() {
        assertNull(GestureFeatureExtractor.extract(emptyList()))
        assertNull(GestureFeatureExtractor.extract(listOf(PointerFrame(SyntheticStream.ring(0f, 0f, 100f, 5), 0L))))
    }

    @Test fun pure_translation_reads_as_drift_with_no_rotation_no_spread_change() {
        val start = SyntheticStream.ring(100f, 100f, 80f, 5)
        // Same ring shifted +200x, +0y: every finger moves identically.
        val end = start.map { it.copy(x = it.x + 200f) }
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertEquals(5, feat.fingerCount)
        assertEquals(200f, feat.centroidDriftPx, 0.5f)
        assertEquals(0f, feat.driftDirectionRad, 0.05f)               // +x ⇒ ~0 rad
        assertEquals(1f, feat.spreadChange, 0.02f)                    // spread held
        assertTrue(kotlin.math.abs(feat.rotationRad) < 0.02f, "translation must show ~0 rotation, was ${feat.rotationRad}")
    }

    @Test fun pure_contraction_reads_as_small_spread_change_no_drift_no_rotation() {
        val start = SyntheticStream.ring(300f, 300f, 200f, 5)
        val end = SyntheticStream.ring(300f, 300f, 50f, 5)            // same centre, quarter radius
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertEquals(0.25f, feat.spreadChange, 0.02f)
        assertTrue(feat.centroidDriftPx < 1f, "a centred contraction must not drift, was ${feat.centroidDriftPx}")
        assertTrue(kotlin.math.abs(feat.rotationRad) < 0.05f, "contraction must show ~0 rotation, was ${feat.rotationRad}")
    }

    @Test fun clockwise_rotation_reads_as_positive_rotation() {
        // y-down screen coords: increasing the polygon phase rotates clockwise on screen.
        val start = SyntheticStream.ring(400f, 400f, 150f, 5, phaseRad = 0f)
        val end = SyntheticStream.ring(400f, 400f, 150f, 5, phaseRad = 0.6f)   // +0.6 rad each finger
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertEquals(0.6f, feat.rotationRad, 0.05f)                  // signed mean ≈ the applied phase
        assertTrue(feat.rotationRad > 0f, "a +phase step is clockwise (positive) in y-down coords")
    }

    @Test fun counterclockwise_rotation_reads_as_negative_rotation() {
        val start = SyntheticStream.ring(400f, 400f, 150f, 5, phaseRad = 0f)
        val end = SyntheticStream.ring(400f, 400f, 150f, 5, phaseRad = -0.6f)
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertTrue(feat.rotationRad < 0f, "a -phase step is counter-clockwise (negative), was ${feat.rotationRad}")
    }

    @Test fun upward_swipe_direction_is_three_halves_pi() {
        val start = SyntheticStream.ring(500f, 600f, 80f, 5)
        val end = start.map { it.copy(y = it.y - 250f) }             // -y = up in screen coords
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertEquals((3 * PI / 2).toFloat(), feat.driftDirectionRad, 0.05f)
    }

    @Test fun finger_count_tracks_only_fingers_present_through_both_endpoints() {
        // Start with 5 fingers; one (id=4) is absent at the end. Only the 4 tracked through count.
        val start = SyntheticStream.ring(200f, 200f, 100f, 5)
        val end = SyntheticStream.ring(200f, 200f, 60f, 5).filter { it.id != 4L }
        val feat = GestureFeatureExtractor.extract(frames(start to 0L, end to SyntheticStream.FRAME_NS))
        assertNotNull(feat)
        assertEquals(4, feat.fingerCount, "only fingers in BOTH endpoints are tracked")
    }
}

/**
 * Unit proof for the [Flourish] strategies in isolation — the uniform commit predicate.
 */
class FlourishTest {

    private val flourish = AllLiftAfterSettle()

    @Test fun settle_then_together_lift_commits() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(400f, 400f, 200f, 0f), floatArrayOf(400f, 400f, 60f, 0f)),
        )
        assertTrue(flourish.committed(stream), "a settle followed by a together-lift is the flourish")
    }

    @Test fun no_lift_does_not_commit() {
        val stream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(400f, 400f, 200f, 0f), floatArrayOf(400f, 400f, 60f, 0f)),
            withFlourish = false,
        )
        assertTrue(!flourish.committed(stream), "fingers still down ⇒ no commit")
    }

    @Test fun lift_without_settle_does_not_commit() {
        // Build a fast-moving contraction that lifts with no still tail.
        val frames = mutableListOf<PointerFrame>()
        var t = 0L
        for (rad in listOf(220f, 140f, 70f, 30f)) {
            frames += PointerFrame(SyntheticStream.ring(400f, 400f, rad, 5), t); t += SyntheticStream.FRAME_NS
        }
        frames += PointerFrame(SyntheticStream.lifted(5), t)
        assertTrue(!flourish.committed(frames), "no still settle before lift ⇒ not a flourish")
    }

    @Test fun deliberate_hold_form_commits_on_a_long_still_hold() {
        val hold = DeliberateHold(holdMs = 200f)
        // 20 still frames at 16ms = 320ms hold at full count, no lift.
        val frames = (0 until 20).map { PointerFrame(SyntheticStream.ring(400f, 400f, 120f, 5), it * SyntheticStream.FRAME_NS) }
        assertTrue(hold.committed(frames), "a long still hold is the hold-form flourish")
    }

    @Test fun deliberate_hold_does_not_commit_while_hand_is_moving() {
        val hold = DeliberateHold(holdMs = 200f)
        val frames = (0 until 20).map {
            PointerFrame(SyntheticStream.ring(400f + it * 20f, 400f, 120f, 5), it * SyntheticStream.FRAME_NS)
        }
        assertTrue(!hold.committed(frames), "a moving hand never satisfies the hold-still requirement")
    }
}
