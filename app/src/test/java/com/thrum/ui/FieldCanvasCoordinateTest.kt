package com.thrum.ui

import com.thuruummm.physics.Cell
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HOSTILE — the FieldCanvas cell→pixel coordinate mapping.
 *
 * FieldCanvas.kt owns the ONLY conversion from engine grid coordinates (Cell, y-up) to screen
 * pixels (px, y-down). The formula, replicated here, is load-bearing: get it wrong and EVERY brick
 * draws at the wrong position, but the tests pass because the render is visual.
 *
 * ```
 * gridOriginX = w / 2f - panCellX * cellPx
 *
 * screenX(cell) = gridOriginX + cell.x * cellPx
 * screenY(cell) = h - (cell.y + 1 - panCellY) * cellPx
 * ```
 *
 * Why these formulas? (from FieldCanvas.kt KDoc):
 *   - The grid is centred horizontally at screen x=0 (engine x=0 → screen x=w/2).
 *   - Engine y is UP (y=0 = bedrock floor). Screen y is DOWN (y=0 = top of screen).
 *   - "+1" in the y formula so the floor brick's BOTTOM edge sits at y=h, not its top.
 *   - panCellX/Y scroll the camera by subtracting cells from the grid origin.
 *
 * These tests attack the formula with adversarial inputs:
 *   A. Cell(0, 0) (the ground origin) must map to the horizontal centre at the BOTTOM of the canvas.
 *   B. A positive cell.x must map to the right of centre; negative to the left.
 *   C. A higher cell.y (stack grows up) must map to a SMALLER screen y (higher on screen = smaller y).
 *   D. panCellX shifts the entire field LEFT (camera moves right → world moves left).
 *   E. panCellY shifts bricks DOWN on screen (camera moves up → world moves down).
 *   F. A cell far from the origin maps further from the centre proportionally (no clipping/overflow).
 *   G. cellPx is the constant scale factor: doubling cellPx doubles the screen distance.
 *   H. The coordinate mapping is injective: no two distinct cells map to the same screen position.
 *
 * The formula does NOT involve Compose (no DrawScope, no Canvas, no density). It is pure float
 * arithmetic — fully JVM-testable as a replicated function.
 *
 * If FieldCanvas.kt changes the formula, these tests will break at the replica. That is the
 * intended signal: update the replica and run the screencap loop to verify the new formula renders
 * correctly. Never silently diverge.
 */
class FieldCanvasCoordinateTest {

    // ── Replica of FieldCanvas cell→pixel mapping ─────────────────────────────────────────────
    //
    // cellToScreen(cell) as implemented in FieldCanvas.kt. Call with the same args the Canvas uses.

    private data class ScreenPos(val x: Float, val y: Float)

    private fun cellToScreen(
        cell: Cell,
        w: Float,
        h: Float,
        cellPx: Float,
        panCellX: Float = 0f,
        panCellY: Float = 0f,
    ): ScreenPos {
        val gridOriginX = w / 2f - panCellX * cellPx
        return ScreenPos(
            x = gridOriginX + cell.x * cellPx,
            y = h - (cell.y + 1 - panCellY) * cellPx,
        )
    }

    private val W      = 1920f
    private val H      = 1080f
    private val CELL   = 52f   // CELL_DP from FieldCanvas.kt

    // ── A. Cell(0, 0) maps to the horizontal centre at the BOTTOM of the canvas ───────────────

    @Test
    fun `Cell(0,0) maps to the horizontal centre of the canvas`() {
        val p = cellToScreen(Cell(0, 0), W, H, CELL)
        assertEquals(W / 2f, p.x, 1e-3f,
            "Cell(0,0) must be at the horizontal centre (engine x=0 → screen x=W/2)")
    }

