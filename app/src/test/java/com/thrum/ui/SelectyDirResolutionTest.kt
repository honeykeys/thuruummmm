package com.thrum.ui

import com.thuruummm.physics.Cell
import org.junit.Test
import kotlin.math.floor
import kotlin.test.assertEquals

/**
 * HOSTILE — the `pressToCell` mapping in GameScreen.kt (the selecty LONG-PRESS geometry).
 *
 * selecty is now ABSOLUTE: a long press picks the grid cell under the finger (Karl: "select the grid
 * slot next to any current block"), so `pressToCell` must be the EXACT inverse of [FieldCanvas]'s
 * `cellToScreen` — press the pixels a cell occupies and you must get that cell back, or the slot you
 * select is not the one you touched. Both are pure functions of (position, screen size, cellPx, pan);
 * no Android, no Compose — so we replicate both here and pin the round-trip on the JVM. A divergence
 * between this replica and production shows up as "long press selected the wrong slot" on device.
 *
 * The two halves (from GameScreen.kt / FieldCanvas.kt):
 *   cellToScreen(cell).x = (screenW/2 - panX*cellPx) + cell.x*cellPx     // cell LEFT edge
 *   cellToScreen(cell).y = screenH - (cell.y + 1 - panY)*cellPx          // cell TOP edge (y-flip)
 *   pressToCell(p).x      = floor((p.x - screenW/2)/cellPx + panX)
 *   pressToCell(p).y      = floor((screenH - p.y)/cellPx + panY)
 */
class SelectyDirResolutionTest {

    // ── Replicas of the production geometry (both private; replicated for JVM testing) ────────────

    private fun pressToCell(
        px: Float, py: Float, screenW: Float, screenH: Float, cellPx: Float, panX: Float, panY: Float,
    ): Cell {
        val cellX = floor((px - screenW / 2f) / cellPx + panX).toInt()
        val cellY = floor((screenH - py) / cellPx + panY).toInt()
        return Cell(cellX, cellY)
    }

    /** The cell's TOP-LEFT screen pixel (matches FieldCanvas.cellToScreen). */
    private fun cellTopLeft(
        cell: Cell, screenW: Float, screenH: Float, cellPx: Float, panX: Float, panY: Float,
    ): Pair<Float, Float> {
        val x = (screenW / 2f - panX * cellPx) + cell.x * cellPx
        val y = screenH - (cell.y + 1 - panY) * cellPx
        return x to y
    }

    private val W = 1920f
    private val H = 1080f
    private val CELL = 252f   // ~96dp at the Pixel 7 density; the absolute value does not matter here

    // ── 1. Round-trip: pressing a cell's CENTRE returns that exact cell ───────────────────────────

    @Test
    fun `pressing the centre of each cell maps back to that cell (pan zero)`() {
        for (cell in listOf(Cell(0, 0), Cell(1, 0), Cell(-1, 0), Cell(3, 2), Cell(-2, 4), Cell(0, 5))) {
            val (tlx, tly) = cellTopLeft(cell, W, H, CELL, 0f, 0f)
            val centre = pressToCell(tlx + CELL / 2f, tly + CELL / 2f, W, H, CELL, 0f, 0f)
            assertEquals(cell, centre, "a press at the centre of $cell must resolve back to $cell")
        }
    }

    @Test
    fun `round-trip holds under a non-zero pan`() {
        val panX = 2.5f; val panY = 1.25f
        for (cell in listOf(Cell(0, 0), Cell(4, 1), Cell(-3, 3))) {
            val (tlx, tly) = cellTopLeft(cell, W, H, CELL, panX, panY)
            val got = pressToCell(tlx + CELL / 2f, tly + CELL / 2f, W, H, CELL, panX, panY)
            assertEquals(cell, got, "with pan ($panX,$panY) a press at the centre of $cell must resolve to $cell")
        }
    }

    // ── 2. The ground row: a press in the bottom band of the screen lands on y == 0 ───────────────

    @Test
    fun `a press in the bottom cell band lands on the ground row (y == 0)`() {
        // The ground row (cell.y == 0) occupies screen-y in [H - CELL, H]. A press just above the bottom
        // edge must be y == 0 — this is how the FIRST brick is placed on an empty field.
        val nearBottom = pressToCell(W / 2f, H - 1f, W, H, CELL, 0f, 0f)
        assertEquals(0, nearBottom.y, "a press near the screen bottom selects the ground row")
    }

    @Test
    fun `the row directly above the ground band is y == 1`() {
        // One cell up from the bottom edge.
        val secondRow = pressToCell(W / 2f, H - CELL - 1f, W, H, CELL, 0f, 0f)
        assertEquals(1, secondRow.y, "a press one cell above the bottom selects y == 1")
    }

    // ── 3. Horizontal placement: presses left / right of centre pick the correct column ──────────

    @Test
    fun `a press right of centre picks a positive column, left of centre a negative one`() {
        val right = pressToCell(W / 2f + CELL * 1.5f, H - CELL / 2f, W, H, CELL, 0f, 0f)
        val left  = pressToCell(W / 2f - CELL * 1.5f, H - CELL / 2f, W, H, CELL, 0f, 0f)
        assertEquals(1, right.x, "1.5 cells right of centre is column 1")
        assertEquals(-2, left.x, "1.5 cells left of centre is column -2 (floor, not round)")
    }

    // ── 4. Boundary: a press exactly on a cell's left edge belongs to THAT cell (floor) ──────────

    @Test
    fun `a press exactly on a cell's left edge belongs to that cell (floor, not the neighbour)`() {
        val cell = Cell(2, 0)
        val (tlx, _) = cellTopLeft(cell, W, H, CELL, 0f, 0f)
        // Press exactly on the left edge x, mid-height of the ground row.
        val got = pressToCell(tlx, H - CELL / 2f, W, H, CELL, 0f, 0f)
        assertEquals(cell, got, "a press on the left edge resolves to the cell that edge opens, not its left neighbour")
    }

    // ── 5. Pan shifts the whole mapping by whole/sub cells ───────────────────────────────────────

    @Test
    fun `panning the view right shifts which column the screen centre selects`() {
        // With no pan, the screen centre is the boundary between column -1 and 0 (floor → column 0 at
        // centre+epsilon). Panning the WORLD by +3 cells means the centre now points at world column 3.
        val centred = pressToCell(W / 2f + 1f, H - CELL / 2f, W, H, CELL, 3f, 0f)
        assertEquals(3, centred.x, "a +3 cell pan moves the centre selection to world column 3")
    }
}
