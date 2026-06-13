package com.thuruummm.physics

/**
 * The tightest stress margin in the current grid — the emergent urgency signal.
 *
 * PHYSICS.md §"Time without a timer": "as you build, bricks sit closer to their strength limit —
 * their margin shrinks. A near-the-edge structure is stressed; the engine exposes its tightest
 * margin so the render / haptic layer can make it tremble."
 *
 * This is a thin wrapper that delegates to [Load.tightestMargin] — kept as a named object so the
 * game orchestrator has one clean call site and the name makes the intent obvious.
 */
object Stress {
    /**
     * Computes the tightest (strength - load) / strength margin across all footed bricks.
     *
     * @return a value in [-∞, 1.0]. 1.0 = no stress. 0.0 = exactly at limit. Negative = already
     *         buckled (should have cascaded, but reported for completeness if called mid-step).
     *         The render/haptic layer maps this to a shake amplitude or tremble frequency.
     */
    fun margin(grid: Grid): Double {
        val footing = Support.footing(grid)
        val loads = Load.loads(grid, footing)
        return Load.tightestMargin(grid, footing.cells, loads)
    }
}
