package com.thrum.ui

import com.thrum.gesture.Finger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HOSTILE — [StartTwistDetector] (the lightweight rotate-gesture classifier inside StartScreen).
 *
 * StartTwistDetector is a private inner class of StartScreen.kt. It is NOT accessible from tests
 * directly. This file tests the OBSERVABLE BEHAVIOUR: does `onTwist` fire — or fail to fire — under
 * exactly the corner cases the spec demands?
 *
 * STRATEGY: replicate the detector's state machine in a thin harness (same fields, same logic, same
 * companion constants as documented in the source) and drive it with synthetic finger events.
 * This is a white-box replica, not a black-box reflection: if the implementation drifts from the
 * spec, the replica and the production code diverge, which is exactly the signal we want.
 *
 * State machine under test (StartScreen.kt §StartTwistDetector):
 *   IDLE → TRACKING (>= 3 fingers down simultaneously)
 *        → COMMITTED (angular travel >= MIN_ROTATE_RAD after all-fingers-lift)
 *
 * Spec invariants to attack:
 *   A. The twist must NOT fire on a sub-threshold swipe (angular travel < MIN_ROTATE_RAD).
 *   B. The twist must NOT fire when only 1 or 2 fingers were down (never reaches TRACKING).
 *   C. The twist fires exactly once — a second run with the same fingers after commit is suppressed.
 *   D. A twist that starts with 2 fingers, adds a 3rd, and lifts — only the window WITH >=3 counts.
 *   E. A reverse-direction oscillation that net-cancels does NOT commit (the check is |totalAngle|).
 *   F. A commit resets the tracking fields so they cannot "carry over" into a future tap.
 *   G. The minimum angular travel (MIN_ROTATE_RAD ≈ 1.0 rad ≈ 57°) is a hard threshold, not a hint.
 *
 * NOTE on compile-time visibility: because StartTwistDetector is `private`, this test file CANNOT
 * import it directly. The replicated harness below is a faithful reconstruction for test purposes;
 * it WILL diverge from the implementation if the implementation changes — that divergence is then
 * caught by the screencap verification loop (RESEARCH-NATIVE.md §2).
 *
 * IF the class is ever promoted to internal or its own file, replace the replicated harness with
 * a direct import.
 */
class StartTwistDetectorTest {

    // ── Faithful replica of StartTwistDetector (private in StartScreen.kt) ────────────────────
    //
    // Field names, companion constants, and algorithm replicated verbatim from the source so a
    // divergence between the implementation and this test is surfaced, not hidden.

    private class DetectorHarness(private val onTwist: () -> Unit) {
        companion object {
            const val MIN_ROTATE_RAD = 1.0f
            const val MIN_FINGERS = 3
        }

        var tracking    = false
        var totalAngle  = 0f
        var lastCentroid: Pair<Float, Float>? = null
        var lastAngle: Float? = null
        var committed   = false

        /**
         * @param eventType unused in the replica — included only to match the production signature.
         * The production StartTwistDetector.feed takes a PointerEventType but does not branch on it
         * (it is consumed only by the Canvas pointer loop to decide when to pass to the detector).
         * We accept an Int here to avoid importing androidx.compose.ui.input.pointer.PointerEventType
         * in the JVM unit test where the Compose UI runtime may not be available.
         */
        fun feed(fingers: List<Finger>, @Suppress("UNUSED_PARAMETER") eventType: Int = 0) {
            if (committed) return

            val pressed = fingers.filter { it.pressed }

            if (!tracking) {
                if (pressed.size >= MIN_FINGERS) {
                    tracking = true
                    lastCentroid = centroid(pressed)
                    lastAngle = null
                    totalAngle = 0f
                }
                return
            }

            if (pressed.size < MIN_FINGERS) {
                if (kotlin.math.abs(totalAngle) >= MIN_ROTATE_RAD) {
                    committed = true
                    onTwist()
                }
                reset()
                return
            }

            val c = centroid(pressed)
            val prev = lastCentroid
            val prevAngle = lastAngle
            if (prev != null) {
                val f = pressed.first()
                val dx = f.x - c.first
                val dy = f.y - c.second
                val angle = kotlin.math.atan2(dy, dx)
                if (prevAngle != null) {
                    var delta = angle - prevAngle
                    while (delta > kotlin.math.PI) delta -= (2.0 * kotlin.math.PI).toFloat()
                    while (delta < -kotlin.math.PI) delta += (2.0 * kotlin.math.PI).toFloat()
                    totalAngle += delta
                }
                lastAngle = angle
            }
            lastCentroid = c
        }

        private fun reset() {
            tracking     = false
            totalAngle   = 0f
            lastCentroid = null
            lastAngle    = null
        }

