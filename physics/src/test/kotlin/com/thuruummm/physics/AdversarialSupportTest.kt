package com.thuruummm.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — Q1 "Do I have a footing?" ([Support]) and the cantilever/overhang model.
 *
 * The happy-path [PhysicsEngineTest] proves the spec's corner case 2 (floating brick falls) and 3
 * (cantilever within reach holds / one cell past reach falls) only along the SHORTEST, CLEANEST
 * geometry: a single anchor beam, one arm, one direction. This file attacks the parts of the footing
 * model the implementation actually ships but the happy path never exercises:
 *
 *   - the overhang is SYMMETRIC and GAP-TOLERANT ([Support.footedSet] reaches `dx` cells either side,
 *     over empty space). The happy path only tests one side and one gap.
 *   - a cantilever arm is itself footed, so its OWN reach radiates again — overhang chaining. Nothing
 *     in the spec's corner cases bounds this; a hostile builder will chain arms to "walk" off a tower.
 *   - the EXACT reach boundary (dx == cantilever holds; dx == cantilever + 1 falls), on BOTH signs.
 *   - removing a support must un-foot everything it carried, transitively and across a broken span
 *     ([Support.unsupportedIfRemoved]) — the seam the cascade leans on.
 *
 * Pure JVM. Run with: ./gradlew :physics:test
 *
 * Engine facts these tests are written against (read from the shipped source, not assumed):
 *   - y == 0 is bedrock-footed unconditionally ([Grid.isGroundLevel]).
 *   - cantilever reach belongs to the SPANNING brick; it foots occupied cells within `reach` cells
 *     on its row, either direction, over empty gaps ([Support.footedSet]).
 *   - [PhysicsEngine.place] resolves a fall on placement, so an unfooted brick never survives a step.
 */
class AdversarialSupportTest {

    // ── Materials (independent of the happy-path file so this seam is attackable alone) ────────────

    private val pebble = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
    private val wood   = Material(weight = 1.5, strength = 5.0, cantilever = 2, shatterThreshold = 3, brittleness = 0.1)
    /** Unbreakable footing rig: huge strength, never shatters, reach 2 — isolates SUPPORT from LOAD. */
    private val anchor = Material(weight = 2.0, strength = 1_000.0, cantilever = 2, shatterThreshold = 99, brittleness = 0.0)
    /** A reach-1 anchor — to pin the exact 1-cell boundary independently of the reach-2 rig. */
    private val stub   = Material(weight = 2.0, strength = 1_000.0, cantilever = 1, shatterThreshold = 99, brittleness = 0.0)

    // ── ATTACK: the overhang is symmetric — the LEFT side must foot too, not just the right ───────

    /**
     * [Support.footedSet] reaches `-reach..reach`. The happy path only ever leans an arm to the +x
     * side. If the implementation ever regressed to a one-sided scan, this would catch it: a beam at
     * x=0 with reach 2 must foot an arm at x=-2 (a diving board pointing the other way).
     */
    @Test
    fun `overhang foots an arm on the negative-x side too`() {
        val engine = PhysicsEngine()
        engine.place(Placement(anchor, Cell(0, 0)))
        engine.place(Placement(anchor, Cell(0, 1)))         // beam, reach 2
        val result = engine.place(Placement(pebble, Cell(-2, 1))) // two cells out to the LEFT, over a gap

        assertNull(result.collapse, "Reach-2 beam must foot an arm 2 cells out on the negative side")
        assertNotNull(result.grid.at(Cell(-2, 1)), "Arm at (-2,1) should be footed by the overhang")
    }

    // ── ATTACK: the EXACT reach boundary on both signs (classic off-by-one in the dx loop) ─────────

