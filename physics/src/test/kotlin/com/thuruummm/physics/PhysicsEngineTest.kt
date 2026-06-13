package com.thuruummm.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 7 corner cases from PHYSICS.md §"What we will prove", made faithful to the corrected engine.
 *
 * Pure JVM — no Android import, no device. Run with:
 *   ./gradlew :physics:test
 *
 * Engine model under test (the corrections this suite encodes):
 *   - Every cell at y == 0 sits on bedrock and is footed unconditionally. Cantilever/overhang is only
 *     exercised ABOVE the ground row, leaning out past the edge of a base — so the cantilever tests
 *     build an elevated beam, not two bricks on the floor.
 *   - A brick that BUCKLES (load > strength) is consumed as debris; a brick that loses its FOOTING
 *     falls and either lands intact (< shatterThreshold) or shatters (≥ shatterThreshold).
 *   - magnitude = fell.size × rings × material-variety.
 */
class PhysicsEngineTest {

    // ── Materials ────────────────────────────────────────────────────────────────────────────────

    /** Light, weak, no overhang, shatters at 2 cells, mildly brittle. */
    private val pebble = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)

    /** Heavy, strong, no overhang, survives long falls. */
    private val stone = Material(weight = 3.0, strength = 9.0, cantilever = 0, shatterThreshold = 4, brittleness = 0.5)

    /** Medium, moderate strength, overhang reach = 2 (the beam material). */
    private val wood = Material(weight = 1.5, strength = 5.0, cantilever = 2, shatterThreshold = 3, brittleness = 0.1)

    /** Very brittle — shatters after a 1-cell drop, hard brittleness shove. */
    private val glass = Material(weight = 1.0, strength = 2.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.9)

    /** Strong beam anchor with overhang reach = 2, effectively unbreakable for footing tests. */
    private val anchor = Material(weight = 2.0, strength = 100.0, cantilever = 2, shatterThreshold = 10, brittleness = 0.0)

    // ── Test 1: short stack stands; tall stack buckles at the bottom ──────────────────────────────

    /** PHYSICS.md 1a: a short stack stands. Bottom pebble bears 2.0 < strength 3.0. */
    @Test
    fun `short stack stands`() {
        val engine = PhysicsEngine()
        engine.place(Placement(pebble, Cell(0, 0)))
        engine.place(Placement(pebble, Cell(0, 1)))
        val result = engine.place(Placement(pebble, Cell(0, 2)))

        assertNull(result.collapse, "Short stack of 3 pebbles should not collapse")
        assertEquals(3, result.grid.cells.size, "All 3 bricks should still stand")
    }

    /**
     * PHYSICS.md 1b: a stack taller than the base material's strength buckles at the bottom.
     * Four pebbles put load 3.0 on the bottom (at the limit). The fifth pushes it to 4.0 > 3.0.
     * The bottom buckler is consumed; the rest fall and re-settle into a shorter, stable column.
     */
    @Test
    fun `tall stack buckles at the bottom`() {
        val engine = PhysicsEngine()
        repeat(4) { y -> engine.place(Placement(pebble, Cell(0, y))) }
        val result = engine.place(Placement(pebble, Cell(0, 4)))

        assertNotNull(result.collapse, "Stack overloaded at the bottom should collapse")
        assertTrue(result.collapse!!.fell.isNotEmpty(), "Bricks should have fallen")
        assertGridIsStable(result.grid)
    }

    // ── Test 2: floating brick falls immediately ──────────────────────────────────────────────────

    /**
     * PHYSICS.md 2: a floating brick (no path to ground) falls immediately.
     * A pebble placed at y = 3 over empty space falls 3 cells. 3 ≥ shatterThreshold(2), so per the
     * spec it SHATTERS on impact — it does not survive. We assert it left its cell and the grid is
     * empty afterward (nothing floating, nothing magically intact).
     */
    @Test
    fun `floating brick falls and shatters`() {
        val engine = PhysicsEngine()
        val result = engine.place(Placement(pebble, Cell(0, 3)))

        assertNotNull(result.collapse, "A floating brick should fall (a collapse result)")
        assertTrue(result.collapse!!.fell.any { it.cell == Cell(0, 3) }, "The pebble should be in the fell list")
        assertNull(result.grid.at(Cell(0, 3)), "Cell (0,3) must be empty after the fall")
        assertEquals(0, result.grid.cells.size, "Fell 3 ≥ shatter@2 → it shatters; grid is empty")
    }

    /** A short drop survives: a pebble dropped onto a one-high base falls 1 < shatter@2 and lands intact. */
    @Test
    fun `short drop lands intact`() {
        val engine = PhysicsEngine()
        engine.place(Placement(pebble, Cell(0, 0)))     // base on the ground
        val result = engine.place(Placement(pebble, Cell(0, 2))) // floats one cell above the base top (y=1)

        assertNotNull(result.collapse, "The floating pebble fell")
        assertNull(result.grid.at(Cell(0, 2)), "It must not remain floating at (0,2)")
        assertNotNull(result.grid.at(Cell(0, 1)), "Fell 1 < shatter@2 → lands intact on the base at (0,1)")
        assertEquals(2, result.grid.cells.size, "Base + landed brick both present")
    }

    // ── Test 3: cantilever / overhang (exercised ABOVE the ground row) ───────────────────────────

    /**
     * PHYSICS.md 3a: a cantilever within reach holds.
     * Build a two-high anchor column at x=0 (anchor reach = 2). Lean a pebble out at (2,1) — two
     * cells sideways from the footed beam at (1,1)... no: the beam is the anchor at (0,1). Reach 2
     * foots (2,1) over the empty gap at (1,1). The pebble holds, footed by the overhang.
     */
    @Test
    fun `cantilever within reach holds`() {
        val engine = PhysicsEngine()
        engine.place(Placement(anchor, Cell(0, 0)))   // ground
        engine.place(Placement(anchor, Cell(0, 1)))   // elevated beam, reach 2
        val result = engine.place(Placement(pebble, Cell(2, 1))) // 2 cells out over the edge

        assertNull(result.collapse, "Pebble at overhang reach = 2 should hold")
        assertNotNull(result.grid.at(Cell(2, 1)), "Pebble should remain at (2,1), footed by the overhang")
    }

    /**
     * PHYSICS.md 3b: one cell past its reach falls.
     * Same elevated beam; the pebble at (3,1) is three cells out — past the reach of 2 — and there is
     * nothing below it, so it has no footing and falls.
     */
    @Test
    fun `cantilever one cell past reach falls`() {
        val engine = PhysicsEngine()
        engine.place(Placement(anchor, Cell(0, 0)))
        engine.place(Placement(anchor, Cell(0, 1)))
        val result = engine.place(Placement(pebble, Cell(3, 1))) // 3 cells out — past reach 2

        assertNotNull(result.collapse, "Pebble past overhang reach should fall")
        assertNull(result.grid.at(Cell(3, 1)), "Pebble must not remain at (3,1) — no footing there")
        // Strengthened (reviewer BUG-4): assert the brick is actually GONE, not merely displaced from
        // its cell. The pebble falls 1 cell to the ground at (3,0) (1 < shatter@2 → lands intact), so
        // the only bricks left are the two-high anchor column. Nothing floats; the faller is accounted.
        assertEquals(3, result.grid.cells.size, "Anchor column (2) + the pebble grounded at (3,0)")
        assertNotNull(result.grid.at(Cell(3, 0)), "The unfooted pebble drops to bedrock at (3,0)")
        assertTrue(
            result.collapse!!.fell.any { it.cell == Cell(3, 1) },
            "The pebble that lost footing must appear in the fell list",
        )
        assertGridIsStable(result.grid)
    }

    // ── Test 4: overloading a brick fails it and drops everything above ──────────────────────────

    /**
     * PHYSICS.md 4: overloading a brick fails that brick and drops everything above it.
     * Strong stone base at y=0; a weak pebble (strength 3.0) at y=1 is the deliberate weak link.
     * Stack pebbles above until the weak link bears > 3.0 — it buckles and the column above comes
     * down. The buckler is consumed; the survivors re-settle stably.
     */
    @Test
    fun `overloaded brick collapses with everything above it`() {
        val engine = PhysicsEngine()
        engine.place(Placement(stone, Cell(0, 0)))   // strong base
        engine.place(Placement(pebble, Cell(0, 1)))  // weak link, strength 3.0
        engine.place(Placement(pebble, Cell(0, 2)))
        engine.place(Placement(pebble, Cell(0, 3)))
        engine.place(Placement(pebble, Cell(0, 4)))  // weak link now bears 3.0 — at the limit
        val result = engine.place(Placement(pebble, Cell(0, 5))) // 4.0 > 3.0 → buckle

        assertNotNull(result.collapse, "Overloaded weak link should trigger a collapse")
        assertTrue(result.collapse!!.fell.isNotEmpty(), "The buckler and the column above should fall")
        assertGridIsStable(result.grid)
    }

    // ── Test 5: brittle shatter triggers a multi-ring cascade ────────────────────────────────────

    /**
     * PHYSICS.md 5: a brittle brick dropped into a stack shatters and triggers a multi-ring cascade.
     * Two glass bricks (strength 2.0) on the ground carry a pebble bridge; a heavy stone dropped from
     * height lands on the bridge, overloading the glass below across more than one ring.
     *
     * The point under test: the cascade propagates over MULTIPLE rings (rings ≥ 2), not all at once.
     */
    @Test
    fun `shattering brittle structure triggers multi-ring cascade`() {
        val engine = PhysicsEngine()
        engine.place(Placement(glass, Cell(0, 0)))   // strength 2.0
        engine.place(Placement(pebble, Cell(0, 1)))  // sits on the glass
        engine.place(Placement(pebble, Cell(0, 2)))  // a little stack
        // Heavy stone dropped from above lands on the pebble stack and overloads the glass beneath.
        val result = engine.place(Placement(stone, Cell(0, 5)))

        assertNotNull(result.collapse, "The heavy drop should cascade")
        assertTrue(
            result.collapse!!.rings >= 2,
            "Expected a multi-ring cascade; got ${result.collapse.rings} ring(s)",
        )
        assertTrue(result.collapse.fell.isNotEmpty(), "Bricks should have fallen")
        assertGridIsStable(result.grid)
    }

    // ── Test 6: tangled complexity outscores a taller plain tower ────────────────────────────────

    /**
     * PHYSICS.md 6: a tangled structure scores a higher THRUUMMMM magnitude than a taller plain tower
     * with MORE bricks — complexity beats size.
     *
     * Plain tower: a single tall column of one material. It collapses in one ring, one material →
     *   magnitude = count × 1 × 1.
     * Tangled structure: mixed materials and an overhang, so the collapse spans more rings and more
     *   material variety → magnitude = (fewer count) × (more rings) × (more variety), which wins.
     */
    @Test
    fun `tangled collapse outscores a taller plain tower`() {
        // Plain tower: a single column of pebbles, stable at 4 (bottom bears 3.0, at the limit), then
        // a 5th brick tips it. One material, one ring → magnitude = count × 1 × 1.
        val tower = PhysicsEngine()
        repeat(4) { y -> tower.place(Placement(pebble, Cell(5, y))) } // stable: bottom bears 3.0
        val towerResult = tower.place(Placement(pebble, Cell(5, 4)))  // 4.0 > 3.0 → buckle
        val towerMagnitude = towerResult.collapse?.magnitude ?: 0.0

        // Tangled: three materials and an overhang. A wood beam column carries a glass fuse on top and
        // a glass-armed overhang to the side; a heavy stone cap overloads the glass, whose failure
        // pulls down the overhung arm too — more rings, three materials.
        val tangled = PhysicsEngine()
        tangled.place(Placement(wood, Cell(0, 0)))    // beam base, reach 2
        tangled.place(Placement(wood, Cell(0, 1)))    // beam, reach 2
        tangled.place(Placement(glass, Cell(2, 1)))   // overhung glass arm, footed by the beam reach
        tangled.place(Placement(glass, Cell(0, 2)))   // glass fuse atop the wood column
        val tangledResult = tangled.place(Placement(stone, Cell(0, 3))) // heavy cap → overload + cascade

        val tangledMagnitude = tangledResult.collapse?.magnitude ?: 0.0

        assertNotNull(towerResult.collapse, "The plain tower should collapse")
        assertNotNull(tangledResult.collapse, "The tangled structure should collapse")
        assertTrue(
            tangledResult.collapse!!.materials.size > towerResult.collapse!!.materials.size,
            "Tangled collapse should involve more material variety " +
                "(${tangledResult.collapse.materials.size}) than the single-material tower " +
                "(${towerResult.collapse.materials.size})",
        )
        assertTrue(
            tangledMagnitude > towerMagnitude,
            "Tangled magnitude ($tangledMagnitude: fell=${tangledResult.collapse.fell.size}, " +
                "rings=${tangledResult.collapse.rings}, variety=${tangledResult.collapse.materials.size}) " +
                "should beat the taller plain tower ($towerMagnitude: fell=${towerResult.collapse.fell.size}, " +
                "rings=${towerResult.collapse.rings}, variety=${towerResult.collapse.materials.size})",
        )
    }

    // ── Test 7: repeated place/collapse leaves a consistent state ─────────────────────────────────

    /**
     * PHYSICS.md 7: repeated place/collapse leaves the structure in a consistent, non-contradictory
     * state — after EVERY place, no unfooted brick and no buckled brick remains.
     */
    @Test
    fun `grid is consistent after every placement`() {
        val engine = PhysicsEngine()
        val materials = listOf(pebble, stone, wood, glass, anchor)
        val random = java.util.Random(42) // fixed seed → reproducible

        repeat(60) {
            val material = materials[random.nextInt(materials.size)]
            engine.place(Placement(material, Cell(random.nextInt(6), random.nextInt(7))))
            assertGridIsStable(engine.grid)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    /** A stable grid — the post-condition of every [PhysicsEngine.place] — has no unfooted, no buckled brick. */
    private fun assertGridIsStable(grid: Grid) {
        val footedSet = Support.footedSet(grid)
        assertTrue(
            Support.unfooted(grid).isEmpty(),
            "Grid has unfooted bricks at ${Support.unfooted(grid)} — cascade did not stabilise",
        )
        val buckled = Load.buckled(grid, Load.loads(grid, footedSet))
        assertTrue(buckled.isEmpty(), "Grid has buckled bricks at $buckled — cascade did not stabilise")
    }
}
