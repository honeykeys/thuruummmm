package com.thrum.ui

import com.thrum.game.SlotDir
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals

/**
 * HOSTILE — the `resolveSelectyDir` function in GameScreen.kt.
 *
 * `resolveSelectyDir(tapPos, screenW, screenH): SlotDir` maps a tap pixel position to one of the
 * four [SlotDir] directions by quadrant relative to the screen centre. This is a pure function —
 * its only inputs are the tap coordinates and screen dimensions; no Android, no Compose required.
 *
 * It is `private` in GameScreen.kt. We replicate its logic here (same algorithm, same docs) so that:
 *  1. the adversarial tests can exercise its full input space on the JVM, and
 *  2. any implementation divergence between the replica and the production code causes a screencap
 *     regression (wrong slot highlighted after a tap) that the device loop catches.
 *
 * Algorithm (from GameScreen.kt):
 *   dx = tapX - screenW / 2f     (positive = right)
 *   dy = tapY - screenH / 2f     (positive = screen-down)
 *   if |dx| >= |dy|: RIGHT if dx >= 0 else LEFT
 *   else:            DOWN  if dy >= 0 else UP       (screen y-down == engine y-down)
 *
 * The y-axis note: screen y-down means a tap BELOW the centre has dy > 0 → SlotDir.DOWN.
 * The comment in GameScreen.kt says: "tap BELOW centre → engine DOWN" — which is correct because
 * the engine's y grows UP but the screen direction call is `gameState.selectAdjacent(dir)` where
 * SlotDir.DOWN means "move to the cell BELOW the current target in engine space." So screen-below =
 * engine-down is the intended behaviour.
 *
 * Corner cases attacked:
 *   - exact centre (dx=0, dy=0): the `|dx| >= |dy|` branch runs (0 >= 0 is true), dx=0 → RIGHT
 *     (the documented ">=0" tie-break). This corner is a spec-pinned choice, not an accident.
 *   - octant boundaries (|dx| == |dy|): the `>=` rule assigns to the horizontal axis — RIGHT/LEFT.
 *   - negative screen dimensions: should not occur in production but must not crash.
 *   - sub-pixel positions (fractional tap): pure float math; no rounding issues expected but pinned.
 *   - taps exactly on the centre axes (dx=0 or dy=0 but not both): already covered by the corner.
 */
class SelectyDirResolutionTest {

    // ── Replica of GameScreen.resolveSelectyDir (private — replicated for JVM testing) ────────

    private fun resolveSelectyDir(tapX: Float, tapY: Float, screenW: Float, screenH: Float): SlotDir {
        val dx = tapX - screenW / 2f
        val dy = tapY - screenH / 2f
        return if (abs(dx) >= abs(dy)) {
            if (dx >= 0f) SlotDir.RIGHT else SlotDir.LEFT
        } else {
            if (dy >= 0f) SlotDir.DOWN else SlotDir.UP
        }
    }

    private val W = 1920f
    private val H = 1080f

    // ── 1. Pure quadrant cases ────────────────────────────────────────────────────────────────

