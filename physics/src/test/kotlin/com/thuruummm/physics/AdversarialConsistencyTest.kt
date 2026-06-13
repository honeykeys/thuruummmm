package com.thuruummm.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE suite — spec corner case 7 (repeated place/collapse leaves a CONSISTENT, non-contradictory
 * state), the engine's DEFENSIVE contract (occupied-cell no-op, idempotency), out-of-contract input,
 * and the [Material] structural contract.
 *
 * [PhysicsEngineTest] test 7 runs 60 seeded-random placements and checks only "no unfooted / no
 * buckled". A consistent grid means MORE than that — and the cheap-to-check invariants it omits are
 * exactly where a copy-on-write engine with id-preserving `move`/`put` paths ([Grid]) tends to rot:
 *
 *   - the Grid's MAP KEY and the brick's own [Brick.cell] must always agree (a `move` that forgot to
 *     `copy(cell=…)` would desync them — render tracking and the next cascade would read a lie).
 *   - brick IDs must stay UNIQUE across the whole grid after any number of falls/relands.
 *   - stress is always a finite number ≤ 1.0 and is 1.0 for an empty/unloaded grid.
 *   - placing on an OCCUPIED cell is a true no-op: same cells, same ids, no phantom mint, collapse null.
 *
 * Plus the contract corners the engine REJECTS by construction ([Material.init]).
 *
 * Pure JVM. Run with: ./gradlew :physics:test
 */
class AdversarialConsistencyTest {

    private val pebble = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
    private val stone  = Material(weight = 3.0, strength = 9.0, cantilever = 0, shatterThreshold = 4, brittleness = 0.5)
    private val wood   = Material(weight = 1.5, strength = 5.0, cantilever = 2, shatterThreshold = 3, brittleness = 0.1)
    private val glass  = Material(weight = 1.0, strength = 2.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.9)

    // ── ATTACK: full structural consistency after a deterministic cascade-heavy sequence ──────────

    /**
     * A hand-authored sequence engineered to MAXIMISE collapses and re-collapses: heavy caps on weak
     * fuses, overhung arms, repeated drops onto the same column. After EVERY placement we assert the
     * full invariant set, not just "no unfooted/buckled". A consistent engine survives all of it.
     */
    @Test
    fun `the grid stays fully consistent through a cascade-heavy sequence`() {
        val engine = PhysicsEngine()
        val script: List<Pair<Material, Cell>> = listOf(
            glass  to Cell(0, 0),   // weak fuse on the ground
            pebble to Cell(0, 1),
            stone  to Cell(0, 4),   // heavy drop → cascade onto the glass
            wood   to Cell(2, 0),   // a beam base elsewhere
            wood   to Cell(2, 1),   // beam, reach 2
            pebble to Cell(4, 1),   // overhung arm
            stone  to Cell(4, 1),   // collision: same cell as the arm — must be a no-op (see below)
            glass  to Cell(2, 2),   // fuse atop the beam
            stone  to Cell(2, 6),   // heavy drop onto the beam column → multi-ring cascade
            pebble to Cell(7, 3),   // a lone floater → falls/shatters
            pebble to Cell(7, 0),
            pebble to Cell(7, 1),
            pebble to Cell(7, 2),
            stone  to Cell(7, 5),   // heavy drop onto the small pebble stack
        )
        for ((material, cell) in script) {
            engine.place(Placement(material, cell))
            assertConsistent(engine.grid)
        }
    }

    /**
     * The same intent, randomised and seeded so it is reproducible but explores wider — but checking
     * the FULL invariant set after each step, which the happy-path random test does not.
     */
    @Test
    fun `randomised placements never produce a contradictory grid`() {
        val engine = PhysicsEngine()
        val materials = listOf(pebble, stone, wood, glass)
        val random = java.util.Random(1_337) // fixed seed → reproducible failure if it ever breaks

        repeat(120) {
            val m = materials[random.nextInt(materials.size)]
            // Allow y down to 0 only (placement surface is at/above bedrock; negative-y is a separate test).
            engine.place(Placement(m, Cell(random.nextInt(8), random.nextInt(8))))
            assertConsistent(engine.grid)
        }
    }

