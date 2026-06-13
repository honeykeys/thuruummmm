package com.thuruummm.physics

/**
 * Q1: "Do I have a footing?" (PHYSICS.md §"Do I have a footing?")
 *
 * A brick is **footed** if a load-path exists from it down to bedrock. Three ways to be footed:
 *   a) it sits on the ground (y == 0), OR
 *   b) the brick directly below it is footed (standard stacking), OR
 *   c) it is reached by a cantilever — a footed brick at the same row spans sideways to it, within
 *      that spanning brick's [Material.cantilever] reach. This is a diving-board overhang: the beam
 *      reaches out OVER EMPTY SPACE (no contiguity required), so a footed wood beam at x=0 with reach
 *      2 foots a brick at x=2 even with x=1 empty. (PHYSICS.md: "leaning out over the edge.")
 *
 * Footing is computed as a flood from bedrock outward: seed with every ground-level brick, then
 * repeatedly mark as footed any brick reachable by rule (b) or (c) from an already-footed brick,
 * until no more can be added. Anything left unfooted must fall.
 *
 * Model note (reconciles the two readings in the docs): the CANTILEVER REACH belongs to the
 * spanning/anchor brick — the beam that leans out determines how far footing extends. A pebble
 * (cantilever 0) cannot itself span, but it can SIT on a wood beam's reach. This matches the
 * executable contract in PhysicsEngineTest (`anchor`, cantilever 2, foots a pebble two cells away).
 *
 * Purely functional: no side effects, no mutation, no Android imports.
 */
object Support {

    /** Cells occupied by an unfooted brick — the Q1 failures. */
    fun unfooted(grid: Grid): Set<Cell> = grid.cells - footedSet(grid)

    /**
     * Which currently-footed bricks would lose their footing if the [doomed] bricks were removed —
     * everything resting (directly or transitively, vertically or via a now-broken span) on a brick
     * that is about to fail. Computed by removing [doomed], recomputing the footed set, and returning
     * the cells that *were* footed (per [previouslyFooted]) but no longer are, excluding [doomed].
     *
     * Used to expand a buckle into the full set of bricks the buckle takes down with it.
     */
    fun unsupportedIfRemoved(grid: Grid, doomed: Set<Cell>, previouslyFooted: Set<Cell>): Set<Cell> {
        if (doomed.isEmpty()) return emptySet()
        val newFooted = footedSet(grid.remove(doomed))
        return (previouslyFooted - doomed) - newFooted
    }

    /**
     * The full result of the bedrock-out footing flood: the footed [cells], and — for every brick
     * footed ONLY by a cantilever overhang (rule c), with no footed cell directly below it — the beam
     * that footed it in the flood ([cantileverParent]). The parent is the structurally-upstream beam
     * (the one the flood reached first, nearest bedrock), so load routed onto it flows TOWARD bedrock,
     * never back out along the overhang. This is the single source of truth [Load] follows so support
     * and load can never disagree about who holds an arm.
     */
    data class Footing(val cells: Set<Cell>, val cantileverParent: Map<Cell, Cell>)

    /**
     * The complete set of footed cells in [grid]: ground-level bricks, everything transitively
     * stacked on them, and everything reached by a cantilever beam's sideways span.
     */
    fun footedSet(grid: Grid): Set<Cell> = footing(grid).cells

    /**
     * The footing flood (see [Footing]). A breadth-first flood from bedrock; the FIRST beam to
     * cantilever-foot a brick (rule c) is recorded as that brick's [Footing.cantileverParent] — and
     * because the flood is breadth-first from bedrock, that first beam is the one nearest a vertical
     * support, which is exactly the direction load must travel. After the flood, parents for bricks
     * that turned out to be vertically footed are reconciled away (against the final footed set), so
     * [Footing.cantileverParent] is keyed by exactly the GENUINE arms — independent of BFS order.
     */
    fun footing(grid: Grid): Footing {
        val footed = HashSet<Cell>()
        val cantileverParent = HashMap<Cell, Cell>()
        val queue = ArrayDeque<Cell>()

        // Seed: every occupied ground-level cell is footed.
        for (cell in grid.cells) {
            if (grid.isGroundLevel(cell)) {
                footed += cell
                queue += cell
            }
        }

        // Flood. A newly-footed brick can foot:
        //   - the brick directly above it (vertical stacking), and
        //   - any occupied cell within its own cantilever reach along the row — reaching out over
        //     empty space (a diving-board overhang); no contiguity required.
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val brick = grid.at(current) ?: continue

            // (b) Vertical: the brick resting directly on this one becomes footed. It has a real cell
            // below it, so it is never an arm — no parent (and reconciliation would strip one anyway).
            tryFoot(current.above(), grid, footed, queue, cantileverParent, parent = null)

            // (c) Cantilever overhang: this footed beam foots any occupied cell within its reach
            // along the row, reaching OUT OVER EMPTY SPACE (a diving board). No contiguity required —
            // a gap between the beam and the leaning brick is the whole point of an overhang.
            val reach = brick.material.cantilever
            if (reach > 0) {
                for (sign in intArrayOf(-1, 1)) {
                    for (dx in 1..reach) {
                        val target = Cell(current.x + sign * dx, current.y)
                        // Tentatively record `current` as the beam that footed `target`. Whether
                        // `target` is GENUINELY an arm (vs a column also standing on its own footing)
                        // is decided after the flood, against the FINAL footed set — see below — so
                        // this recording is order-independent and never races the flood.
                        tryFoot(target, grid, footed, queue, cantileverParent, parent = current)
                    }
                }
            }
        }

        // Reconcile: a cantilever parent is meaningful ONLY for a true arm — a footed brick with no
        // footed cell directly below it and not on bedrock. Any brick that also stands on a column is
        // vertically footed; its overhang link is incidental and must not redirect its load sideways.
        // Deciding this against the FINAL footed set (not mid-flood) removes all BFS-ordering fragility:
        // the result is identical regardless of the order beams happened to reach a cell.
        cantileverParent.keys.retainAll { arm ->
            arm.y != 0 && Cell(arm.x, arm.y - 1) !in footed
        }
        return Footing(footed, cantileverParent)
    }

    /**
     * Mark [cell] footed (and enqueue for further propagation) if it is occupied and not yet footed.
     * If [parent] is non-null and this is the FIRST time [cell] is footed, record [parent] as its
     * cantilever support beam.
     */
    private fun tryFoot(
        cell: Cell,
        grid: Grid,
        footed: MutableSet<Cell>,
        queue: ArrayDeque<Cell>,
        cantileverParent: MutableMap<Cell, Cell>,
        parent: Cell?,
    ) {
        if (grid.occupied(cell) && footed.add(cell)) {
            if (parent != null) cantileverParent[cell] = parent
            queue += cell
        }
    }
}
