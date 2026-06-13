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
 * The magnitude → richness map ([Thruummm]) is pure Kotlin — no Android, no device. We assert the
 * design law holds in the *feel*: a more tangled collapse rings bigger than a taller plain one,
 * and every collapse produces a real, well-formed THRUUMMMM.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.ThruummmMappingTest"
 */
class ThruummmMappingTest {

    private val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    private val wood = Material(1.5, 5.0, 2, 3, 0.1)
    private val glass = Material(1.0, 2.0, 0, 1, 0.9)

    private fun brick(id: Int, m: Material) = Brick(id, m, Cell(id, 0))

    private fun collapse(fell: List<Brick>, rings: Int, materials: Set<Material>) =
        CollapseResult(fell = fell, rings = rings, materials = materials, finalGrid = Grid())

    // ── well-formedness: every collapse yields a playable, non-empty envelope ──────────────────

    @Test
    fun `a single-brick one-ring collapse still produces a real thruummm`() {
        val c = collapse(listOf(brick(0, pebble)), rings = 1, materials = setOf(pebble))
        val wave = Thruummm.forCollapse(c)
        assertEquals(wave.timings.size, wave.amplitudes.size, "timings and amplitudes must pair 1:1")
        assertTrue(wave.timings.isNotEmpty(), "even the smallest collapse must vibrate")
        assertTrue(wave.timings.all { it >= 0 }, "no negative durations")
        assertTrue(wave.amplitudes.all { it in 0..255 }, "amplitudes must be in the android 0..255 range")
        assertTrue(wave.amplitudes.any { it > 0 }, "the wave must actually buzz, not be all-silence")
    }

    @Test
    fun `the wave always ends on a slam — the final segment is a non-zero buzz`() {
        val c = collapse(listOf(brick(0, pebble), brick(1, wood)), rings = 2, materials = setOf(pebble, wood))
        val wave = Thruummm.forCollapse(c)
        assertTrue(wave.amplitudes.last() > 0, "every collapse ends on the landing slam")
        assertTrue(wave.timings.last() >= 200, "the slam is a long buzz, not a tick")
    }

    // ── the design law: complexity beats size (PHYSICS.md §"reward complexity, not size") ──────

    @Test
    fun `a tangled collapse rings bigger than a taller plain tower with more bricks`() {
        // Plain tall tower: many bricks, ONE ring, ONE material — the "naive vertical".
        val plain = collapse(
            fell = (0 until 12).map { brick(it, pebble) },
            rings = 1,
            materials = setOf(pebble),
        )
        // Tangled structure: FEWER bricks, but many rings and mixed materials.
        val tangled = collapse(
            fell = (0 until 6).map { brick(it, if (it % 2 == 0) wood else glass) },
            rings = 4,
            materials = setOf(wood, glass, pebble),
        )

        // The physics magnitude already says tangled wins (6×4×3=72 > 12×1×1=12); the haptic must
        // honour it — a richer envelope (more tumble pulses) and at least as much total energy.
        assertTrue(
            tangled.magnitude > plain.magnitude,
            "sanity: the physics magnitude itself ranks tangled above plain",
        )

        val tangledWave = Thruummm.forCollapse(tangled)
        val plainWave = Thruummm.forCollapse(plain)

        assertTrue(
            tumblePulses(tangledWave) > tumblePulses(plainWave),
            "the tangled (multi-ring) collapse must tumble in more pulses than the plain one",
        )
        assertTrue(
            energy(tangledWave) >= energy(plainWave),
            "the tangled collapse must feel at least as big overall, despite fewer bricks",
        )
    }

    @Test
    fun `richness is monotonic along a growing collapse ray — more of everything never feels smaller`() {
        // HONEST SCOPE: this asserts monotonicity along ONE ray through the input space — fell, rings,
        // and variety all growing together (the natural "a bigger collapse" direction). It does NOT
        // claim global monotonicity in raw `magnitude`: the map deliberately spends fell, rings, and
        // variety on DIFFERENT felt dimensions (tumble body, pulse count, texture swing) and clamps
        // pulses at MAX_TUMBLE_PULSES, so two collapses of equal magnitude but different shape can
        // carry different energy — by design (DESIGN.md "reward complexity, not size"; complexity and
        // size are not the same axis). Forcing total-energy monotonicity in magnitude would flatten
        // that distinction. What the design DOES promise is that a strictly-larger collapse (more
        // bricks, deeper cascade, more materials) never rings smaller — that is the ray asserted here.
        var prevEnergy = -1L
        for (step in 1..6) {
            val c = collapse(
                fell = (0 until step * 2).map { brick(it, if (it % 2 == 0) wood else glass) },
                rings = step,
                materials = setOf(wood, glass),
            )
            val e = energy(Thruummm.forCollapse(c))
            assertTrue(e >= prevEnergy, "energy must not decrease as the whole collapse grows (step=$step)")
            prevEnergy = e
        }
    }

    @Test
    fun `material variety adds texture — a mixed collapse is more jagged than a uniform one`() {
        val uniform = collapse((0 until 6).map { brick(it, wood) }, rings = 3, materials = setOf(wood))
        val mixed = collapse((0 until 6).map { brick(it, if (it % 2 == 0) wood else glass) }, rings = 3, materials = setOf(wood, glass))
        // Same fell count and rings; only variety differs. Variety widens the alternating-pulse
        // amplitude swing, so the mixed collapse has a larger spread between its loudest and
        // quietest tumble pulses.
        assertTrue(
            amplitudeSpread(Thruummm.forCollapse(mixed)) > amplitudeSpread(Thruummm.forCollapse(uniform)),
            "more material variety must read as more textured (wider amplitude swing)",
        )
    }

    // ── helpers reading the felt shape out of the raw envelope ─────────────────────────────────

    /** Tumble pulses = the non-zero buzzes before the final slam. */
    private fun tumblePulses(w: Haptic.Wave): Int =
        w.amplitudes.dropLast(1).count { it > 0 }

    /** Rough felt "energy" = Σ(amplitude × duration) over the on-segments. */
    private fun energy(w: Haptic.Wave): Long =
        w.amplitudes.indices.sumOf { w.amplitudes[it].toLong() * w.timings[it] }

    /** Spread between the loudest and quietest tumble pulse (excludes the slam and the gaps). */
    private fun amplitudeSpread(w: Haptic.Wave): Int {
        val pulses = w.amplitudes.dropLast(1).filter { it > 0 }
        return (pulses.maxOrNull() ?: 0) - (pulses.minOrNull() ?: 0)
    }
}