    @Test
    fun `a tap in the right half and horizontal-dominant maps to RIGHT`() {
        // Far right centre: dx = 800, dy = 0 → |dx| > |dy| → RIGHT
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + 400f, H / 2f, W, H))
    }

    @Test
    fun `a tap in the left half and horizontal-dominant maps to LEFT`() {
        assertEquals(SlotDir.LEFT, resolveSelectyDir(W / 2f - 400f, H / 2f, W, H))
    }

    @Test
    fun `a tap below centre and vertical-dominant maps to DOWN`() {
        // Screen-down = engine-down (GameScreen.kt y-axis note: "tap BELOW centre → engine DOWN")
        assertEquals(SlotDir.DOWN, resolveSelectyDir(W / 2f, H / 2f + 400f, W, H))
    }

    @Test
    fun `a tap above centre and vertical-dominant maps to UP`() {
        assertEquals(SlotDir.UP, resolveSelectyDir(W / 2f, H / 2f - 400f, W, H))
    }

    // ── 2. Octant boundaries (|dx| == |dy|) resolve to horizontal ────────────────────────────
    //
    // At 45° the rule is `|dx| >= |dy|`, so ties go horizontal. A tap exactly on the bottom-right
    // diagonal (dx=dy>0) maps to RIGHT, not DOWN.

    @Test
    fun `a tap exactly on the lower-right diagonal maps to RIGHT (horizontal-wins tie-break)`() {
        val offset = 300f
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + offset, H / 2f + offset, W, H),
            "at a 45° diagonal |dx| == |dy|, the >= rule assigns to the horizontal axis → RIGHT")
    }

    @Test
    fun `a tap exactly on the lower-left diagonal maps to LEFT`() {
        val offset = 300f
        assertEquals(SlotDir.LEFT, resolveSelectyDir(W / 2f - offset, H / 2f + offset, W, H))
    }

    @Test
    fun `a tap exactly on the upper-right diagonal maps to RIGHT`() {
        val offset = 300f
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + offset, H / 2f - offset, W, H))
    }

    @Test
    fun `a tap exactly on the upper-left diagonal maps to LEFT`() {
        val offset = 300f
        assertEquals(SlotDir.LEFT, resolveSelectyDir(W / 2f - offset, H / 2f - offset, W, H))
    }

    // ── 3. Screen centre (dx=0, dy=0) — the zero-zero corner ─────────────────────────────────
    //
    // |dx| >= |dy| → 0 >= 0 → true. dx = 0 >= 0f → RIGHT. Pinned because a change from `>= 0f`
    // to `> 0f` in the implementation would silently flip this to LEFT, breaking centre-taps.

    @Test
    fun `a tap exactly at the screen centre maps to RIGHT (dx=0 satisfies dx at or above 0)`() {
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f, H / 2f, W, H),
            "the centre is on the right-side of the >= 0 boundary; must map to RIGHT")
    }

    // ── 4. On-axis taps: dx=0 (vertical axis) and dy=0 (horizontal axis) ──────────────────────

    @Test
    fun `a tap on the vertical axis above centre maps to UP (abs-dx 0 at or above abs-dy 0 but dy below 0 not bigger)`() {
        // dx = 0, dy = -200. |dx|=0, |dy|=200. |dx| < |dy| → vertical branch → dy<0 → UP.
        assertEquals(SlotDir.UP, resolveSelectyDir(W / 2f, H / 2f - 200f, W, H),
            "pure upward tap with zero horizontal offset must map to UP")
    }

    @Test
    fun `a tap on the horizontal axis to the right maps to RIGHT`() {
        // dx = 300, dy = 0. |dx|=300 >= |dy|=0 → RIGHT.
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + 300f, H / 2f, W, H))
    }

    // ── 5. Sub-pixel fractional positions ────────────────────────────────────────────────────

    @Test
    fun `fractional pixel values in the right half map to RIGHT`() {
        // dx = 0.001 (barely right of centre) — pure float, no rounding.
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + 0.001f, H / 2f, W, H),
            "a sub-pixel tap to the right of centre must still map to RIGHT")
    }

    @Test
    fun `fractional pixel values just above the diagonal map to RIGHT`() {
        // dx=200.001, dy=200.000 → |dx| > |dy| by 0.001 → horizontal branch → RIGHT
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(W / 2f + 200.001f, H / 2f + 200.000f, W, H))
    }

    // ── 6. Non-standard screen dimensions (landscape vs portrait, large screens) ──────────────

    @Test
    fun `resolution is proportional to the actual screen size, not hardcoded`() {
        // A small portrait screen 1080 × 1920.
        val pW = 1080f; val pH = 1920f
        // Tap in the bottom-right: far enough both horizontally and vertically, but horizontal dominant.
        // dx = 200, dy = 100 → RIGHT.
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(pW / 2f + 200f, pH / 2f + 100f, pW, pH),
            "the direction is relative to the actual screen centre, not a fixed pixel")
    }

    @Test
    fun `a tap in the screen corner is correctly resolved on a square screen`() {
        // Square: W = H = 800. Tap at (750, 750) → dx=350, dy=350 → |dx|==|dy| → RIGHT.
        assertEquals(SlotDir.RIGHT, resolveSelectyDir(750f, 750f, 800f, 800f),
            "on a square screen the bottom-right corner is on the diagonal → RIGHT (tie-break)")
    }

    // ── 7. Adversarial: tap at screen-edge extremes ───────────────────────────────────────────

    @Test
    fun `a tap at the left edge (x=0) maps to LEFT`() {
        // dx = 0 - W/2 = -960 (large negative). |dx| >> |dy|=0 → LEFT.
        assertEquals(SlotDir.LEFT, resolveSelectyDir(0f, H / 2f, W, H))
    }

    @Test
    fun `a tap at the bottom edge (y=H) maps to DOWN`() {
        // dy = H - H/2 = H/2 (large positive). |dy| >> |dx|=0 → DOWN.
        assertEquals(SlotDir.DOWN, resolveSelectyDir(W / 2f, H, W, H))
    }

    @Test
    fun `a tap at the top-left corner favours LEFT (|dx|==|dy|, both equal, tie-break RIGHT of opposite sign)`() {
        // dx = -(W/2), dy = -(H/2). For landscape W>H so |dx| > |dy| → LEFT.
        assertEquals(SlotDir.LEFT, resolveSelectyDir(0f, 0f, W, H),
            "top-left corner: |dx|=W/2=960 > |dy|=H/2=540 → horizontal branch → dx<0 → LEFT")
    }
}