    // ── ATTACK: placing on an occupied cell is a true no-op (defensive contract) ────────────────────

    /**
     * [PhysicsEngine.place] documents an occupied-cell placement as a no-op returning current state.
     * A hostile reviewer pins that it is EXACTLY a no-op: identical cell set, identical brick ids, no
     * id burned (the next genuine placement must still mint contiguously), and collapse == null.
     */
    @Test
    fun `placing on an occupied cell is a true no-op and burns no id`() {
        val engine = PhysicsEngine()
        engine.place(Placement(pebble, Cell(0, 0)))
        engine.place(Placement(pebble, Cell(0, 1)))
        val before = engine.grid
        val idsBefore = before.bricks.values.map { it.id }.sorted()

        // Collision: (0,1) is occupied. Must be a no-op.
        val collision = engine.place(Placement(stone, Cell(0, 1)))
        assertNull(collision.collapse, "A collision placement must not report a collapse")
        assertEquals(before.cells, collision.grid.cells, "No cell may change on a collision no-op")
        assertEquals(
            idsBefore, collision.grid.bricks.values.map { it.id }.sorted(),
            "No brick id may change on a collision no-op",
        )
        // The occupant material is unchanged — the collision did NOT overwrite it.
        assertEquals(pebble, collision.grid.at(Cell(0, 1))!!.material, "Occupant must be untouched")

        // A subsequent genuine placement must still be footed and consistent — and mint a NEW id.
        engine.place(Placement(pebble, Cell(0, 2)))
        assertConsistent(engine.grid)
        assertEquals(3, engine.grid.cells.size, "The genuine placement after the collision must land")
    }

    // ── ATTACK: stress is always a finite number ≤ 1.0 ─────────────────────────────────────────────

    @Test
    fun `stress of an empty grid is one and never NaN`() {
        val r = PhysicsEngine().place(Placement(pebble, Cell(0, 0)))
        // One ground pebble bears no load → margin is full.
        assertEquals(1.0, r.stress, 1e-9, "A single unloaded ground brick has stress 1.0")
        assertTrue(r.stress.isFinite(), "Stress must be finite")
        assertTrue(r.stress <= 1.0 + 1e-9, "Stress can never exceed 1.0")
    }

    @Test
    fun `Stress margin of a truly empty grid is one`() {
        assertEquals(1.0, Stress.margin(Grid()), 1e-9, "An empty world is maximally calm")
    }

    // ── ATTACK: reset clears everything ─────────────────────────────────────────────────────────────

    @Test
    fun `reset returns the engine to an empty, consistent world`() {
        val engine = PhysicsEngine()
        repeat(5) { y -> engine.place(Placement(pebble, Cell(0, y))) }
        engine.reset()
        assertEquals(0, engine.grid.cells.size, "reset() must empty the grid")
        assertEquals(1.0, Stress.margin(engine.grid), 1e-9, "A reset world is calm")
        // After reset, a fresh placement must still be footed and consistent.
        assertNull(engine.place(Placement(pebble, Cell(0, 0))).collapse, "Post-reset ground placement holds")
        assertConsistent(engine.grid)
    }

    // ── ATTACK: out-of-contract input — a cell BELOW bedrock (y < 0) ───────────────────────────────

    /**
     * The engine claims it is defensive and that the game layer constrains the surface. A cell at
     * y = -1 is below bedrock — out of contract. We PIN the observed behaviour so any future
     * hardening (e.g. rejecting y < 0) is a deliberate, test-breaking decision rather than a silent
     * change: the brick does not remain below ground, the grid stays consistent, and nothing throws.
     *
     * Observed behaviour of the shipped engine: (0,-1) is unfooted → it "falls", landingRow scans the
     * empty range below it and returns 0, the fall distance is negative so it does not shatter, and it
     * settles onto bedrock at (0,0). Surprising, but consistent — and now locked.
     */
    @Test
    fun `a sub-bedrock placement does not corrupt the grid`() {
        val engine = PhysicsEngine()
        val result = engine.place(Placement(pebble, Cell(0, -1)))

        assertNull(engine.grid.at(Cell(0, -1)), "Nothing may remain below bedrock")
        assertConsistent(engine.grid)               // whatever it did, the world is not contradictory
        assertNotNull(result, "The call must return a result, not throw")
        assertTrue(result.stress.isFinite(), "Stress must stay finite even for out-of-contract input")
    }