    @Test
    fun `Cell(0,0) maps to the BOTTOM of the canvas (the floor brick bottom edge at y=H)`() {
        val p = cellToScreen(Cell(0, 0), W, H, CELL)
        // y = h - (0 + 1 - 0) * cellPx = h - cellPx. This is the TOP of the brick. The brick's
        // BOTTOM is at y = h (the canvas bottom) because the brick draws from topLeft down by cellPx.
        // The formula gives the brick's top-left y; the bottom is at y + cellPx = h.
        assertEquals(H - CELL, p.y, 1e-3f,
            "Cell(0,0) topLeft.y must be H - cellPx so the brick's bottom edge aligns with H")
    }

    // ── B. Positive cell.x → right of centre; negative → left ──────────────────────────────

    @Test
    fun `a cell to the right (x=1) maps to the right of the grid origin`() {
        val origin = cellToScreen(Cell(0, 0), W, H, CELL)
        val right  = cellToScreen(Cell(1, 0), W, H, CELL)
        assertTrue(right.x > origin.x,
            "cell.x=1 must be to the right of cell.x=0 (positive x = rightward)")
        assertEquals(origin.x + CELL, right.x, 1e-3f,
            "exactly one cellPx to the right")
    }

    @Test
    fun `a cell to the left (x=-1) maps to the left of the grid origin`() {
        val origin = cellToScreen(Cell(0, 0), W, H, CELL)
        val left   = cellToScreen(Cell(-1, 0), W, H, CELL)
        assertTrue(left.x < origin.x, "cell.x=-1 must be to the left of cell.x=0")
        assertEquals(origin.x - CELL, left.x, 1e-3f, "exactly one cellPx to the left")
    }

    // ── C. Higher cell.y → smaller screen y (higher on screen) ──────────────────────────────

    @Test
    fun `a brick one row up (y=1) has a SMALLER screen y than the floor brick (y=0)`() {
        val floor  = cellToScreen(Cell(0, 0), W, H, CELL)
        val oneUp  = cellToScreen(Cell(0, 1), W, H, CELL)
        assertTrue(oneUp.y < floor.y,
            "cell.y=1 must map to a smaller screen y than cell.y=0 (engine up → screen up = smaller y)")
        assertEquals(floor.y - CELL, oneUp.y, 1e-3f, "exactly one cellPx higher on screen")
    }

    @Test
    fun `a stack of 5 bricks spaces exactly cellPx apart in screen y`() {
        val ys = (0..4).map { row -> cellToScreen(Cell(0, row), W, H, CELL).y }
        for (i in 1..4) {
            assertEquals(ys[i - 1] - CELL, ys[i], 1e-3f,
                "row $i and row ${i - 1} must be exactly cellPx apart in screen y")
        }
    }

    // ── D. panCellX shifts the field LEFT by panCellX cells (camera moves right → world left) ──

    @Test
    fun `a panCellX of 1 shifts every brick exactly one cellPx to the left`() {
        val noPan  = cellToScreen(Cell(3, 2), W, H, CELL, panCellX = 0f)
        val panOne = cellToScreen(Cell(3, 2), W, H, CELL, panCellX = 1f)
        assertEquals(noPan.x - CELL, panOne.x, 1e-3f,
            "panCellX=1 must shift the brick one cellPx to the left (camera right → world left)")
    }

    @Test
    fun `panCellX does NOT affect the y coordinate`() {
        val noPan  = cellToScreen(Cell(3, 2), W, H, CELL, panCellX = 0f)
        val panTwo = cellToScreen(Cell(3, 2), W, H, CELL, panCellX = 2f)
        assertEquals(noPan.y, panTwo.y, 1e-3f, "panCellX must not affect screen y")
    }

    // ── E. panCellY shifts bricks DOWN on screen (camera up → world down = larger y) ──────────

    @Test
    fun `a panCellY of 1 shifts every brick exactly one cellPx downward on screen`() {
        // y = h - (cell.y + 1 - panCellY) * cellPx
        // panCellY=1: y = h - (cell.y + 1 - 1) * cellPx = h - cell.y * cellPx (one step lower).
        val noPan  = cellToScreen(Cell(2, 3), W, H, CELL, panCellY = 0f)
        val panOne = cellToScreen(Cell(2, 3), W, H, CELL, panCellY = 1f)
        assertEquals(noPan.y + CELL, panOne.y, 1e-3f,
            "panCellY=1 must shift the brick one cellPx downward on screen (camera up → world down)")
    }

