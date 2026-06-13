package com.thrum.haptics

import com.thuruummm.physics.Brick
import com.thuruummm.physics.Cell
import com.thuruummm.physics.CollapseResult
import com.thuruummm.physics.Grid
import com.thuruummm.physics.Material
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — every THRUUMMMM the engine can emit must be a wave the REAL android
 * VibrationEffect.createWaveform(timings, amplitudes, repeat) will accept. If [Thruummm] ever emits
 * a malformed envelope, HapticEngine hands it straight to createWaveform and the app crashes on the
 * device at the worst possible moment — mid-collapse, the toy's payoff.
 *
 * The android preconditions asserted here are primary-source verified (2026-06-13):
 *  - createWaveform throws IllegalArgumentException if timings.length != amplitudes.length.
 *    Source: android.googlesource.com/platform/frameworks/base .../os/VibrationEffect.java —
 *    `if (timings.length != amplitudes.length) throw new IllegalArgumentException(...)`.
 *  - amplitude values must be between 0 and 255, or == DEFAULT_AMPLITUDE(-1).
 *    Source: same file + developer.android.com/reference/android/os/VibrationEffect (createWaveform
 *    @param amplitudes "must be between 0 and 255, or equal to DEFAULT_AMPLITUDE").
 *  - a waveform of all-zero amplitudes is silent (motor never turns on): a valid object but a dead
 *    THRUUMMMM — the reward would be felt as nothing. DESIGN.md: "even the smallest collapse must
 *    vibrate." We attack that too.
 *
 * The existing ThruummmMappingTest asserts the FEEL (complexity beats size, monotonic energy). This
 * file is disjoint: it attacks the HARDWARE CONTRACT and the degenerate / hostile collapse inputs
 * the mapping test never feeds (zero components, vast magnitude, lopsided rings vs fell).
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.ThruummmWaveContractTest"
 */
class ThruummmWaveContractTest {

    private val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    private val wood = Material(1.5, 5.0, 2, 3, 0.1)
    private val glass = Material(1.0, 2.0, 0, 1, 0.9)
    private val stone = Material(3.0, 9.0, 0, 4, 0.5)

    private fun brick(id: Int, m: Material) = Brick(id, m, Cell(id, 0))

    private fun collapse(fell: List<Brick>, rings: Int, materials: Set<Material>) =
        CollapseResult(fell = fell, rings = rings, materials = materials, finalGrid = Grid())

    /**
     * The single assertion the device will make for us, made here on the JVM: a wave is well-formed
     * iff createWaveform would accept it. Mirrors the verified AOSP precondition exactly.
     */
    private fun assertAndroidWillAccept(w: Haptic.Wave, ctx: String) {
        assertEquals(
            w.timings.size,
            w.amplitudes.size,
            "[$ctx] createWaveform throws IllegalArgumentException when timings.length != amplitudes.length (verified AOSP VibrationEffect.java)",
        )
        assertTrue(w.timings.isNotEmpty(), "[$ctx] an empty waveform plays nothing — every collapse must produce a real THRUUMMMM")
        w.timings.forEach { t ->
            assertTrue(t >= 0L, "[$ctx] negative segment duration is nonsensical for a waveform (got $t)")
        }
        w.amplitudes.forEach { a ->
            assertTrue(
                a in 0..255 || a == -1,
                "[$ctx] amplitude must be in 0..255 or DEFAULT_AMPLITUDE(-1); got $a — createWaveform will reject it on-device",
            )
        }
        assertTrue(w.amplitudes.any { it > 0 }, "[$ctx] a THRUUMMMM of all-zero amplitudes is a silent reward — the collapse would be felt as nothing")
    }

    // ── the corners of the collapse-component space ─────────────────────────────────────────────

    @Test
    fun `the smallest possible real collapse emits an android-valid wave`() {
        val w = Thruummm.forCollapse(collapse(listOf(brick(0, pebble)), rings = 1, materials = setOf(pebble)))
        assertAndroidWillAccept(w, "1 brick, 1 ring, 1 material")
    }

    @Test
    fun `a vast collapse far past the magnitude soft cap still emits an android-valid wave`() {
        // 500 bricks × 12 rings × 4 materials = magnitude 24_000, ~20x the soft cap (1200). The
        // intensity curve must saturate, the slam amplitude must clamp at 255, pulses must stay <=
        // MAX_TUMBLE_PULSES, and NOTHING may overflow the 0..255 band. A naive lerp without a final
        // coerce would peg amplitude > 255 here and crash createWaveform.
        val w = Thruummm.forCollapse(
            collapse(
                fell = (0 until 500).map { brick(it, listOf(pebble, wood, glass, stone)[it % 4]) },
                rings = 12,
                materials = setOf(pebble, wood, glass, stone),
            ),
        )
        assertAndroidWillAccept(w, "500 bricks, 12 rings, 4 materials (far past soft cap)")
        assertTrue(w.amplitudes.all { it <= 255 }, "saturation must hold the amplitude band even at ~20x the soft cap")
    }

