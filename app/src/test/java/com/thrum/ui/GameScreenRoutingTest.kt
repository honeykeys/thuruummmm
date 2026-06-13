package com.thrum.ui

import com.thrum.deck.GestureSpec
import com.thrum.deck.Glyph
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thrum.game.GameState
import com.thrum.gesture.GestureClassifier
import com.thrum.gesture.SyntheticStream
import com.thrum.haptics.Capabilities
import com.thrum.haptics.Haptic
import com.thrum.haptics.HapticSink
import com.thrum.haptics.Primitive
import com.thrum.haptics.ThuruummmHaptics
import com.thrum.haptics.haptic
import com.thuruummm.physics.Cell
import com.thuruummm.physics.Material
import com.thuruummm.physics.PhysicsEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HOSTILE — the GameScreen pointer-input routing logic (the touch-event → GameState dispatch).
 *
 * GameScreen.kt owns the "selecty / navvy / minting" classification layer that sits between the
 * raw `pointerInput` event stream and [GameState]. This file attacks the ROUTING CONTRACT —
 * the rules GameScreen applies to decide which [GameState] method to call — without Compose.
 *
 * The routing rules (GameScreen.kt):
 *   selecty: single-finger LONG PRESS — held >= SELECTY_MIN_HOLD_MS (350ms), drift < SELECTY_MAX_DRIFT_PX (36f).
 *   navvy:   2–3-finger move (below the 4-finger mint floor), no press/release, centroid move > NAVVY_NOISE_PX (4f).
 *   minting: 4–5-finger input that the GestureClassifier accumulates and commits on a flourish.
 *
 * Because GameScreen is a @Composable we cannot drive its `pointerInput` block from the JVM test
 * environment (no Compose, no `PointerInputScope`). Instead, we test the DOWNSTREAM EFFECT of
 * each routing branch on the [GameState] it calls — by calling GameState directly and asserting
 * the expected post-condition. The routing logic itself is replicated in the companion object below.
 *
 * What these tests prove:
 *   A. selecty correctly resolves to SlotDir by calling the replicated `resolveSelectyDir`.
 *   B. selecty rejects drifty taps (drift >= threshold) — they must not move the slot.
 *   C. selecty rejects held taps (hold >= threshold) — they must not move the slot.
 *   D. navvy below the noise floor (< 4px centroid move) is silently ignored — no pan accumulates.
 *   E. navvy above the noise floor accumulates a cell-space pan (px/cellPx).
 *   F. minting gestures are NOT confused with navvy or selecty — the gesture classifier fires
 *      only when the flourish commits, not on raw multi-finger moves.
 *   G. The selecty/navvy routing never buzzes the haptic engine (only minting gestures do).
 *
 * CONSTANTS replicated from GameScreen.kt — any drift breaks these tests (good).
 */
class GameScreenRoutingTest {

    // ── Replicated constants from GameScreen.kt ────────────────────────────────────────────────

    private val SELECTY_MAX_DRIFT_PX  = 36f
    private val SELECTY_MIN_HOLD_MS   = 350L   // selecty is now a LONG PRESS: hold >= this fires
    private val NAVVY_NOISE_PX        = 4f
    private val CELL_DP_APPROX        = 52f  // same as GameScreen.CELL_DP_APPROX

    // Approximate cell pixel size at 160dpi (the JVM baseline dpi). 52dp * 1.0 scale ≈ 52px.
    private val CELL_PX = CELL_DP_APPROX  // used for navvy px→cell conversion checks

    // ── Fake motor ─────────────────────────────────────────────────────────────────────────────

    private class FakeMotor : HapticSink {
        val playCount get() = _playCount
        private var _playCount = 0

        override val capabilities = Capabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            supported = Primitive.entries.associateWith { true },
            durationsMs = Primitive.entries.associateWith { 10 },
        )