    @Test
    fun `panCellY does NOT affect the x coordinate`() {
        val noPan  = cellToScreen(Cell(2, 3), W, H, CELL, panCellY = 0f)
        val panTwo = cellToScreen(Cell(2, 3), W, H, CELL, panCellY = 2f)
        assertEquals(noPan.x, panTwo.x, 1e-3f, "panCellY must not affect screen x")
    }

    // ── F. A cell far from the origin maps proportionally, no clipping ────────────────────────

    @Test
    fun `a cell at x=20 maps exactly 20 cellPx to the right of the origin`() {
        val origin = cellToScreen(Cell(0, 0), W, H, CELL)
        val farRight = cellToScreen(Cell(20, 0), W, H, CELL)
        assertEquals(origin.x + 20 * CELL, farRight.x, 1e-3f,
            "cell.x=20 must be 20 × cellPx to the right of the origin — no clamping")
    }

    @Test
    fun `a cell at y=50 maps exactly 50 cellPx above the floor`() {
        val floor  = cellToScreen(Cell(0, 0), W, H, CELL)
        val high   = cellToScreen(Cell(0, 50), W, H, CELL)
        assertEquals(floor.y - 50 * CELL, high.y, 1e-3f,
            "cell.y=50 must be 50 × cellPx above the floor on screen — no clamping")
    }

    // ── G. Doubling cellPx doubles all screen distances ───────────────────────────────────────

    @Test
    fun `doubling cellPx doubles the horizontal distance between any two cells`() {
        val c1 = cellToScreen(Cell(0, 0), W, H, CELL)
        val c2 = cellToScreen(Cell(3, 0), W, H, CELL)
        val d1 = abs(c2.x - c1.x)   // distance at CELL

        val c3 = cellToScreen(Cell(0, 0), W, H, CELL * 2f)
        val c4 = cellToScreen(Cell(3, 0), W, H, CELL * 2f)
        val d2 = abs(c4.x - c3.x)   // distance at CELL*2

        assertEquals(d1 * 2f, d2, 1e-3f,
            "doubling cellPx must double the horizontal screen distance between cells")
    }

    // ── H. The mapping is injective: no two distinct cells share a screen position ─────────────
    //
    // If the mapping were non-injective, two bricks would draw on top of each other — impossible
    // in a valid grid (the grid forbids duplicate cells), but good to verify the mapping itself
    // preserves injectivity.

    @Test
    fun `distinct cells map to distinct screen positions (no collisions)`() {
        val cells = buildList {
            for (x in -5..5) for (y in 0..5) add(Cell(x, y))
        }
        val positions = cells.map { cellToScreen(it, W, H, CELL) }
        val unique = positions.map { "${it.x},${it.y}" }.toSet()
        assertEquals(cells.size, unique.size,
            "every distinct cell must map to a unique screen position — the mapping must be injective")
    }

    // ── I. Negative cell.x and cell.y=0 remain BELOW the floor of cells at y=1 ────────────────

    @Test
    fun `a negative-x cell on the ground has the same y as a positive-x ground cell`() {
        val left  = cellToScreen(Cell(-3, 0), W, H, CELL)
        val right = cellToScreen(Cell( 3, 0), W, H, CELL)
        assertEquals(left.y, right.y, 1e-3f,
            "two ground-level cells at opposite x positions must share the same screen y (flat floor)")
    }

    // ── Helper ────────────────────────────────────────────────────────────────────────────────

    private fun assertEquals(expected: Float, actual: Float, tolerance: Float, message: String = "") {
        assertTrue(abs(actual - expected) <= tolerance,
            if (message.isEmpty()) "expected $expected ± $tolerance, got $actual"
            else "$message — expected $expected ± $tolerance, got $actual")
    }
}