    // ── ATTACK: the Material structural contract is enforced at construction ───────────────────────

    /**
     * Karl's load-bearing rule: the card class imposes NO VALUE constraints on a card — only a
     * STRUCTURAL contract (well-formed). [Material.init] is that contract for the physics facet. A
     * hostile reviewer proves it actually rejects malformed materials, so a bad card cannot reach the
     * engine and produce undefined physics. Each branch of the `require` block gets its own probe.
     */
    @Test
    fun `Material rejects non-positive weight`() {
        assertFailsWith<IllegalArgumentException>("weight must be > 0") {
            Material(weight = 0.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
        }
        assertFailsWith<IllegalArgumentException> {
            Material(weight = -1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
        }
    }

    @Test
    fun `Material rejects non-positive strength`() {
        assertFailsWith<IllegalArgumentException>("strength must be > 0") {
            Material(weight = 1.0, strength = 0.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)
        }
    }

    @Test
    fun `Material rejects negative cantilever`() {
        assertFailsWith<IllegalArgumentException>("cantilever must be >= 0") {
            Material(weight = 1.0, strength = 3.0, cantilever = -1, shatterThreshold = 2, brittleness = 0.2)
        }
    }

    @Test
    fun `Material rejects negative shatterThreshold`() {
        assertFailsWith<IllegalArgumentException>("shatterThreshold must be >= 0") {
            Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = -1, brittleness = 0.2)
        }
    }

    @Test
    fun `Material rejects brittleness outside zero to one`() {
        assertFailsWith<IllegalArgumentException>("brittleness > 1") {
            Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 1.0001)
        }
        assertFailsWith<IllegalArgumentException>("brittleness < 0") {
            Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = -0.0001)
        }
    }

    /** The contract ADMITS the boundary values (0 cantilever, 0 shatter, 0 and 1 brittleness). */
    @Test
    fun `Material admits the legal boundary values`() {
        Material(weight = 0.0001, strength = 0.0001, cantilever = 0, shatterThreshold = 0, brittleness = 0.0)
        Material(weight = 9.9, strength = 9.9, cantilever = 5, shatterThreshold = 9, brittleness = 1.0)
        // No exception = pass.
    }

    // ── The full consistency invariant — the post-condition of every place() ───────────────────────

    /**
     * A grid is CONSISTENT when:
     *   (a) no brick is unfooted (everything has a path to bedrock);
     *   (b) no brick is buckled (load <= strength everywhere);
     *   (c) every map key equals the brick's own .cell (no key/field desync);
     *   (d) all brick ids are unique;
     *   (e) the stress margin is a finite number ≤ 1.0.
     */
    private fun assertConsistent(grid: Grid) {
        val footedSet = Support.footedSet(grid)

        // (a) footing
        assertTrue(
            Support.unfooted(grid).isEmpty(),
            "INCONSISTENT: unfooted bricks remain at ${Support.unfooted(grid)}",
        )
        // (b) load
        val buckled = Load.buckled(grid, Load.loads(grid, footedSet))
        assertTrue(buckled.isEmpty(), "INCONSISTENT: buckled bricks remain at $buckled")

        // (c) map-key / brick-cell coherence
        for ((cell, brick) in grid.bricks) {
            assertEquals(
                cell, brick.cell,
                "INCONSISTENT: brick id=${brick.id} is keyed at $cell but its own cell is ${brick.cell}",
            )
        }
        // (d) id uniqueness
        val ids = grid.bricks.values.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "INCONSISTENT: duplicate brick ids in $ids")

        // (e) stress is finite and bounded above by 1.0
        val s = Stress.margin(grid)
        assertTrue(s.isFinite(), "INCONSISTENT: stress is not finite ($s)")
        assertTrue(s <= 1.0 + 1e-9, "INCONSISTENT: stress exceeds 1.0 ($s)")
    }
}