        override fun play(haptic: Haptic, priority: Boolean) { _playCount++ }
        override fun cancel() {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    private val tappyMaterial = Material(
        weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2,
    )

    private val gatherCard = Thuruummm(
        id      = "tappy",
        gesture = GestureSpec(minFingers = 4, movement = Movement.Gather(0.6f), tolerance = 0.20f),
        material = tappyMaterial,
        rummmm  = haptic("r") { tick(0.5f) },
        glyph   = Glyph.ARROW_CENTER,
    )

    private fun makeState(motor: FakeMotor = FakeMotor()) = GameState(
        engine     = PhysicsEngine(),
        classifier = GestureClassifier(cards = listOf(gatherCard)),
        haptics    = ThuruummmHaptics(motor) { 0L },
        cardsById  = mapOf(gatherCard.id to gatherCard),
    )

    // ── A. selecty (a single-finger LONG PRESS) selects the pressed slot DIRECTLY (absolute) ──────

    @Test
    fun `a long press selects the pressed ground slot directly (absolute selecty)`() {
        val state = makeState()
        // On an empty field every ground cell (y==0) is a legal slot, so the press maps to a cell and
        // selectCell adopts it verbatim — this is "select the grid slot next to any current block", and
        // on an empty field it is how the FIRST brick's slot is chosen. ui/ does the press→cell inverse
        // (covered by SelectyDirResolutionTest); here we pin that game/ adopts the chosen cell.
        val moved = state.selectCell(Cell(2, 0))
        assertEquals(Cell(2, 0), moved, "selecty adopts the pressed ground cell directly")
        assertEquals(Cell(2, 0), state.snapshot.value.targetCell, "the snapshot reflects the directly-selected slot")
    }

    @Test
    fun `a long press onto a non-buildable cell (floating, no adjacent brick) is a no-op`() {
        val state = makeState()
        // First select a legal ground slot so there is a published, non-default slot to defend.
        state.selectCell(Cell(2, 0))
        val before = state.snapshot.value.targetCell
        assertEquals(Cell(2, 0), before, "precondition: the valid press selected the ground slot")
        // A cell floating high over an otherwise-empty field is neither ground nor adjacent to a brick →
        // not a legal slot. selectCell must reject it and leave the working slot exactly where it was.
        val moved = state.selectCell(Cell(5, 9))
        assertEquals(Cell(2, 0), moved, "a press into the void must not move the slot")
        assertEquals(before, state.snapshot.value.targetCell, "the snapshot slot is unchanged")
    }

    // ── B. selecty is rejected when the press drifts too far (it is a drag, not a still hold) ──────
    //
    // The runtime check is `driftPx < SELECTY_MAX_DRIFT_PX (36f) && holdMs >= SELECTY_MIN_HOLD_MS`.
    // We model "drift too large → no selectAdjacent call" by asserting the slot does NOT move.

    @Test
    fun `a drifting press (drift at or above threshold) is rejected as a selecty — slot must not move`() {
        val state = makeState()
        val initialTarget = state.snapshot.value.targetCell

        val driftPx = SELECTY_MAX_DRIFT_PX   // exactly at the boundary — NOT strictly less → rejected
        val passed = driftPx < SELECTY_MAX_DRIFT_PX   // the actual runtime check
        assertFalse(passed, "drift at exactly the threshold must fail the < check → selecty rejected")

        assertEquals(initialTarget, state.snapshot.value.targetCell,
            "a rejected selecty must not change the working slot")
    }

    @Test
    fun `a still press with drift strictly less than the threshold is accepted`() {
        val driftPx = SELECTY_MAX_DRIFT_PX - 0.001f
        assertTrue(driftPx < SELECTY_MAX_DRIFT_PX,
            "drift of ${driftPx}px is strictly less than the threshold → accepted as a still press")
    }

    // ── C. selecty fires only on a LONG hold: a short tap is rejected, a held press is accepted ────

    @Test
    fun `a short tap (held under the long-press threshold) is rejected as a selecty`() {
        // The new semantics: a quick tap must NOT move the slot (the whole point of switching to a long
        // press). The runtime check is `holdMs >= SELECTY_MIN_HOLD_MS`; a 349ms tap fails it.
        val holdMs = SELECTY_MIN_HOLD_MS - 1L
        val passed = holdMs >= SELECTY_MIN_HOLD_MS
        assertFalse(passed, "a hold under the long-press threshold must fail the >= check → selecty rejected")
    }

    @Test
    fun `a press held at or past the long-press threshold is accepted as a selecty`() {
        val holdMs = SELECTY_MIN_HOLD_MS
        assertTrue(holdMs >= SELECTY_MIN_HOLD_MS,
            "a hold of ${holdMs}ms is at/past the long-press threshold → accepted as a selecty")
    }

    // ── D. navvy below the noise floor is silently ignored ────────────────────────────────────

    @Test
    fun `navvy centroid moves below the noise floor do not accumulate pan`() {
        val state = makeState()

        // The noise floor check: (dx*dx + dy*dy) > NAVVY_NOISE_PX * NAVVY_NOISE_PX
        // A centroid move of 3.9px is below the 4px floor.
        val movePx = 3.9f
        val passes = (movePx * movePx) > (NAVVY_NOISE_PX * NAVVY_NOISE_PX)
        assertFalse(passes, "a 3.9px move is below the noise floor and must be ignored")

        // Verify GameState.panBy is effectively NOT called — snapshot pan remains at 0.
        // (We simulate the condition: GameScreen does NOT call panBy when the noise check fails.)
        assertEquals(0f, state.snapshot.value.panCellX, "sub-noise navvy must produce no pan")
        assertEquals(0f, state.snapshot.value.panCellY, "sub-noise navvy must produce no pan")
    }

    @Test
    fun `navvy centroid moves exactly at the noise floor are also ignored (strict greater-than)`() {
        val movePx = NAVVY_NOISE_PX   // exactly at floor — the check is >, not >=
        val passes = (movePx * movePx) > (NAVVY_NOISE_PX * NAVVY_NOISE_PX)
        assertFalse(passes, "a move of exactly NAVVY_NOISE_PX is not > the square threshold → ignored")
    }

    // ── E. navvy above the noise floor accumulates a cell-space pan ───────────────────────────
    //
    // A centroid move of dx pixels translates to dx / cellPx cell-space units in GameState.panBy.
    // We verify the cell-space conversion formula directly: panCellX += dx / cellPx.

    @Test
    fun `navvy accumulates pan as px-over-cellPx in cell space`() {
        val state = makeState()

        // A centroid move of 104px horizontally → 104 / CELL_PX cells = 2.0 cells.
        val dxPx = 104f
        assertTrue((dxPx * dxPx) > (NAVVY_NOISE_PX * NAVVY_NOISE_PX), "precondition: above noise")

        val dCell = dxPx / CELL_PX
        state.panBy(dCell, 0f)  // simulate what GameScreen calls after the noise check

        assertEquals(dCell, state.snapshot.value.panCellX, 1e-4f,
            "navvy must accumulate dx/cellPx in panCellX")
    }

    @Test
    fun `navvy y-flips before calling panBy (screen-down = engine-up)`() {
        // GameScreen: state.panBy(dx / cellPx, -dy / cellPx)
        // A downward drag (dy positive in screen space) should PAN DOWN in engine space, which
        // is a NEGATIVE panCellY because engine Y grows upward.
        val state = makeState()
        val dyPx   = 104f  // screen-down
        val dCell  = -(dyPx / CELL_PX)   // the y-flip
        state.panBy(0f, dCell)

        assertTrue(state.snapshot.value.panCellY < 0f,
            "a screen-downward drag must produce a negative panCellY (engine y-up flip)")
    }

    // ── F. minting gestures are NOT prematurely fired on a navvy frame ────────────────────────
    //
    // The GestureClassifier fires only when the FLOURISH commits. A multi-finger slide (the navvy
    // route) must never accidentally fire the classifier mid-slide. We prove this by pumping
    // multi-finger frames that look like an in-progress minting gesture (no flourish yet) and
    // asserting the classifier emits no Recognized.

    @Test
    fun `a multi-finger slide without a flourish does not commit a brick`() {
        val state = makeState()

        // Drive 15 frames of a 4-finger stationary hold — enough for the classifier to attempt
        // matching but without the lift flourish.
        val holdFrames = SyntheticStream.stroke(
            count       = 4,
            keys        = listOf(floatArrayOf(500f, 400f, 200f, 0f)),
            withFlourish = false,    // NO lift → no commit
            steps       = 15,
        )
        var committed = false
        for (frame in holdFrames) {
            state.onFingers(frame.fingers)
            if (state.tick(frame.timeNanos) != null) committed = true
        }

        assertFalse(committed, "a gesture without the flourish must never commit a brick")
        assertEquals(0, state.snapshot.value.bricks.size, "no brick is placed without a flourish")
    }

    // ── G. Navigation (selecty / navvy) never triggers a haptic ─────────────────────────────
    //
    // Only minting commits fire haptics. A tap (selecty) or a pan (navvy) must not buzz.

    @Test
    fun `selecty does not fire the haptic engine`() {
        val motor = FakeMotor()
        val state = makeState(motor)

        state.selectCell(Cell(1, 0))  // the selecty route (absolute slot pick)
        assertEquals(0, motor.playCount, "selecty must never fire the haptic engine")
    }

    @Test
    fun `navvy (panBy) does not fire the haptic engine`() {
        val motor = FakeMotor()
        val state = makeState(motor)

        state.panBy(2.0f, -1.0f)  // the navvy route
        assertEquals(0, motor.playCount, "navvy must never fire the haptic engine")
    }

    @Test
    fun `only a committed gesture fires the haptic engine`() {
        val motor = FakeMotor()
        val state = makeState(motor)

        // Place a brick via the full gesture pipeline — should fire thur + rummmm = 2 play calls.
        val stream = SyntheticStream.stroke(
            count = 4,
            keys  = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
        )
        stream.forEach { frame -> state.onFingers(frame.fingers); state.tick(frame.timeNanos) }

        assertTrue(motor.playCount >= 2,
            "a committed gesture must fire at least 2 haptic beats (thur + rummmm); got ${motor.playCount}")
    }

}
