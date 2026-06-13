package com.thuruummm.physics

/**
 * The world: an immutable snapshot of all placed bricks.
 *
 * The ground (y = -1 / "bedrock") is implicit — it has infinite strength and supports anything
 * at y = 0 unconditionally. The grid has no enforced width or height; the game layer constrains
 * the placement surface. The engine operates on whatever cells are occupied.
 *
 * Operations return new [Grid] copies; no mutation after construction. This makes cascade rings
 * easy to reason about: each ring produces a new world state.
 */
data class Grid(
    val bricks: Map<Cell, Brick> = emptyMap(),
    private val nextId: Int = 0,
) {
    /**
     * True if [cell] is on the bedrock row (y == 0). y == 0 sits directly on bedrock — the implicit
     * infinitely-strong floor — so any occupied y == 0 cell is footed unconditionally. This is the
     * bedrock contract the whole footing flood rests on; it checks y only, nothing below.
     */
    fun isGroundLevel(cell: Cell): Boolean = cell.y == 0

    /** The brick at [cell], or null. */
    fun at(cell: Cell): Brick? = bricks[cell]

    /** True if [cell] is occupied. */
    fun occupied(cell: Cell): Boolean = cell in bricks

    /** All occupied cells. */
    val cells: Set<Cell> get() = bricks.keys

    /**
     * Place a brick of [material] at [cell]. Does not validate support — that is [Support]'s job.
     * Mints a fresh monotonic id. Returns the new grid and the brick that was placed.
     */
    fun place(material: Material, cell: Cell): Pair<Grid, Brick> {
        val brick = Brick(id = nextId, material = material, cell = cell)
        return Grid(bricks + (cell to brick), nextId + 1) to brick
    }

    /**
     * Re-insert an existing [brick] at its [Brick.cell], preserving its id. Used by [Cascade] to
     * settle a brick that fell intact — the render layer tracks the same brick through the fall.
     * Does NOT advance [nextId]; this is a relocation of an already-minted brick, not a new mint.
     */
    fun put(brick: Brick): Grid = Grid(bricks + (brick.cell to brick), nextId)

    /**
     * Remove the bricks at [cells] from the grid. Used by [Cascade] to drop failed bricks.
     * Returns the new grid with those cells vacated.
     */
    fun remove(cells: Collection<Cell>): Grid =
        Grid(bricks - cells.toSet(), nextId)
}
