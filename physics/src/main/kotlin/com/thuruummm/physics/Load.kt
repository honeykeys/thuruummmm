package com.thuruummm.physics

/**
 * Q2: "Do my shoulders hold?" (PHYSICS.md §"Do my shoulders hold?")
 *
 * For every footed brick, compute the total load resting on it — the sum of weights of every brick
 * that routes its weight down through it — then a brick buckles when (load > strength).
 *
 * Load model (MVP, per PHYSICS.md "What the MVP deliberately leaves out"):
 *   - A brick's weight + everything above it routes to the brick directly below.
 *   - A cantilever arm (footed only because a beam overhangs to it, not because the cell below is
 *     footed) routes its accumulated load ONTO THE BEAM THAT FOOT IT — the beam recorded as its
 *     [Support.Footing.cantileverParent] by the same flood that decided footing — so the beam bears
 *     the arm cantilevered onto it ("a brick carries … plus any arms cantilevered onto it",
 *     PHYSICS.md). The beam then routes that load down its own column to bedrock. The arm is NOT
 *     bypassed onto a structurally-unrelated column (the BUG-1/BUG-2 defect: a cantilever beam that
 *     could never register the load it holds and so could never buckle, gutting the core reward loop).
 *
 * This is the MVP approximation of Red Faction's layer-stress model; Zarkonnen's full directional
 * routing is a noted future upgrade. Purely functional: no mutation of inputs, no Android imports.
 */
object Load {

    /**
     * Computes the downward load on each footed cell of [grid] given its [footedSet].
     * Cells absent from the result carry zero load. Unfooted bricks are not in scope — [Support]
     * and [Cascade] handle them. Convenience overload; routes via a freshly-computed footing flood.
     */
    fun loads(grid: Grid, footedSet: Set<Cell>): Map<Cell, Double> =
        loads(grid, Support.footing(grid))

    /**
     * Computes the downward load on each footed cell, routing cantilever-arm load through the SAME
     * footing flood [Support] used to decide footing — so support and load can never disagree about
     * who carries an arm (the divergence the reviewer flagged as BUG-2).
     *
     * Processed strictly top row → bottom row. Each cell routes its accumulated load (own weight +
     * everything already settled onto it) onto exactly ONE lower-or-equal cell:
     *   - ground (y == 0): terminal — bedrock bears it.
     *   - a vertically-stacked brick (footed cell directly below): pours straight down.
     *   - a cantilever ARM (footed only by an overhang): pours onto its [Footing.cantileverParent] —
     *     the beam that footed it in the flood, which is on the SAME row and nearer bedrock. The beam
     *     therefore REGISTERS the cantilevered arm (it can now buckle — the BUG-1 fix), then routes
     *     that load down its own column on the same row's pass.
     *
     * Ordering within a row: the cantilever-parent map points strictly inward (toward the supporting
     * column, by construction of the breadth-first flood), so it is a forest with no same-row cycles.
     * We settle each row's arms onto their parents in inward order (an arm's parent must receive the
     * arm's load before the parent itself routes), computed by descending hop-distance-to-a-non-arm.
     * Then every non-arm cell on the row pours straight down. This keeps the result independent of
     * map iteration order while letting a chain of arms accumulate onto the beam that ultimately holds
     * them.
     */
    fun loads(grid: Grid, footing: Support.Footing): Map<Cell, Double> {
        val footedSet = footing.cells
        val parent = footing.cantileverParent
        val footedCells = grid.cells.filter { it in footedSet }
        if (footedCells.isEmpty()) return emptyMap()
        val maxRow = footedCells.maxOf { it.y }

        // load[c] = weight routed onto c from bricks above it AND from arms cantilevered onto it
        // (not counting c's own weight).
        val load = HashMap<Cell, Double>()

        // An arm is a footed brick whose footing came from an overhang, recorded by the flood.
        val isArm: (Cell) -> Boolean = { it in parent }

        for (row in maxRow downTo 0) {
            val rowCells = footedCells.filter { it.y == row }

            // Phase A: settle arms onto their parent beams, outermost first. An arm's hop distance to
            // its nearest non-arm ancestor orders the chain: the further-out arm settles before the
            // one it rests on, so each parent has received its children's load before it routes.
            val armsByDepth = rowCells
                .filter { isArm(it) }
                .sortedByDescending { hopsToVerticalSupport(it, parent) }
            for (arm in armsByDepth) {
                val beam = parent[arm] ?: continue              // defensive; isArm guarantees presence
                val transfer = grid.at(arm)!!.material.weight + (load[arm] ?: 0.0)
                load[beam] = (load[beam] ?: 0.0) + transfer
            }

            // Phase B: every non-arm cell pours its accumulated load straight down its column.
            for (cell in rowCells) {
                if (isArm(cell)) continue                       // settled laterally in Phase A
                if (cell.y == 0) continue                       // ground: terminal, bedrock bears it
                val below = cell.below()                        // guaranteed footed (non-arm above ground)
                val transfer = grid.at(cell)!!.material.weight + (load[cell] ?: 0.0)
                load[below] = (load[below] ?: 0.0) + transfer
            }
        }
        return load
    }

    /** Hops along the cantilever-parent chain until a vertically-supported (non-arm) cell is reached. */
    private fun hopsToVerticalSupport(cell: Cell, parent: Map<Cell, Cell>): Int {
        var hops = 0
        var c = cell
        // The parent map is a finite forest pointing inward; bound the walk by its size defensively.
        while (c in parent && hops <= parent.size) {
            c = parent.getValue(c)
            hops++
        }
        return hops
    }

    /**
     * The set of footed cells whose load exceeds their material strength — the buckled bricks.
     * [loads] is the map from [loads].
     */
    fun buckled(grid: Grid, loads: Map<Cell, Double>): Set<Cell> =
        buildSet {
            for ((cell, l) in loads) {
                val brick = grid.at(cell) ?: continue
                if (l > brick.material.strength) add(cell)
            }
        }

    /**
     * The tightest stress margin across all footed bricks: (strength − load) / strength.
     * 1.0 = no load at all; → 0.0 = on the edge; negative = already buckled. Drives render/haptic
     * trembling (PHYSICS.md "Time without a timer"). Returns 1.0 for an empty/unloaded grid.
     */
    fun tightestMargin(grid: Grid, footedSet: Set<Cell>, loads: Map<Cell, Double>): Double {
        var tightest = 1.0
        for (cell in footedSet) {
            val brick = grid.at(cell) ?: continue
            val l = loads[cell] ?: 0.0
            val margin = (brick.material.strength - l) / brick.material.strength
            if (margin < tightest) tightest = margin
        }
        return tightest
    }
}
