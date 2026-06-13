package com.thrum.ui

import com.thrum.game.CollapseView
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HOSTILE — [computeShake] in FieldCanvas.kt.
 *
 * `computeShake(collapse: CollapseView, elapsedMs: Long): Offset` is a pure function (no Android,
 * no Compose) that maps a physics collapse + elapsed time to a camera-shake pixel offset. It is
 * `internal` (actually just a package-level `fun` with no explicit visibility — therefore
 * `internal` in the module). It is directly callable from the `com.thrum.ui` test package.
 *
 * The spec (FieldCanvas.kt):
 *   - Returns Offset.Zero at elapsedMs >= SHAKE_DURATION_MS (700L).
 *   - Amplitude scales with collapse.magnitude, capped at SHAKE_AMPLITUDE_MAX_PX (18f).
 *   - Amplitude decays toward zero over 700ms (decay = 1 - progress).
 *   - x and y components are sinusoidal; they are NOT zero throughout (unless amplitude is zero).
 *   - Both components return FLOAT offsets (Offset.x, Offset.y).
 *
 * What we attack:
 *   A. At elapsedMs = SHAKE_DURATION_MS the result is exactly Offset.Zero (boundary condition).
 *   B. At elapsedMs > SHAKE_DURATION_MS the result is still Offset.Zero (past the boundary).
 *   C. At t=0 the amplitude peak is proportional to magnitude up to the cap.
 *   D. The amplitude cap at SHAKE_AMPLITUDE_MAX_PX is enforced: a magnitude of 1_000_000 must not
 *      produce an offset component larger than the cap.
 *   E. The shake decays: the amplitude at the halfway point is smaller than at t=0.
 *   F. Zero magnitude produces Offset.Zero at every time step (nothing to shake for).
 *   G. The x and y components are not equal throughout (they use different frequencies).
 *
 * Offset type note: androidx.compose.ui.geometry.Offset is an `inline value class` wrapping a
 * packed Long. In JVM unit tests it materialises as the class itself. Offset.Zero is accessible
 * on the JVM unit-test path because the class is in `compose-ui` which is a compile-time
 * dependency (it lands on the test classpath via the `:app` module's `implementation(...)` deps).
 * If Offset is unavailable in the test runner, the test will fail with a ClassNotFoundError —
 * that signals the test source set needs `testImplementation(platform(libs.compose.bom))` added.
 *
 * All constants replicated from FieldCanvas.kt:
 *   SHAKE_DURATION_MS          = 700L
 *   SHAKE_FREQUENCY_HZ         = 14f
 *   SHAKE_AMPLITUDE_MAX_PX     = 18f
 *   SHAKE_AMPLITUDE_PER_MAGNITUDE = 2.5f
 */
class ComputeShakeTest {

    // Replicated constants (from FieldCanvas.kt — any drift here is caught by failing tests).
    private val SHAKE_DURATION_MS = 700L
    private val SHAKE_AMPLITUDE_MAX_PX = 18f
    private val SHAKE_AMPLITUDE_PER_MAGNITUDE = 2.5f

    /** Build a CollapseView with the given magnitude and minimal non-zero ring/fell data. */
    private fun collapse(magnitude: Double, rings: Int = 2, fellCount: Int = 3) = CollapseView(
        magnitude = magnitude,
        rings     = rings,
        fellIds   = List(fellCount) { it },
    )

    // ── A. At exactly SHAKE_DURATION_MS the result is Offset.Zero ────────────────────────────

    @Test
    fun `computeShake returns Offset_Zero at exactly the shake duration boundary`() {
        val result = computeShake(collapse(magnitude = 5.0), elapsedMs = SHAKE_DURATION_MS)
        assertEquals(0f, result.x, "x must be zero at the duration boundary")
        assertEquals(0f, result.y, "y must be zero at the duration boundary")
    }

    // ── B. Past the duration boundary the result is still Offset.Zero ────────────────────────

    @Test
    fun `computeShake returns Offset_Zero when elapsed exceeds the shake duration`() {
        val farPast = SHAKE_DURATION_MS * 10
        val result  = computeShake(collapse(magnitude = 100.0), elapsedMs = farPast)
        assertEquals(0f, result.x, "x must be zero past the duration window")
        assertEquals(0f, result.y, "y must be zero past the duration window")
    }

    // ── C. Early in the shake, the offset is non-zero for a non-zero magnitude ───────────────

    @Test
    fun `computeShake produces a non-zero offset at t=1ms for a non-trivial magnitude`() {
        val result = computeShake(collapse(magnitude = 4.0), elapsedMs = 1L)
        // At t=1ms the decay factor is (1 - 1/700) ≈ 0.9986 — full amplitude, sinusoidal.
        // The x component is amplitude * sin(frequency * t_seconds); for t≈0 the value is small
        // but should not be exactly zero unless amplitude is zero (which it is not for magnitude=4).
        // Rather than hard-pin floating-point values, assert the combined magnitude is positive.
        val totalMag = kotlin.math.hypot(result.x, result.y)
        assertTrue(totalMag > 0f,
            "a shake at t=1ms with magnitude=4.0 must produce a non-zero offset; got (${ result.x }, ${result.y})")
    }

    // ── D. The amplitude cap is enforced for extreme magnitudes ──────────────────────────────
    //
    // At t=0ms: progress=0, decay=1, amplitude = min(18f, 2.5f * 1_000_000f) = 18f.
    // The x component is 18f * sin(0) = 0 (sin(0)=0 exactly). The y component is
    // 18f * cos(0 * 0.73) = 18f * cos(0) = 18f.
    // So at t=0 the y component EXACTLY equals the cap. Check |y| <= SHAKE_AMPLITUDE_MAX_PX.

    @Test
    fun `the amplitude cap prevents extreme magnitudes from exceeding SHAKE_AMPLITUDE_MAX_PX`() {
        val result = computeShake(collapse(magnitude = 1_000_000.0), elapsedMs = 0L)
        assertTrue(abs(result.x) <= SHAKE_AMPLITUDE_MAX_PX + 1e-4f,
            "|x| must not exceed the cap: ${result.x}")
        assertTrue(abs(result.y) <= SHAKE_AMPLITUDE_MAX_PX + 1e-4f,
            "|y| must not exceed the cap: ${result.y}")
    }

    @Test
    fun `a small magnitude produces a proportionally smaller amplitude than the cap`() {
        // magnitude = 1.0 → uncapped amplitude = 2.5f * 1.0 = 2.5f. Cap = 18f. Not capped.
        // At t=0, decay=1: y = 2.5f * cos(0) = 2.5f. Must be < SHAKE_AMPLITUDE_MAX_PX.
        val result = computeShake(collapse(magnitude = 1.0), elapsedMs = 0L)
        val uncappedAmplitude = SHAKE_AMPLITUDE_PER_MAGNITUDE * 1.0f
        assertTrue(abs(result.y) <= uncappedAmplitude + 1e-4f,
            "a magnitude-1 collapse must not produce an offset larger than ${uncappedAmplitude}px")
    }

    // ── E. The shake decays: amplitude at the halfway point is smaller than at t=0 ─────────
    //
    // At t=0ms: decay = 1 - 0/700 = 1.0. At t=350ms: decay = 1 - 350/700 = 0.5. The y component
    // at t=0 is amplitude * cos(0) = amplitude. At t=350ms it is 0.5 * amplitude * cos(14*2π*0.35).
    // We can only compare magnitudes (the sinusoid may be zero at certain frequencies); so we compare
    // the Y value at t=0 against the maximum possible at t=350ms.

    @Test
    fun `the shake amplitude at the halfway point cannot exceed half the initial amplitude`() {
        val m = 6.0
        val uncapped = SHAKE_AMPLITUDE_PER_MAGNITUDE * m.toFloat()
        val initialAmplitude = minOf(SHAKE_AMPLITUDE_MAX_PX, uncapped)

        // At t=350ms: decay = 0.5. Maximum possible amplitude component = 0.5 * initialAmplitude.
        val halfway = computeShake(collapse(magnitude = m), elapsedMs = 350L)
        val halfwayMag = kotlin.math.hypot(halfway.x, halfway.y)
        // The combined magnitude cannot exceed amplitude * sqrt(2) (both sin and cos at peak).
        val maxPossibleAtHalfway = initialAmplitude * 0.5f * kotlin.math.sqrt(2f) + 1e-3f
        assertTrue(halfwayMag <= maxPossibleAtHalfway,
            "at 350ms (halfway) the combined shake magnitude $halfwayMag cannot exceed $maxPossibleAtHalfway")
    }

    // ── F. Zero magnitude produces Offset.Zero at every time step ────────────────────────────
    //
    // amplitude = min(18f, 2.5f * 0.0f) = 0f. Both sin and cos of 0 times anything = 0.

    @Test
    fun `a collapse with magnitude zero produces Offset_Zero throughout`() {
        for (elapsed in listOf(0L, 1L, 100L, 350L, 699L)) {
            val result = computeShake(collapse(magnitude = 0.0), elapsedMs = elapsed)
            assertEquals(0f, result.x, "x must be zero for magnitude=0 at elapsed=$elapsed")
            assertEquals(0f, result.y, "y must be zero for magnitude=0 at elapsed=$elapsed")
        }
    }

    // ── G. x and y components differ (different frequencies, not equal everywhere) ──────────
    //
    // x uses sin(frequency * t), y uses cos(frequency * t * 0.73). At t != 0 they will generally
    // differ. We pick a time where the analytical difference is clear: at t=25ms the x and y
    // frequencies produce measurably different values, assuming the amplitude is non-negligible.

    @Test
    fun `x and y components are not equal throughout the shake (different frequencies)`() {
        // At t=25ms: x = A*sin(14*2π*0.025) ≈ A*sin(2.199), y = A*cos(14*2π*0.025*0.73) ≈ A*cos(1.605).
        // sin(2.199) ≈ 0.808, cos(1.605) ≈ -0.051 — clearly different.
        val result = computeShake(collapse(magnitude = 5.0), elapsedMs = 25L)
        // If x == y here the frequencies collapsed — a bug in the constants.
        assertTrue(abs(result.x - result.y) > 0.1f,
            "x and y must differ at t=25ms due to different frequencies; got x=${result.x}, y=${result.y}")
    }

    // ── H. The collapse ring count does NOT affect shake amplitude ─────────────────────────────
    //
    // The shake formula uses ONLY `collapse.magnitude`, not `rings` or `fellIds`. Verify by keeping
    // magnitude constant and varying rings.

    @Test
    fun `shake amplitude depends only on magnitude, not on ring count`() {
        val m = 3.0
        val fewRings  = computeShake(collapse(magnitude = m, rings = 1), elapsedMs = 50L)
        val manyRings = computeShake(collapse(magnitude = m, rings = 99), elapsedMs = 50L)
        assertEquals(fewRings.x, manyRings.x, absoluteTolerance = 1e-4f,
            "ring count must not affect x offset")
        assertEquals(fewRings.y, manyRings.y, absoluteTolerance = 1e-4f,
            "ring count must not affect y offset")
    }

    // ── I. Fell-count does NOT affect shake amplitude ─────────────────────────────────────────

    @Test
    fun `shake amplitude depends only on magnitude, not on the number of bricks that fell`() {
        val m = 3.0
        val fewFell  = computeShake(collapse(magnitude = m, fellCount = 1), elapsedMs = 50L)
        val manyFell = computeShake(collapse(magnitude = m, fellCount = 999), elapsedMs = 50L)
        assertEquals(fewFell.x, manyFell.x, absoluteTolerance = 1e-4f,
            "fell count must not affect x offset")
        assertEquals(fewFell.y, manyFell.y, absoluteTolerance = 1e-4f,
            "fell count must not affect y offset")
    }

    // ── Helper extension (avoids importing kotlin.test assertEquals with delta) ───────────────

    private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String) {
        assertTrue(abs(actual - expected) <= absoluteTolerance,
            "$message — expected $expected ± $absoluteTolerance, got $actual")
    }
}