    /**
     * dx == reach must HOLD; dx == reach+1 must FALL. The happy path tests reach=2 only. Here we pin
     * a reach-1 beam so the boundary is dx∈{1 holds, 2 falls} and assert BOTH signs. An off-by-one in
     * `for (dx in 1..reach)` (e.g. `until`, or `<=` vs `<`) dies here.
     */
    @Test
    fun `reach boundary holds at exactly reach and fails one past, both directions`() {
        // +x side, dx == reach (1) holds
        run {
            val e = PhysicsEngine()
            e.place(Placement(stub, Cell(0, 0)))
            e.place(Placement(stub, Cell(0, 1)))            // reach-1 beam
            val r = e.place(Placement(pebble, Cell(1, 1)))  // exactly 1 out
            assertNull(r.collapse, "+x arm at exactly reach=1 must hold")
            assertNotNull(r.grid.at(Cell(1, 1)), "+x arm at reach=1 should remain footed")
        }
        // +x side, dx == reach+1 (2) falls
        run {
            val e = PhysicsEngine()
            e.place(Placement(stub, Cell(0, 0)))
            e.place(Placement(stub, Cell(0, 1)))
            val r = e.place(Placement(pebble, Cell(2, 1)))  // one past reach=1
            assertNotNull(r.collapse, "+x arm one past reach must fall")
            assertNull(r.grid.at(Cell(2, 1)), "+x arm one past reach must not remain footed")
        }
        // -x side, dx == reach (1) holds
        run {
            val e = PhysicsEngine()
            e.place(Placement(stub, Cell(0, 0)))
            e.place(Placement(stub, Cell(0, 1)))
            val r = e.place(Placement(pebble, Cell(-1, 1)))
            assertNull(r.collapse, "-x arm at exactly reach=1 must hold")
            assertNotNull(r.grid.at(Cell(-1, 1)), "-x arm at reach=1 should remain footed")
        }
        // -x side, dx == reach+1 (2) falls
        run {
            val e = PhysicsEngine()
            e.place(Placement(stub, Cell(0, 0)))
            e.place(Placement(stub, Cell(0, 1)))
            val r = e.place(Placement(pebble, Cell(-2, 1)))
            assertNotNull(r.collapse, "-x arm one past reach must fall")
            assertNull(r.grid.at(Cell(-2, 1)), "-x arm one past reach must not remain footed")
        }
    }

    // ── ATTACK: overhang CHAINING — an arm's own reach radiates footing again ──────────────────────

    /**
     * A cantilever arm is footed, so per [Support.footedSet] its OWN cantilever reach re-radiates.
     * This lets a builder "walk" arms off a tower indefinitely. The engine SHIPS this behaviour
     * (arms enqueue and re-propagate). We pin it so any future "arms cannot re-anchor other arms"
     * change is a deliberate, test-breaking decision — not a silent regression.
     *
     * Rig: anchor column at x=0 (reach 2) foots wood arm at (2,1); the wood arm (reach 2) must then
     * foot a pebble at (4,1) — four cells from any vertical column, reachable ONLY by chaining.
     */
    @Test
    fun `a cantilever arm re-radiates its own reach (overhang chaining)`() {
        val engine = PhysicsEngine()
        engine.place(Placement(anchor, Cell(0, 0)))
        engine.place(Placement(anchor, Cell(0, 1)))          // column, reach 2
        engine.place(Placement(wood,   Cell(2, 1)))          // arm, footed by the column; reach 2
        val result = engine.place(Placement(pebble, Cell(4, 1))) // footed ONLY via the arm's reach

        assertNull(result.collapse, "A chained arm at (4,1) is footed by the wood arm's own reach")
        assertNotNull(result.grid.at(Cell(4, 1)), "Overhang chaining must keep (4,1) standing")
        assertNotNull(result.grid.at(Cell(2, 1)), "The intermediate arm must remain standing too")
    }

    // ── ATTACK: a beam does NOT foot a brick across the row gap if NOTHING anchors the beam ────────

