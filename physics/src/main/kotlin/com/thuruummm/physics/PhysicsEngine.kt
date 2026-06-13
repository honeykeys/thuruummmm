package com.thuruummm.physics

/**
 * The result of one [PhysicsEngine.place] call.
 *
 * @param grid     The stabilised grid after placing the brick and resolving any collapse.
 * @param collapse Non-null if the placement triggered a cascade. Carries the fell list, ring
 *                 count, material variety, and THRUUMMMM [CollapseResult.magnitude].
 * @param stress   The tightest stress margin in [grid] after stabilisation. 1.0 = calm; near 0.0
 *                 = the next brick could tip it; negative = (should not occur post-stabilisation
 *                 but included for diagnostics). The render/haptic layer maps this to trembling.
 */
data class StepResult(
    val grid: Grid,
    val collapse: CollapseResult?,
    val stress: Double,
)

/**
 * The single entry point for the physics engine.
 *
 * Encapsulates the three-question loop described in PHYSICS.md:
 *   Q1 — support  ([Support])
 *   Q2 — load     ([Load])
 *   Q3 — cascade  ([Cascade])
 *
 * The engine is stateful — it owns the current [Grid] and mutates it on each [place] call.
 * State lives here; the Compose layer holds this engine in a `remember { PhysicsEngine() }`.
 * There is no threading inside the engine; the game loop drives it synchronously per frame.
 *
 * This class is the ONLY class from `:physics` that the `:app` game orchestrator imports for
 * write access. Everything else is a data type flowing outward.
 */
class PhysicsEngine(initialGrid: Grid = Grid()) {

    private var _grid: Grid = initialGrid

    /** Current grid — readable by the render layer for snapshot building. */
    val grid: Grid get() = _grid

    /**
     * Place a brick of [placement.material] at [placement.cell].
     *
     * Pipeline:
     * 1. Attempt the placement. If the target cell is occupied, return the current state unchanged
     *    (the gesture classifier should avoid this but the engine is defensive).
     * 2. Q1: Check support of the newly placed brick. If it has no footing, it falls immediately.
     * 3. Q2: Check load — does the new brick overload anything below it?
     * 4. Q3: If anything fails, run [Cascade] from the lowest failing brick.
     * 5. Compute [Stress.margin] on the stabilised grid.
     * 6. Return [StepResult].
     */
    fun place(placement: Placement): StepResult {
        if (_grid.occupied(placement.cell)) {
            // Cell already taken; no-op. Return current state.
            return StepResult(
                grid = _grid,
                collapse = null,
                stress = Stress.margin(_grid),
            )
        }

        // --- Place ---
        val (workingGrid, _) = _grid.place(placement.material, placement.cell)

        // --- Q1: Support — bricks with no path to bedrock fall. ---
        val footing = Support.footing(workingGrid)
        val footedSet = footing.cells
        val unfooted = workingGrid.cells - footedSet

        // --- Q2: Load — among footed bricks, which buckle (load > strength)? ---
        val loads = Load.loads(workingGrid, footing)
        val buckled = Load.buckled(workingGrid, loads)

        // Seed the cascade with the genuine first wave, separating the two failure modes (they fall
        // differently — see Cascade.run):
        //   - bucklers: the LOWEST buckled brick — "the lowest buckling brick is where the collapse
        //     begins" (PHYSICS.md). It is consumed; the cascade re-derives any higher bucklers each
        //     ring, so the ring count honestly reflects the real propagation (which drives magnitude).
        //   - fallers: every unfooted brick (Q1), plus everything the lowest buckle leaves unsupported.
        val initialBuckled: Set<Cell> =
            if (buckled.isEmpty()) emptySet() else setOf(buckled.minByOrNull { it.y }!!)
        val initialUnfooted: Set<Cell> =
            unfooted + Support.unsupportedIfRemoved(workingGrid, initialBuckled, footedSet) - initialBuckled

        val collapse: CollapseResult? =
            if (initialUnfooted.isEmpty() && initialBuckled.isEmpty()) {
                _grid = workingGrid
                null
            } else {
                val result = Cascade.run(workingGrid, initialUnfooted, initialBuckled)
                _grid = result.finalGrid
                result
            }

        val stress = Stress.margin(_grid)
        return StepResult(grid = _grid, collapse = collapse, stress = stress)
    }

    /** Reset the engine to an empty grid. Used by the Start screen restart path. */
    fun reset() {
        _grid = Grid()
    }
}
