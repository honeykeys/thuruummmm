package com.thuruummm.physics

/**
 * Q3: "What falls, and does it take others with it?" (PHYSICS.md §"What falls, does it take others?")
 *
 * A cascade is the ring-by-ring propagation of failures after an initial set of doomed bricks is
 * identified. Each ring:
 *   1. Drop all currently-doomed bricks. Within a ring, bricks are dropped bottom-up per column so a
 *      brick never lands on another doomed brick (which is itself about to fall) — the doomed set is
 *      removed from the working surface up front, so landings are computed against what actually
 *      remains. (This is the load-bearing fix: fall distances and shatter decisions feed the
 *      THRUUMMMM magnitude, so they must be physically right, not order-dependent.)
 *   2. A brick that falls ≥ [Material.shatterThreshold] cells SHATTERS: it is gone, and it applies a
 *      lateral shove (= weight × brittleness) to the bricks beside its landing cell. That shove is
 *      extra load that can push a neighbour past its strength.
 *   3. Re-evaluate support + load on the settled grid (with the shatter shoves folded in). New
 *      failures seed the next ring.
 *   4. Repeat until a ring produces no new failures.
 *
 * Each pass is one **ring** (PHYSICS.md). Rings × variety × count is the THRUUMMMM magnitude, so the
 * ring count is a first-class output, not an implementation detail.
 *
 * Purely functional: no side effects, no mutation of inputs, no Android imports.
 */
object Cascade {