    @Test
    fun `a single-ring thousand-brick plain tower stays android-valid and capped in pulse count`() {
        val w = Thruummm.forCollapse(
            collapse((0 until 1000).map { brick(it, pebble) }, rings = 1, materials = setOf(pebble)),
        )
        assertAndroidWillAccept(w, "1000 bricks, 1 ring, 1 material")
        // rings == 1 is coerced up to MIN_TUMBLE_PULSES, never down to 0 — a 0-pulse tumble would
        // still have the slam, but the design guarantees a minimum tumble before the slam.
        val tumblePulses = w.amplitudes.dropLast(1).count { it > 0 }
        assertTrue(tumblePulses >= 1, "even a 1-ring collapse tumbles before it slams (MIN_TUMBLE_PULSES floor)")
    }

    @Test
    fun `many rings but few bricks still produces a capped, valid wave`() {
        // Lopsided: rings far exceed fell. pulses must clamp at MAX_TUMBLE_PULSES (7), not grow with
        // rings unboundedly, or the wave array balloons and the crash rattles forever.
        val w = Thruummm.forCollapse(
            collapse((0 until 2).map { brick(it, glass) }, rings = 999, materials = setOf(glass)),
        )
        assertAndroidWillAccept(w, "2 bricks, 999 rings, 1 material")
        val tumblePulses = w.amplitudes.dropLast(1).count { it > 0 }
        assertTrue(tumblePulses <= 7, "tumble pulses must be capped (MAX_TUMBLE_PULSES) regardless of ring count; got $tumblePulses")
    }

    @Test
    fun `high material variety does not push any pulse amplitude out of range`() {
        // Variety widens the alternating amplitude swing (textureSwing). At max variety the *louder*
        // alternate pulses are scaled UP — they must still clamp at 255, and the *softer* ones must
        // not drop below 0. This is exactly where an un-coerced swing would breach the band.
        val w = Thruummm.forCollapse(
            collapse(
                fell = (0 until 8).map { brick(it, listOf(pebble, wood, glass, stone)[it % 4]) },
                rings = 5,
                materials = setOf(pebble, wood, glass, stone),
            ),
        )
        assertAndroidWillAccept(w, "max variety, mid magnitude")
    }

    @Test
    fun `forComponents tolerates a zero or negative magnitude without breaking the wave`() {
        // forComponents is exposed for direct testing. Feed it a degenerate / impossible magnitude
        // (<= 0). ln(1 + max(0, magnitude)) guards the log domain; the wave must still be valid.
        // A bug that let magnitude reach ln() unguarded would NaN the intensity and corrupt every
        // amplitude. We attack the guard directly.
        val zero = Thruummm.forComponents(fell = 1, rings = 1, variety = 1, magnitude = 0.0)
        assertAndroidWillAccept(zero, "magnitude == 0")
        val negative = Thruummm.forComponents(fell = 1, rings = 1, variety = 1, magnitude = -50.0)
        assertAndroidWillAccept(negative, "magnitude < 0 (impossible, but the map must not NaN)")
        // No amplitude may be NaN-derived: every value already asserted in 0..255 above, but pin the
        // intent — a NaN intensity would round to a garbage Int.
        assertTrue(zero.amplitudes.none { it < 0 } && negative.amplitudes.none { it < 0 }, "a degenerate magnitude must not produce negative amplitudes")
    }

    @Test
    fun `every segment pairs a duration with an amplitude across a sweep of collapses`() {
        // A property sweep: across a grid of (fell, rings, variety), the wave is ALWAYS android-valid.
        // The single most important invariant — array parity — is the one that crashes createWaveform.
        val mats = listOf(pebble, wood, glass, stone)
        for (fell in intArrayOf(1, 2, 5, 13, 40)) {
            for (rings in intArrayOf(1, 2, 4, 7, 30)) {
                for (variety in 1..4) {
                    val fellBricks = (0 until fell).map { brick(it, mats[it % variety]) }
                    val materials = mats.take(variety).toSet()
                    val w = Thruummm.forCollapse(collapse(fellBricks, rings, materials))
                    assertAndroidWillAccept(w, "fell=$fell rings=$rings variety=$variety")
                }
            }
        }
    }

    // ── the slam invariant the device feel depends on ──────────────────────────────────────────

    @Test
    fun `the wave always ends on a non-zero slam segment, never a trailing gap`() {
        // If the last segment were a gap (amplitude 0), the felt crash would end on silence — the
        // slam is the punctuation the player chases. Also: a trailing 0-amplitude segment is legal
        // for android but wrong for the design. Attack both small and large collapses.
        for (c in listOf(
            collapse(listOf(brick(0, pebble)), 1, setOf(pebble)),
            collapse((0 until 20).map { brick(it, if (it % 2 == 0) wood else glass) }, 6, setOf(wood, glass)),
        )) {
            val w = Thruummm.forCollapse(c)
            assertTrue(w.amplitudes.last() > 0, "the wave must END on the slam (non-zero amplitude), never on a gap")
            assertTrue(w.timings.last() >= 220L, "the slam is a long buzz (>= SLAM_MS_MIN), not a tick")
        }
    }
}
