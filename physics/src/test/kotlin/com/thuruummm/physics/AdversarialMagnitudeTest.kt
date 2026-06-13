package com.thuruummm.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — spec corner case 6 and the THE LOAD-BEARING CLAIM of the whole game:
 * "reward complexity, not size" → magnitude = fell × rings × variety, and a TANGLED collapse must
 * out-score a TALLER PLAIN TOWER THAT FELLED MORE BRICKS.
 *
 * The happy path ([PhysicsEngineTest] test 6) compares a tangle against a tower of EQUAL-ISH size and
 * only asserts variety-count and a magnitude inequality. That is too gentle: it does not prove the
 * tower actually felled MORE bricks (the "size" the claim must beat), and it does not pin the
 * formula's real failure mode — that a SINGLE-MATERIAL tangle (variety 1) does NOT beat size, because
 * the formula multiplies by variety. A hostile reviewer must surface that the prose ("complexity
 * beats size") is only true when complexity carries material VARIETY and extra RINGS.
 *
 * Pure JVM. Run with: ./gradlew :physics:test
 */
class AdversarialMagnitudeTest {

    private val pebble = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
    private val stone  = Material(weight = 3.0, strength = 9.0, cantilever = 0, shatterThreshold = 4, brittleness = 0.5)
    private val glass  = Material(weight = 1.0, strength = 2.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.9)

    // ── THE magnitude assertion: complexity beats size, AND size is genuinely larger ──────────────

    /**
     * A TALL PLAIN tower of 7 pebbles. One material, one column → it buckles at the base, the column
     * comes down in a single ring. magnitude = fell × 1 × 1 = fell. We deliberately make it FELL MORE
     * BRICKS than the tangle so the comparison genuinely pits "more bricks" against "more structure".
     *
     * The TANGLED structure (mixed wood/glass/stone with an overhung arm) collapses across multiple
     * rings and multiple materials. The claim under test: even felling FEWER bricks, it out-scores the
     * taller tower because rings × variety multiply.
     *
     * Assertions, in order of hostility:
     *   1. the tower really did fall as a single-material, single-ring event (else the comparison lies);
     *   2. the tower felled strictly MORE bricks than the tangle (size is on the tower's side);
     *   3. the tangle carried strictly more material variety and ≥ 2 rings (the complexity is real);
     *   4. and STILL the tangle's magnitude strictly exceeds the tower's.
     */
    @Test
    fun `tangled complexity outscores a taller tower that felled more bricks`() {
        // ── Plain tower: ONE material, tall, designed to fell MANY bricks in a SINGLE ring.
        //
        // A naive pebble column self-limits (its base buckles the instant it bears > 3, so it can
        // never grow tall enough to fell a big stack). To put SIZE genuinely on the tower's side we use
        // one light material whose strength (10) is the chokepoint: the column grows to 11 bricks (base
        // bearing exactly 10, holding), then a 12th tips the base past its limit. The base buckles and
        // the 11 bricks above — too strong to re-buckle — fall once and re-settle. One ring, one
        // material, a large fell count.
        val pillar = Material(weight = 1.0, strength = 10.0, cantilever = 0, shatterThreshold = 99, brittleness = 0.0)
        val towerEngine = PhysicsEngine()
        // base + 10 uppers: base bears exactly 10.0 (== strength) and HOLDS (buckle is strict `>`).
        repeat(11) { y -> towerEngine.place(Placement(pillar, Cell(9, y))) }
        // The 12th brick pushes the base to 11.0 > 10.0 → the base buckles; 11 bricks above fall once.
        val towerCollapse = towerEngine.place(Placement(pillar, Cell(9, 11))).collapse
        assertNotNull(towerCollapse, "The tall single-material pillar must collapse when its base is overloaded")
        assertEquals(1, towerCollapse!!.materials.size, "A plain pillar is ONE material — variety must be 1")
        assertEquals(1, towerCollapse.rings, "A plain column buckles in a single ring")
        assertTrue(
            towerCollapse.fell.size >= 8,
            "The pillar must fell a large stack (size on its side); got ${towerCollapse.fell.size}",
        )

        // ── Tangled: a brittle GLASS fuse on the ground carrying a PEBBLE stack, struck by a heavy
        // STONE dropped from height. Three materials. The stone settles first (ring 1), THEN its
        // weight overloads the glass fuse, which buckles and pulls the pebble stack down (ring 2+).
        // Multi-ring AND multi-material — genuine complexity, far fewer bricks than the pillar.
        val tangled = PhysicsEngine()
        tangled.place(Placement(glass,  Cell(0, 0)))  // strength 2.0 — the fuse
        tangled.place(Placement(pebble, Cell(0, 1)))  // pebble stack on the fuse
        tangled.place(Placement(pebble, Cell(0, 2)))
        val tangledCollapse = tangled.place(Placement(stone, Cell(0, 5))).collapse // heavy drop → cascade
        assertNotNull(tangledCollapse, "The tangled structure must collapse")

        // (1)+(2) Size is genuinely on the tower's side: it felled strictly MORE bricks.
        assertTrue(
            towerCollapse.fell.size > tangledCollapse!!.fell.size,
            "Precondition of the claim: the tower must fell MORE bricks (${towerCollapse.fell.size}) " +
                "than the tangle (${tangledCollapse.fell.size}); otherwise we are not beating SIZE",
        )

        // (3) The complexity is real: more material variety, and a deeper (≥2-ring) propagation.
        assertTrue(
            tangledCollapse.materials.size > towerCollapse.materials.size,
            "Tangle variety (${tangledCollapse.materials.size}) must exceed tower variety " +
                "(${towerCollapse.materials.size})",
        )
        assertTrue(
            tangledCollapse.rings >= 2,
            "A tangle must propagate over multiple rings; got ${tangledCollapse.rings}",
        )
        // Reviewer BUG-3/BUG-4: under the corrected lowest-buckler-per-ring rule, the glass fuse and
        // each successively-overloaded pebble buckle in their OWN ring rather than all at once, so the
        // win here is genuinely on ring DEPTH (not only on variety). Pin that the propagation is deep —
        // a regression to all-bucklers-at-once would drop this back to 2 and is now test-breaking.
        assertTrue(
            tangledCollapse.rings >= 3,
            "Lowest-buckler-per-ring must surface the full propagation depth; got ${tangledCollapse.rings}",
        )

        // (4) THE claim: despite felling FEWER bricks, complexity wins on magnitude.
        assertTrue(
            tangledCollapse.magnitude > towerCollapse.magnitude,
            "complexity-beats-size FAILED: tangle magnitude=${tangledCollapse.magnitude} " +
                "(fell=${tangledCollapse.fell.size}, rings=${tangledCollapse.rings}, " +
                "variety=${tangledCollapse.materials.size}) did NOT exceed tower magnitude=" +
                "${towerCollapse.magnitude} (fell=${towerCollapse.fell.size}, rings=${towerCollapse.rings}, " +
                "variety=${towerCollapse.materials.size})",
        )
    }

    // ── ATTACK: the honest counter — a SINGLE-MATERIAL tangle does NOT beat size ───────────────────

    /**
     * The prose says "complexity beats size"; the FORMULA says "fell × rings × variety". So a complex
     * arrangement built from ONE material (variety = 1) gains nothing from its shape unless it also
     * earns extra rings. This test documents the formula's true contract: a one-material structure of
     * N bricks that collapses in one ring scores exactly N — identical to a plain tower of N bricks.
     * If the engine ever sneaks an undocumented "complexity bonus" into magnitude, this equality
     * breaks and the hidden coupling is exposed.
     */
    @Test
    fun `magnitude is exactly fell times rings times variety with no hidden bonus`() {
        // A single-material, single-ring collapse: 5-pebble tower.
        val e = PhysicsEngine()
        repeat(4) { y -> e.place(Placement(pebble, Cell(0, y))) }
        val c = e.place(Placement(pebble, Cell(0, 4))).collapse
        assertNotNull(c, "The 5-pebble tower collapses")

        val expected = c!!.fell.size.toDouble() * c.rings * c.materials.size
        assertEquals(
            expected, c.magnitude, 1e-9,
            "magnitude MUST equal fell×rings×variety exactly — no hidden complexity bonus is allowed",
        )
        // And for a one-material single-ring event, that is just the fell count.
        assertEquals(1, c.materials.size, "One material → variety 1")
        assertEquals(1, c.rings, "One column → one ring")
        assertEquals(c.fell.size.toDouble(), c.magnitude, 1e-9, "variety 1, rings 1 → magnitude == fell count")
    }

    // ── ATTACK: an empty / no-op collapse has magnitude 0, never NaN or negative ───────────────────

    /**
     * Defensive: a CollapseResult with nothing in it (zero rings, zero materials, empty fell) must
     * have magnitude exactly 0.0 — not NaN, not negative. The product form `0 × 0 × 0` is safe, but a
     * hostile reviewer pins it so a future formula change (e.g. an additive term or a division) cannot
     * silently produce a non-zero or undefined magnitude for a collapse that did not happen.
     */
    @Test
    fun `an empty collapse result has magnitude zero`() {
        val empty = CollapseResult(fell = emptyList(), rings = 0, materials = emptySet(), finalGrid = Grid())
        assertEquals(0.0, empty.magnitude, 0.0, "An empty collapse must score exactly 0.0")
        assertTrue(empty.magnitude.isFinite(), "Magnitude must never be NaN or infinite")
    }
}