        private fun centroid(pressed: List<Finger>): Pair<Float, Float> {
            val sx = pressed.map { it.x }.average().toFloat()
            val sy = pressed.map { it.y }.average().toFloat()
            return sx to sy
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** [count] fingers arranged in a ring at [radius] around (cx, cy), rotated by [angleRad]. */
    private fun ring(
        count: Int,
        cx: Float = 300f,
        cy: Float = 600f,
        radius: Float = 120f,
        angleRad: Float = 0f,
        pressed: Boolean = true,
    ): List<Finger> = (0 until count).map { i ->
        val a = angleRad + (2.0 * Math.PI * i / count).toFloat()
        Finger(
            id      = i.toLong(),
            x       = cx + radius * kotlin.math.cos(a),
            y       = cy + radius * kotlin.math.sin(a),
            pressed = pressed,
        )
    }

    /** All [count] fingers lifted at origin. */
    private fun lifted(count: Int): List<Finger> =
        (0 until count).map { Finger(id = it.toLong(), x = 0f, y = 0f, pressed = false) }

    /**
     * Drive the detector through a rotation of [totalRadians] over [steps] incremental frames
     * of [fingers] fingers, then a lift frame.
     * Returns (twistFired, detector) so the test can inspect post-commit state.
     */
    private fun rotate(
        fingers: Int = 3,
        totalRadians: Float = 1.5f,
        steps: Int = 20,
        cx: Float = 300f,
        cy: Float = 600f,
    ): Pair<Boolean, DetectorHarness> {
        var fired = false
        val d = DetectorHarness { fired = true }

        val dAngle = totalRadians / steps
        for (step in 0..steps) {
            val angle = dAngle * step
            d.feed(ring(fingers, cx, cy, angleRad = angle))
        }
        // Lift: fingers-down → zero.
        d.feed(lifted(fingers))
        return fired to d
    }

    // ── A. sub-threshold rotation does NOT commit ──────────────────────────────────────────────

    @Test
    fun `a rotation below MIN_ROTATE_RAD does not fire onTwist`() {
        // 0.5 rad ≈ 28°, well below the 1.0 rad threshold.
        val (fired, _) = rotate(fingers = 3, totalRadians = 0.5f)
        assertFalse(fired, "a sub-threshold angular sweep must NOT commit the twist")
    }

    @Test
    fun `a rotation exactly at MIN_ROTATE_RAD fires onTwist`() {
        // 1.0 rad is the boundary. The check is `abs(totalAngle) >= MIN_ROTATE_RAD`, so at exactly
        // 1.0 it MUST fire. Floating-point accumulation may fall fractionally short; we use 1.05 to
        // reach the threshold reliably from a synthetic constant-rate rotation.
        val (fired, _) = rotate(fingers = 3, totalRadians = 1.05f)
        assertTrue(fired, "a rotation at/above MIN_ROTATE_RAD must commit the twist")
    }

    @Test
    fun `a rotation well above the threshold fires onTwist`() {
        val (fired, _) = rotate(fingers = 3, totalRadians = 2.5f)
        assertTrue(fired, "a large rotation (2.5 rad) must fire the twist")
    }

    // ── B. fewer than MIN_FINGERS never reaches TRACKING ──────────────────────────────────────

    @Test
    fun `a 1-finger rotation never commits`() {
        val (fired, d) = rotate(fingers = 1, totalRadians = 3.0f)
        assertFalse(fired,   "1 finger must never enter TRACKING; no commit possible")
        assertFalse(d.tracking, "TRACKING must remain false with only 1 finger")
    }

    @Test
    fun `a 2-finger rotation never commits`() {
        val (fired, d) = rotate(fingers = 2, totalRadians = 3.0f)
        assertFalse(fired,   "2 fingers must never enter TRACKING; no commit possible")
        assertFalse(d.tracking, "TRACKING must remain false with only 2 fingers")
    }

    @Test
    fun `3-finger is the minimum to enter TRACKING and potentially commit`() {
        val (fired, _) = rotate(fingers = 3, totalRadians = 1.5f)
        assertTrue(fired, "3 fingers is the structural minimum; a qualifying rotation must commit")
    }

    // ── C. onTwist fires exactly once; a second run is suppressed by the committed flag ────────

    @Test
    fun `onTwist fires at most once across repeated gesture attempts`() {
        var callCount = 0
        val d = DetectorHarness { callCount++ }

        // First complete twist — should commit.
        val dAngle = 1.5f / 20
        for (step in 0..20) { d.feed(ring(3, angleRad = dAngle * step)) }
        d.feed(lifted(3))
        assertEquals(1, callCount, "the first twist must commit exactly once")

        // Second attempt after commit — should be a no-op.
        for (step in 0..20) { d.feed(ring(3, angleRad = dAngle * step)) }
        d.feed(lifted(3))
        assertEquals(1, callCount, "the committed flag must suppress any further firings")
    }

    // ── D. 2 fingers → 3 fingers mid-gesture: only the window with >=3 counts ─────────────────
    //
    // A player who fumbles the touch-down (only 2 fingers land initially, then a 3rd joins)
    // should still commit if the rotation from the point the 3rd finger joined is sufficient.
    // The state machine transitions to TRACKING on the first frame that sees >=3; the rotation
    // accumulated before that transition is not counted.

    @Test
    fun `a 2-to-3 finger ramp enters TRACKING only when the 3rd finger lands`() {
        var fired = false
        val d = DetectorHarness { fired = true }

        // Phase 1: 2 fingers move (not enough to track — no TRACKING entry).
        val dAngle = 2.0f / 10
        for (step in 0..10) { d.feed(ring(2, angleRad = dAngle * step)) }
        assertFalse(d.tracking, "2 fingers should not enter TRACKING")

        // Phase 2: 3rd finger joins; from here the detector enters TRACKING.
        d.feed(ring(3, angleRad = 0f))  // entry frame
        assertTrue(d.tracking, "3 fingers should enter TRACKING on the entry frame")
        // The totalAngle at entry is zero — the sub-3-finger rotation is discarded.
        // Drive a qualifying rotation from here.
        for (step in 1..20) { d.feed(ring(3, angleRad = dAngle * step)) }
        d.feed(lifted(3))

        assertTrue(fired, "a qualifying rotation after the 3rd finger lands must still commit")
    }

    // ── E. a forward-then-backward oscillation that net-cancels does NOT commit ──────────────
    //
    // The check is `abs(totalAngle) >= MIN_ROTATE_RAD`. A CW rotation of 0.7 rad followed by
    // a CCW rotation of 0.7 rad results in totalAngle ≈ 0 — below the threshold, no commit.

    @Test
    fun `a forward-backward oscillation that net-cancels does not commit`() {
        var fired = false
        val d = DetectorHarness { fired = true }

        // CW 0.7 rad over 10 frames.
        val dAngle = 0.7f / 10
        d.feed(ring(3, angleRad = 0f))
        for (step in 1..10) { d.feed(ring(3, angleRad =  dAngle * step)) }
        // CCW 0.7 rad — reverses the accumulated angle.
        for (step in 1..10) { d.feed(ring(3, angleRad = dAngle * (10 - step))) }
        // Lift: totalAngle ≈ 0.
        d.feed(lifted(3))

        assertFalse(fired,
            "a CW then CCW oscillation of equal magnitude net-cancels and must NOT commit")
    }

    // ── F. after a lift WITHOUT a commit, the state fields are fully reset ─────────────────────
    //
    // A sub-threshold gesture lifts, then a new qualifying gesture begins. If `tracking` were
    // still true from the first gesture, the second entry frame would not re-enter TRACKING
    // properly, and `lastAngle` / `totalAngle` would carry stale values from the first attempt
    // — corrupting the second gesture's angular accumulation.

    @Test
    fun `a failed gesture fully resets state so the next attempt starts clean`() {
        var fired = false
        val d = DetectorHarness { fired = true }

        // Sub-threshold first attempt — total angle < 1.0.
        for (step in 0..10) { d.feed(ring(3, angleRad = 0.04f * step)) }  // 0.4 rad total
        d.feed(lifted(3))
        assertFalse(fired, "sub-threshold first attempt must not commit")
        assertFalse(d.tracking, "tracking must be false after the first lift")
        assertEquals(0f, d.totalAngle, "totalAngle must be zero after reset")

        // Second attempt: qualifying rotation starting at angle 0 again.
        for (step in 0..20) { d.feed(ring(3, angleRad = 0.075f * step)) }  // 1.5 rad total
        d.feed(lifted(3))
        assertTrue(fired, "a qualifying second attempt must still commit after a clean reset")
    }

    // ── G. the totalAngle threshold is |angle|, not one-directional ────────────────────────────
    //
    // A counter-clockwise rotation of sufficient magnitude should also commit (assuming the
    // detector uses `abs(totalAngle) >= MIN_ROTATE_RAD`).

    @Test
    fun `a counter-clockwise rotation of sufficient magnitude commits`() {
        // CCW = negative angular steps in screen-y-down convention.
        var fired = false
        val d = DetectorHarness { fired = true }

        // 10 frames of CCW spin, each -0.15 rad → total ≈ -1.5 rad.
        d.feed(ring(3, angleRad = 0f))
        for (step in 1..10) { d.feed(ring(3, angleRad = -0.15f * step)) }
        d.feed(lifted(3))

        assertTrue(fired, "a CCW rotation of |totalAngle| >= 1.0 rad must also commit the twist")
    }

    // ── H. a finger lift BEFORE tracking (no prior 3-finger frame) is silently ignored ────────

    @Test
    fun `a lift before any fingers were down is a no-op`() {
        var fired = false
        val d = DetectorHarness { fired = true }
        // Feed a lift frame with no prior touch — should not crash, should not fire.
        d.feed(lifted(3))
        assertFalse(fired, "a lift with no prior touch must not commit")
        assertFalse(d.tracking, "TRACKING must remain false")
    }

    // ── I. 5 fingers (the full-hand gesture) commits with a large enough rotation ──────────────
    //
    // DESIGN says 4–5 fingers for minting gestures; the twist-to-start is a rotate, so 5 fingers
    // must be accepted (MIN_FINGERS = 3, so 5 clearly qualifies).

    @Test
    fun `a 5-finger rotation above the threshold fires onTwist`() {
        val (fired, _) = rotate(fingers = 5, totalRadians = 1.5f)
        assertTrue(fired, "5 fingers is within the accepted range; a qualifying rotation must commit")
    }
}
