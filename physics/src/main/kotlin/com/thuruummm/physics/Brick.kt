package com.thuruummm.physics

/**
 * A placed brick: a [Material] at a [cell] in the grid, identified by [id].
 *
 * Bricks are immutable once placed; the grid creates new copies on any structural update. The
 * [id] is a monotonically increasing integer assigned by the engine — stable across cascade rings
 * so tests and the render layer can track individual bricks through a collapse.
 */
data class Brick(
    val id: Int,
    val material: Material,
    val cell: Cell,
)

/**
 * A position in the grid: column [x] (0 = left), row [y] (0 = ground level, increasing upward).
 *
 * The engine is screen-agnostic — "up" here is structural up (away from bedrock). The UI layer
 * flips y for screen coordinates.
 */
data class Cell(val x: Int, val y: Int) {
    fun above(): Cell = Cell(x, y + 1)
    fun below(): Cell = Cell(x, y - 1)
    fun left(): Cell  = Cell(x - 1, y)
    fun right(): Cell = Cell(x + 1, y)
    fun neighbours(): List<Cell> = listOf(above(), below(), left(), right())
}