    /**
     * Run the full cascade from [initialGrid].
     *
     * Two flavours of failure seed each ring, and they fall DIFFERENTLY:
     *   - [initialBuckled] — bricks whose shoulders gave out (load > strength). A buckled brick is
     *     CONSUMED: it crumbles to debris and is removed, exactly like Red Faction's failed layer.
     *     It does not re-land (which would let an overloaded column simply re-stack and re-overload —
     *     a non-terminating no-op). Its removal is what lets the structure above come down.
     *   - [initialUnfooted] — bricks that lost their footing. These FALL: they drop to a landing, and
     *     if they fell ≥ shatterThreshold they shatter (and shove neighbours), else they land intact.
     *
     * Returns a [CollapseResult]: everything that left its place across all rings, the ring count,
     * the material variety, and the stabilised grid.
     */
    fun run(initialGrid: Grid, initialUnfooted: Set<Cell>, initialBuckled: Set<Cell>): CollapseResult {
        var grid = initialGrid
        // Keyed by brick id so a brick that moves through several rings (falls, lands, falls again)
        // is counted ONCE in the report — its last-seen state wins.
        val fellById = LinkedHashMap<Int, Brick>()
        val allMaterials = mutableSetOf<Material>()
        var rings = 0

        // Guard against a pathological non-terminating cascade. Each ring removes at least one brick
        // (a consumed buckler) or relocates fallers to strictly lower cells, so the process is finite;
        // this bound is defence-in-depth, sized above any plausible structure.
        val maxRings = initialGrid.cells.size + 2

        var fallers: Set<Cell> = initialUnfooted.filter { grid.occupied(it) }.toSet()
        var bucklers: Set<Cell> = initialBuckled.filter { grid.occupied(it) }.toSet()

        while ((fallers.isNotEmpty() || bucklers.isNotEmpty()) && rings < maxRings) {
            rings++

            // --- 1. Consume bucklers (debris, gone) and lift fallers off the surface BEFORE any
            // landing is computed, so a faller never lands on a brick that is itself leaving. ---
            val buckledBricks = bucklers.mapNotNull { grid.at(it) }
            val fallerBricks = fallers.mapNotNull { grid.at(it) }

            buckledBricks.forEach { fellById[it.id] = it; allMaterials += it.material }
            fallerBricks.forEach { fellById[it.id] = it; allMaterials += it.material }

            var working = grid.remove(bucklers + fallers)

            // --- Drop fallers bottom-up per column. Ascending-y order means every lower faller in a
            // column has already settled (or shattered) before a higher one resolves its landing. ---
            val shoves = mutableMapOf<Cell, Double>() // brittleness shove → extra load, this ring only
            for (brick in fallerBricks.sortedBy { it.cell.y }) {
                val from = brick.cell
                val landingY = landingRow(working, from.x, from.y)
                val fellDistance = from.y - landingY

                if (fellDistance >= brick.material.shatterThreshold) {
                    // Shatters: gone; shove the bricks beside its landing cell.
                    val shove = brick.material.weight * brick.material.brittleness
                    if (shove > 0.0) {
                        for (neighbour in Cell(from.x, landingY).neighbours()) {
                            if (working.occupied(neighbour)) {
                                shoves[neighbour] = (shoves[neighbour] ?: 0.0) + shove
                            }
                        }
                    }
                } else {
                    // Lands intact, keeping its id so the render layer tracks the same brick falling.
                    working = working.put(brick.copy(cell = Cell(from.x, landingY)))
                }
            }

            grid = working

            // --- 3. Re-evaluate. Fold the shatter shoves into the load map, then find new failures. ---
            val footing = Support.footing(grid)
            val footedSet = footing.cells
            val loads = Load.loads(grid, footing)
            val effectiveLoads = HashMap(loads)
            for ((cell, extra) in shoves) {
                if (grid.occupied(cell)) {
                    effectiveLoads[cell] = (effectiveLoads[cell] ?: 0.0) + extra
                }
            }

            val allBuckled = Load.buckled(grid, effectiveLoads)
            // "The lowest buckling brick is where the collapse begins" (PHYSICS.md §2) — applied
            // CONSISTENTLY per ring, exactly as PhysicsEngine.place seeds it: consume only the lowest
            // buckler this ring; any higher bucklers are re-derived against the settled grid next ring
            // and consumed there. This makes the ring count reflect the true depth of propagation
            // (each buckle is its own ring), which is the factor `magnitude = fell × rings × variety`
            // exists to capture. Consuming every buckler at once would understate rings — and so
            // understate the THRUUMMMM — the exact value the engine exists to compute.
            val nextBuckled: Set<Cell> =
                if (allBuckled.isEmpty()) emptySet() else setOf(allBuckled.minByOrNull { it.y }!!)
            // Everything the lowest buckler was holding, plus anything else that lost footing, falls.
            val unsupportedByBuckle = Support.unsupportedIfRemoved(grid, nextBuckled, footedSet)
            val nextUnfooted = Support.unfooted(grid) + unsupportedByBuckle

            bucklers = nextBuckled.filter { grid.occupied(it) }.toSet()
            fallers = (nextUnfooted - bucklers).filter { grid.occupied(it) }.toSet()
        }

        // Fail loud, never silently report an unstable grid as final. The cascade is provably finite
        // (every ring either consumes a buckler — strictly removing a brick — or relocates fallers to
        // strictly lower cells), so a guard trip means a real invariant break, not an expected branch.
        // PhysicsEngine.place advertises finalGrid as stable; corrupting that contract silently is the
        // worst failure mode for the engine, so we assert rather than return a contradictory world.
        check(fallers.isEmpty() && bucklers.isEmpty()) {
            "Cascade exceeded maxRings ($maxRings) without stabilising — fallers=$fallers, " +
                "bucklers=$bucklers. The grid is NOT stable; the termination proof has been violated."
        }

        return CollapseResult(
            fell = fellById.values.toList(),
            rings = rings,
            materials = allMaterials,
            finalGrid = grid,
        )
    }

    /**
     * The y a brick falling down column [x] from [fromY] lands at: one above the highest occupied
     * cell strictly below [fromY], or the ground (y = 0) if the column is empty below it.
     *
     * The falling brick's own cell has already been removed from [grid] (the whole doomed set is
     * removed before any landing is computed), so this scans a clean surface.
     */
    private fun landingRow(grid: Grid, x: Int, fromY: Int): Int {
        for (y in (fromY - 1) downTo 0) {
            if (grid.occupied(Cell(x, y))) return y + 1
        }
        return 0
    }
}