    /**
     * Footing must flow FROM bedrock. A wood beam floating in space at (2,5) with nothing under or
     * beside it has no footing itself — so it cannot lend footing to a neighbour. This attacks the
     * footing flood directly (via [Support.footedSet] on a hand-built grid, bypassing the engine's
     * auto-fall) to prove reach is NEVER computed for an unfooted beam: an island of a floating beam
     * and a brick within its reach must both be UNFOOTED. A bug that radiated reach before the beam's
     * own footing was established would wrongly foot the leaning brick.
     */
    @Test
    fun `an unanchored beam lends no footing — the whole island is unfooted`() {
        val (g0, _) = Grid().place(wood, Cell(2, 5))         // floating beam, reach 2
        val (g1, _) = g0.place(pebble, Cell(4, 5))            // a brick within the beam's reach, also floating

        val footed = Support.footedSet(g1)
        assertTrue(footed.isEmpty(), "A floating beam foots neither itself nor anything in its reach: $footed")
        assertEquals(setOf(Cell(2, 5), Cell(4, 5)), Support.unfooted(g1), "Both island bricks are unfooted")
    }

    // ── ATTACK: removing the support must un-foot the whole carried family (the cascade seam) ──────

    /**
     * [Support.unsupportedIfRemoved] is what turns "the lowest buckler" into "everything it held".
     * Build a column with an overhung arm, then ask: if the base column is removed, does the arm
     * (footed only via that column's reach) get reported as newly-unsupported? It must — otherwise a
     * buckle would leave footed-by-a-deleted-beam ghosts behind.
     */
    @Test
    fun `removing a support reports the span-footed arm as unsupported`() {
        val (g0, _) = Grid().place(anchor, Cell(0, 0))
        val (g1, _) = g0.place(anchor, Cell(0, 1))
        val (g2, _) = g1.place(pebble, Cell(2, 1))           // arm footed only by the (0,1) beam's reach
        val footed = Support.footedSet(g2)

        assertTrue(Cell(2, 1) in footed, "Precondition: the arm is footed before removal")

        // Remove the entire support column. The arm loses its only footing path.
        val orphaned = Support.unsupportedIfRemoved(g2, doomed = setOf(Cell(0, 0), Cell(0, 1)), previouslyFooted = footed)
        assertTrue(Cell(2, 1) in orphaned, "Span-footed arm must be reported unsupported once its beam is removed")
        assertFalse(Cell(0, 0) in orphaned, "The doomed cells themselves are excluded from the orphan set")
        assertFalse(Cell(0, 1) in orphaned, "The doomed cells themselves are excluded from the orphan set")
    }

    /** [Support.unsupportedIfRemoved] with an empty doomed set is a no-op — degenerate-input guard. */
    @Test
    fun `removing nothing orphans nothing`() {
        val (g, _) = Grid().place(pebble, Cell(0, 0))
        val footed = Support.footedSet(g)
        assertEquals(emptySet(), Support.unsupportedIfRemoved(g, doomed = emptySet(), previouslyFooted = footed))
    }

    // ── ATTACK: empty world is well-defined (no NPE / no phantom footing) ──────────────────────────

    @Test
    fun `empty grid has no footed cells and no unfooted cells`() {
        val g = Grid()
        assertTrue(Support.footedSet(g).isEmpty(), "Empty world foots nothing")
        assertTrue(Support.unfooted(g).isEmpty(), "Empty world has nothing to be unfooted")
    }

    // ── ATTACK: a full ground row is all footed regardless of horizontal contiguity ────────────────

    /**
     * Three separate ground bricks with gaps between them are each independently bedrock-footed.
     * A bug that required horizontal adjacency to the previous brick (rather than y==0) would fail.
     */
    @Test
    fun `disjoint ground bricks are each independently footed`() {
        var g = Grid()
        for (x in intArrayOf(-3, 0, 7)) g = g.place(pebble, Cell(x, 0)).first
        val footed = Support.footedSet(g)
        assertEquals(setOf(Cell(-3, 0), Cell(0, 0), Cell(7, 0)), footed, "Every y==0 brick is footed alone")
    }
}
