package com.thuruummm.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — Q2 "Do my shoulders hold?" ([Load]) and Q3 "What falls?" ([Cascade]) — the
 * fall-distance / shatter machinery and the load-routing the THRUUMMMM magnitude depends on.
 *
 * Spec corner cases 1 (stack buckles), 4 (overload drops the column), 5 (brittle multi-ring cascade)
 * are proven by [PhysicsEngineTest] only at comfortable distances from the failure thresholds. The
 * collapse score is `fell × rings × variety` — every fall distance and shatter decision feeds it —
 * so the corners that matter are the EXACT thresholds and the conservation laws, not "it collapsed".
 * This file attacks:
 *
 *   - the EXACT shatter boundary: fellDistance == shatterThreshold shatters; one less lands intact
 *     ([Cascade]'s `>=`). Off-by-one here silently changes every magnitude.
 *   - the EXACT buckle boundary: load == strength HOLDS (the test is `>` strict); load == strength+ε
 *     buckles. The spec says "load > strength"; a `>=` bug fails a structure that should stand.
 *   - LOAD CONSERVATION: a stable structure routes every footed brick's weight down to bedrock; no
 *     weight is created or silently dropped (the cantilever-arm routing in [Load.loads] is the risk).
 *   - the lowest buckler is the seed ("the lowest buckling brick is where the collapse begins").
 *   - debris shoves: a shattering brittle brick adds (weight × brittleness) to its landing neighbours,
 *     and that shove ALONE can buckle a neighbour that was otherwise within margin.
 *   - shatterThreshold == 0 (the Material contract allows it): any fall shatters.
 *
 * Pure JVM. Run with: ./gradlew :physics:test
 */
class AdversarialLoadCascadeTest {

    // The one shared material this suite leans on. Bespoke materials are declared locally per test so
    // each attack's geometry is self-contained and a hostile reader can see the numbers at the seam.
    private val pebble = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)

    // ── ATTACK: the EXACT shatter boundary (fellDistance >= shatterThreshold) ──────────────────────

    /**
     * A pebble (shatter@2) floating at y=2 over an empty column falls EXACTLY 2 cells. 2 >= 2 → it
     * SHATTERS and is gone. The happy path only ever tests fell=3 (clearly past) and fell=1 (clearly
     * under). The boundary itself — where an off-by-one (`>` instead of `>=`) lives — is here.
     */
    @Test
    fun `fall of exactly shatterThreshold shatters`() {
        val engine = PhysicsEngine()
        val result = engine.place(Placement(pebble, Cell(0, 2))) // falls 2 == shatter@2

        assertNotNull(result.collapse, "A floating brick falls")
        assertTrue(result.collapse!!.fell.any { it.id >= 0 }, "Something fell")
        assertNull(result.grid.at(Cell(0, 0)), "Fell exactly shatterThreshold → shatters; nothing lands")
        assertEquals(0, result.grid.cells.size, "Grid must be empty after the shatter")
    }

    /**
     * One cell less than the threshold must LAND INTACT. Pebble (shatter@2) floating at y=1 over an
     * empty column falls 1 < 2 → lands on bedrock at y=0, intact, keeping its identity. Pins the
     * other side of the `>=` boundary.
     */
    @Test
    fun `fall of one less than shatterThreshold lands intact on bedrock`() {
        val engine = PhysicsEngine()
        val result = engine.place(Placement(pebble, Cell(0, 1))) // falls 1 < shatter@2

        assertNotNull(result.collapse, "A floating brick falls even when it survives the landing")
        assertNull(result.grid.at(Cell(0, 1)), "It must not stay floating at (0,1)")
        assertNotNull(result.grid.at(Cell(0, 0)), "Fell 1 < shatter@2 → lands intact on bedrock at (0,0)")
        assertEquals(1, result.grid.cells.size, "Exactly the one landed brick remains")
    }

    /** shatterThreshold == 0 is a legal Material; ANY fall (>= 0) must shatter it. */
    @Test
    fun `shatterThreshold zero shatters on any fall`() {
        val fragile = Material(weight = 1.0, strength = 5.0, cantilever = 0, shatterThreshold = 0, brittleness = 0.0)
        val engine = PhysicsEngine()
        val result = engine.place(Placement(fragile, Cell(0, 1))) // falls 1 >= 0 → shatter

        assertNotNull(result.collapse, "It fell")
        assertEquals(0, result.grid.cells.size, "shatter@0 → even a 1-cell fall shatters it to nothing")
    }

    // ── ATTACK: the EXACT buckle boundary (load > strength is STRICT) ──────────────────────────────

    /**
     * The spec is explicit: a brick buckles when "load > strength" — strictly. A bottom pebble
     * (strength 3.0) carrying exactly 3.0 of load (three pebbles stacked on it) must HOLD. A `>=`
     * regression in [Load.buckled] would wrongly collapse a structure sitting exactly at its limit.
     */
    @Test
    fun `load exactly equal to strength holds`() {
        val engine = PhysicsEngine()
        engine.place(Placement(pebble, Cell(0, 0)))   // bottom, strength 3.0
        engine.place(Placement(pebble, Cell(0, 1)))
        engine.place(Placement(pebble, Cell(0, 2)))
        val result = engine.place(Placement(pebble, Cell(0, 3))) // bottom now bears exactly 3.0

        assertNull(result.collapse, "load == strength is not a buckle (spec: strictly load > strength)")
        assertEquals(4, result.grid.cells.size, "All four pebbles must stand at the exact limit")
        // And the margin is exactly 0.0 at the limit — the tremble signal is maxed but not failed.
        assertEquals(0.0, result.stress, 1e-9, "Margin at the exact limit is 0.0, not negative")
    }

    /** One straw more — the limit-plus-one pebble — must tip it. The matching `>` side of the boundary. */
    @Test
    fun `load one increment over strength buckles`() {
        val engine = PhysicsEngine()
        repeat(4) { y -> engine.place(Placement(pebble, Cell(0, y))) } // bottom at the limit (3.0)
        val result = engine.place(Placement(pebble, Cell(0, 4)))        // pushes bottom to 4.0 > 3.0

        assertNotNull(result.collapse, "load just over strength must buckle")
        assertGridIsStable(result.grid)
    }

    // ── ATTACK: LOAD CONSERVATION — no weight invented, none silently dropped ──────────────────────

    /**
     * Physical law the engine must not violate: in a fully stable structure, the total weight of all
     * footed bricks equals the total load that arrives at bedrock — i.e. the sum, over ground-level
     * footed bricks, of (their own weight + the load resting on them). The cantilever-arm routing in
     * [Load.loads] is the place weight could be silently dropped (its "No support → drop its load"
     * branch). If any path leaks weight, this fails.
     *
     * Structure: a reach-2 wood column carrying a pebble arm out over a gap — the arm's weight MUST
     * find its way down to the ground brick, not vanish.
     */
    @Test
    fun `stable structure conserves weight down to bedrock`() {
        val column = Material(weight = 2.0, strength = 1_000.0, cantilever = 2, shatterThreshold = 99, brittleness = 0.0)
        var g = Grid()
        g = g.place(column, Cell(0, 0)).first   // ground
        g = g.place(column, Cell(0, 1)).first   // beam, reach 2
        g = g.place(pebble, Cell(2, 1)).first    // arm out over the gap at (1,1)

        assertWeightConserved(g)
    }

    /** Same law, stress-tested across a deterministic pile of placements that all settle stably. */
    @Test
    fun `weight is conserved after a deterministic sequence of stable placements`() {
        val light = Material(weight = 1.0, strength = 1_000.0, cantilever = 1, shatterThreshold = 99, brittleness = 0.0)
        val engine = PhysicsEngine()
        // A staircase of strong, light bricks — every placement is footed, nothing should ever fall.
        for (x in 0..4) for (y in 0..x) engine.place(Placement(light, Cell(x, y)))
        assertNull(engine.place(Placement(light, Cell(5, 0))).collapse, "Strong staircase never collapses")
        assertWeightConserved(engine.grid)
    }

    // ── ATTACK: a cantilever BEAM bears the arm cantilevered onto it, and BUCKLES under it ──────────

    /**
     * Reviewer BUG-1/BUG-2 regression. PHYSICS.md §"Do my shoulders hold?": "a brick carries … plus any
     * arms cantilevered onto it." A cantilever arm's weight must land ON THE BEAM that foots it — not be
     * routed around the beam onto a structurally-unrelated column. The old load model bypassed the beam
     * onto the base, so an overhang beam read load 0 and could NEVER buckle — the single most interesting
     * collapse (an overloaded cantilever snapping) was impossible. This pins that it IS possible.
     *
     * Rig: a very strong base at (0,0) foots a WEAK beam at (0,1) (strength 4, reach 2). A heavy arm
     * (weight 5) leans onto the beam at (2,1) over the gap. The beam must register the arm's 5.0 of load,
     * which exceeds its strength 4.0 → the BEAM buckles. The base (strength 1000) does not.
     */
    @Test
    fun `a cantilever beam bears its arm and buckles when the arm overloads it`() {
        val base     = Material(weight = 1.0, strength = 1_000.0, cantilever = 2, shatterThreshold = 99, brittleness = 0.0)
        val weakBeam = Material(weight = 1.0, strength = 4.0,     cantilever = 2, shatterThreshold = 99, brittleness = 0.0)
        val heavyArm = Material(weight = 5.0, strength = 9.0,     cantilever = 0, shatterThreshold = 99, brittleness = 0.0)

        // First prove the BEAM registers the arm's load (the routing fix), via the load map directly.
        var g = Grid()
        g = g.place(base, Cell(0, 0)).first
        g = g.place(weakBeam, Cell(0, 1)).first
        g = g.place(heavyArm, Cell(2, 1)).first      // arm leaning onto the beam over the gap at (1,1)
        val footing = Support.footing(g)
        val loads = Load.loads(g, footing)
        assertTrue(Cell(2, 1) in footing.cells, "Precondition: the arm is footed by the beam's reach")
        assertEquals(
            5.0, loads[Cell(0, 1)] ?: 0.0, 1e-9,
            "The beam at (0,1) MUST register the 5.0 the arm cantilevers onto it — not route it around",
        )
        assertTrue(
            Cell(0, 1) in Load.buckled(g, loads),
            "Arm load 5.0 > beam strength 4.0 → the cantilever beam must buckle (impossible under BUG-1)",
        )

        // Now drive it through the engine: placing the heavy arm triggers the collapse, the beam is the
        // buckler, and the grid stabilises (the arm loses its only footing and falls too).
        val engine = PhysicsEngine()
        engine.place(Placement(base, Cell(0, 0)))
        engine.place(Placement(weakBeam, Cell(0, 1)))
        val result = engine.place(Placement(heavyArm, Cell(2, 1)))
        assertNotNull(result.collapse, "Overloading the cantilever beam must collapse the structure")
        assertNull(result.grid.at(Cell(2, 1)), "The arm loses its footing once the beam buckles")
        assertGridIsStable(result.grid)
    }

    // ── ATTACK: the LOWEST buckler seeds the collapse ──────────────────────────────────────────────

    /**
     * "The lowest buckling brick is where the collapse begins." A weak base (strength 3.0) carries a
     * strong column; when the base is overloaded it is the buckler, and per the spec it is CONSUMED as
     * debris — its removal is what lets the column above come down. The strong column then re-settles.
     *
     * The hostile move: capture the base brick's stable id BEFORE the cap, then prove that exact brick
     * is GONE after the cascade. A naive engine that re-lands the buckled base (instead of consuming
     * it) would leave its id in the grid — and an overloaded column would silently re-stack forever.
     */
    @Test
    fun `the lowest buckler is consumed, not re-landed`() {
        val strong = Material(weight = 1.0, strength = 100.0, cantilever = 0, shatterThreshold = 99, brittleness = 0.0)
        val weakBase = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 99, brittleness = 0.0)

        val engine = PhysicsEngine()
        engine.place(Placement(weakBase, Cell(0, 0)))   // strength 3.0 — the deliberate weak link
        engine.place(Placement(strong, Cell(0, 1)))
        engine.place(Placement(strong, Cell(0, 2)))
        engine.place(Placement(strong, Cell(0, 3)))     // base now bears exactly 3.0 — holds
        val baseId = engine.grid.at(Cell(0, 0))!!.id    // the brick we expect to be consumed

        val result = engine.place(Placement(strong, Cell(0, 4))) // base bears 4.0 > 3.0 → buckles

        assertNotNull(result.collapse, "An overloaded base must collapse")
        assertGridIsStable(result.grid)
        assertTrue(
            result.grid.bricks.values.none { it.id == baseId },
            "The lowest buckler (id=$baseId) must be CONSUMED, not re-landed into the grid",
        )
        // The strong column survives and re-settles into a shorter, stable stack.
        assertEquals(4, result.grid.cells.size, "The four strong bricks re-settle; the base is gone")
    }

    // ── ATTACK: the brittleness shove ALONE buckles an otherwise-safe neighbour ────────────────────

    /**
     * A shattering brick adds (weight × brittleness) to the bricks beside its landing cell. That shove
     * is the cascade's accelerant. Here a neighbour is engineered to sit JUST within its margin so
     * that only the shove can finish it — proving the shove is actually folded into the load
     * re-evaluation ([Cascade] effectiveLoads), not dropped.
     *
     * Rig: a glass pillar (strength 2.0) at (1,0) carries nothing of its own; beside it at (0,..) a
     * heavy brittle stone is dropped from height so it shatters next to the glass and shoves it.
     */
    @Test
    fun `a debris shove can buckle a neighbour that was within margin`() {
        // A neighbour brick at (1,0) bearing a load that sits just under its strength.
        val nearLimit = Material(weight = 1.0, strength = 2.0, cantilever = 0, shatterThreshold = 99, brittleness = 0.0)
        // A heavy, very brittle smasher: weight 4, brittleness 1.0 → a shove of 4.0 on landing.
        val smasher = Material(weight = 4.0, strength = 9.0, cantilever = 0, shatterThreshold = 1, brittleness = 1.0)

        val engine = PhysicsEngine()
        engine.place(Placement(nearLimit, Cell(1, 0)))            // ground neighbour, strength 2.0, bears 0 of its own
        val result = engine.place(Placement(smasher, Cell(0, 4))) // falls 4 >= 1 → shatters at (0,0), shoves (1,0)

        assertNotNull(result.collapse, "The dropped smasher cascades")
        // The shove (4.0 × 1.0 = 4.0) exceeds the neighbour's strength (2.0): it must have buckled and
        // been consumed. If the shove were dropped, the neighbour would survive — that is the bug we hunt.
        assertNull(result.grid.at(Cell(1, 0)), "The debris shove alone must buckle and consume the neighbour")
        assertGridIsStable(result.grid)
    }

    // ── ATTACK: SIMULTANEOUS fallers never land ON each other mid-fall (the remove-first invariant) ─

    /**
     * [Cascade] removes the WHOLE doomed set before computing any landing, then drops fallers bottom-up
     * per column. If a higher faller resolved its landing against a lower faller that had not yet been
     * lifted off the surface, fall distances (and thus shatter decisions and the magnitude) would be
     * wrong, and bricks could overlap or strand in mid-air.
     *
     * This forces a genuinely SIMULTANEOUS multi-faller ring: a weak base (strength 2.0) carries a
     * stack of three tough bricks; overloading the base buckles it, so all three tough bricks lose
     * footing in the SAME ring. They must re-settle into a gapless ground stack — y = 0,1,2 — with no
     * overlap and nothing left floating.
     */
    @Test
    fun `simultaneous fallers re-stack gaplessly from the floor`() {
        val tough = Material(weight = 1.0, strength = 9.0, cantilever = 0, shatterThreshold = 9, brittleness = 0.0)
        val weakBase = Material(weight = 1.0, strength = 2.0, cantilever = 0, shatterThreshold = 9, brittleness = 0.0)

        val engine = PhysicsEngine()
        engine.place(Placement(weakBase, Cell(0, 0)))   // strength 2.0
        engine.place(Placement(tough, Cell(0, 1)))
        engine.place(Placement(tough, Cell(0, 2)))      // base now bears exactly 2.0 — holds
        val result = engine.place(Placement(tough, Cell(0, 3))) // base bears 3.0 > 2.0 → buckles; 3 fall at once

        assertNotNull(result.collapse, "Overloading the base must collapse the stack")
        assertGridIsStable(result.grid)
        // The base is consumed; the three tough bricks re-settle gaplessly from the floor.
        assertEquals(3, result.grid.cells.size, "Exactly the three tough bricks remain (base consumed)")
        assertNotNull(result.grid.at(Cell(0, 0)), "A tough brick settles on bedrock")
        assertNotNull(result.grid.at(Cell(0, 1)), "A tough brick settles directly above it — no gap")
        assertNotNull(result.grid.at(Cell(0, 2)), "A tough brick settles above that — no overlap, no float")
        assertNull(result.grid.at(Cell(0, 3)), "Nothing may remain at the old top cell")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private fun assertGridIsStable(grid: Grid) {
        val footedSet = Support.footedSet(grid)
        assertTrue(
            Support.unfooted(grid).isEmpty(),
            "Grid has unfooted bricks at ${Support.unfooted(grid)} — cascade did not stabilise",
        )
        val buckled = Load.buckled(grid, Load.loads(grid, footedSet))
        assertTrue(buckled.isEmpty(), "Grid has buckled bricks at $buckled — cascade did not stabilise")
    }

    /**
     * Asserts conservation: total weight of footed bricks == total weight arriving at bedrock
     * (sum over ground-level footed cells of own-weight + load resting on them). Any silently dropped
     * or invented load breaks the equality.
     */
    private fun assertWeightConserved(grid: Grid) {
        val footed = Support.footedSet(grid)
        val loads = Load.loads(grid, footed)
        val totalFootedWeight = footed.sumOf { grid.at(it)!!.material.weight }
        val arrivingAtBedrock = footed
            .filter { grid.isGroundLevel(it) }
            .sumOf { grid.at(it)!!.material.weight + (loads[it] ?: 0.0) }
        assertEquals(
            totalFootedWeight, arrivingAtBedrock, 1e-9,
            "Load conservation violated: footed weight=$totalFootedWeight but bedrock receives " +
                "$arrivingAtBedrock — weight was invented or silently dropped in routing",
        )
    }
}
