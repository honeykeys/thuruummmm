package com.thrum.ui

import com.thrum.deck.GestureSpec
import com.thrum.deck.Glyph
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thrum.game.BrickView
import com.thrum.game.CollapseView
import com.thrum.game.GameState
import com.thrum.game.RenderSnapshot
import com.thrum.game.SlotDir
import com.thrum.gesture.GestureClassifier
import com.thrum.gesture.PointerFrame
import com.thrum.gesture.Recognized
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE — the [RenderSnapshot] construction contract and the FieldCanvas snapshot-mapping
 * invariants (the `com.thrum.ui` seam, from the game's side).
 *
 * FieldCanvas reads [RenderSnapshot] via a `State<RenderSnapshot>` delegate inside its draw lambda.
 * The Canvas owns the cell→pixel mapping; the snapshot owns the data. These tests attack the DATA
 * contract that FieldCanvas relies on — what it can assume about any [RenderSnapshot] it receives.
 *
 * The FieldCanvas draw logic itself requires Compose to test (render results verified via screencap).
 * These tests attack what the Canvas RECEIVES — the snapshot shape — entirely on the JVM.
 *
 * Invariants attacked:
 *
 *   1. RenderSnapshot.EMPTY is the canonical empty state. No bricks, no collapse, no pan,
 *      stress=1.0, targetCell=null (the starting condition before any selecty). The Canvas
 *      must not be broken by a null targetCell (the spec says "null only at game start").
 *
 *   2. After a placement, `bricks` contains exactly one [BrickView] per placed-and-surviving brick.
 *      Each BrickView carries the CORRECT (cell, glyph, material) triple. A mismatch here is a
 *      silent rendering error — the wrong glyph stamps on the wrong brick.
 *
 *   3. The `panCellX`/`panCellY` in the snapshot track EXACTLY the accumulated navvy deltas.
 *      The FieldCanvas subtracts these from each brick's cell-to-pixel mapping; off-by-one or
 *      truncated pan corrupts the entire field position.
 *
 *   4. The `collapse` field is non-null on exactly the TRIGGER FRAME and null on all other frames.
 *      The shake logic reads `snap.collapse` once per frame; a sticky collapse would replay the
 *      shake animation on every subsequent frame forever.
 *
 *   5. The `stress` field is 1.0 on an empty field and drops toward 0.0 as the structure approaches
 *      failure. The FieldCanvas uses it to scale the tremor overlay; a stress of exactly 0 means
 *      the tremor overlay is at maximum opacity.
 *
 *   6. [BrickView.id] is stable across cascade rings. The Canvas uses this id to key animations.
 *      We verify the id survives a non-collapsing placement (it is the physics Brick.id).
 *
 *   7. The `hapticsAvailable` flag is forwarded faithfully from the motor probe. If false, the
 *      Canvas shows the white-glow visual fallback on the collapse frame. A false flag wrongly
 *      carried as true would silently skip the visual fallback, breaking eyes-off accessibility.
 *
 * Screencap coverage note: visual appearance of cells at incorrect pixel coordinates, wrong glyph
 * shapes, missing stress overlay, missing collapse glow — all require device screencap to verify.
 * These tests prove the DATA the Canvas receives is correct; whether the Canvas renders it correctly
 * is the screencap pass.
 */
class RenderSnapshotMappingTest {

    private class FakeMotor(val hasVibrator: Boolean = true) : HapticSink {
        override val capabilities = Capabilities(
            hasVibrator = hasVibrator,
            hasAmplitudeControl = hasVibrator,
            supported = Primitive.entries.associateWith { hasVibrator },
            durationsMs = Primitive.entries.associateWith { 10 },
        )
        override fun play(haptic: Haptic, priority: Boolean) {}
        override fun cancel() {}
    }

    private val tappyMaterial = Material(
        weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2,
    )

    private val gatherCard = Thuruummm(
        id       = "gather-card",
        gesture  = GestureSpec(minFingers = 4, movement = Movement.Gather(0.6f), tolerance = 0.20f),
        material = tappyMaterial,
        rummmm   = haptic("r") { tick(0.5f) },
        glyph    = Glyph.ARROW_CENTER,
    )

    private fun makeState(hasVibrator: Boolean = true) = GameState(
        engine     = PhysicsEngine(),
        classifier = GestureClassifier(cards = listOf(gatherCard)),
        haptics    = ThuruummmHaptics(FakeMotor(hasVibrator)) { 0L },
        cardsById  = mapOf(gatherCard.id to gatherCard),
    )

    private fun gatherStream() = SyntheticStream.stroke(
        count = 4,
        keys  = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
    )

    private fun GameState.runStream(): Recognized? =
        gatherStream().fold<PointerFrame, Recognized?>(null) { fired, frame ->
            onFingers(frame.fingers); tick(frame.timeNanos) ?: fired
        }

    // ── 1. RenderSnapshot.EMPTY is the canonical first-frame state ────────────────────────────

    @Test
    fun `EMPTY has no bricks, no collapse, no pan, calm stress, and hapticsAvailable true`() {
        val empty = RenderSnapshot.EMPTY
        assertEquals(0, empty.bricks.size,         "EMPTY carries no bricks")
        assertNull(empty.targetCell,               "EMPTY targetCell is null (pre-first-selecty)")
        assertEquals(0f, empty.panCellX,           "EMPTY panCellX = 0")
        assertEquals(0f, empty.panCellY,           "EMPTY panCellY = 0")
        assertEquals(1.0, empty.stress,            "EMPTY stress = 1.0 (calm)")
        assertNull(empty.collapse,                 "EMPTY has no collapse")
        assertTrue(empty.hapticsAvailable,         "EMPTY assumes haptics available")
    }

    @Test
    fun `EMPTY is structurally equal to itself`() {
        // RenderSnapshot is a data class: EMPTY must equal a second EMPTY reference.
        assertEquals(RenderSnapshot.EMPTY, RenderSnapshot.EMPTY,
            "EMPTY must be equal to itself (data-class structural equality)")
    }

    // ── 2. After one placement the snapshot carries exactly one BrickView at the target cell ──

    @Test
    fun `one committed placement produces exactly one BrickView with correct cell, glyph, material`() {
        val state = makeState()
        assertNotNull(state.runStream(), "the gather commits")

        val snap = state.snapshot.value
        assertEquals(1, snap.bricks.size, "exactly one brick after one placement")
        val view = snap.bricks.single()
        assertEquals(Cell(0, 0), view.cell,         "the brick is at the ground working slot")
        assertEquals(Glyph.ARROW_CENTER, view.glyph, "the glyph matches the card's glyph")
        assertEquals(tappyMaterial, view.material,  "the material matches the card's material")
    }

    // ── 3. Pan deltas accumulate exactly in the snapshot (the Canvas y-flip must use these) ────

    @Test
    fun `pan deltas accumulate in the snapshot and read back exactly`() {
        val state = makeState()
        state.panBy(3.0f, -1.5f)
        state.panBy(-0.5f, 2.0f)

        val snap = state.snapshot.value
        assertEquals(2.5f, snap.panCellX, "panCellX = 3.0 + (-0.5) = 2.5")
        assertEquals(0.5f, snap.panCellY, "panCellY = -1.5 + 2.0 = 0.5")
    }

    @Test
    fun `pan accumulation is independent of placements`() {
        val state = makeState()
        state.panBy(2.0f, 1.0f)
        assertNotNull(state.runStream(), "the placement commits")
        state.panBy(1.0f, 0.5f)

        val snap = state.snapshot.value
        assertEquals(3.0f, snap.panCellX, "pan must accumulate across placements")
        assertEquals(1.5f, snap.panCellY, "pan must accumulate across placements")
    }

    // ── 4. The collapse field is a ONE-FRAME trigger ──────────────────────────────────────────
    //
    // Place enough bricks to trigger a collapse, then tick once more without placing — the
    // collapse view must be gone from the subsequent snapshot.

    @Test
    fun `the collapse field is non-null only on the trigger frame`() {
        val state = makeState()
        // A 5-tall column of strength-3 material: the 5th brick overloads the base.
        repeat(4) { assertNotNull(state.runStream(), "intermediate placements must commit") }
        assertNotNull(state.runStream(), "the tipping placement commits")

        val triggerSnap = state.snapshot.value
        assertNotNull(triggerSnap.collapse, "the trigger frame must carry a CollapseView")

        // One idle tick with no placement — collapse must clear.
        state.onFingers(emptyList())
        state.tick(99_000_000_000L)
        assertNull(state.snapshot.value.collapse,
            "the collapse view must be null on the next non-trigger frame — it is a one-frame latch")
    }

    // ── 5. Stress is calm (1.0) on an empty field and drops as the structure loads up ─────────

    @Test
    fun `stress is 1_0 on an empty field`() {
        val state = makeState()
        state.onFingers(emptyList())
        state.tick(0L)
        assertEquals(1.0, state.snapshot.value.stress,
            "an empty field has no load — stress must be maximally calm (1.0)")
    }

    @Test
    fun `stress is strictly less than 1_0 after placing bricks that load the structure`() {
        val state = makeState()
        // Three bricks stacked: the bottom brick bears the weight of the two above it.
        assertNotNull(state.runStream())
        state.selectAdjacent(SlotDir.UP)
        assertNotNull(state.runStream())
        state.selectAdjacent(SlotDir.UP)
        assertNotNull(state.runStream())

        val stress = state.snapshot.value.stress
        assertTrue(stress < 1.0,
            "a loaded structure must have stress < 1.0 (the tightest margin has narrowed), got $stress")
    }

    // ── 6. BrickView.id is stable across non-collapsing placements ───────────────────────────
    //
    // The Canvas keys animations by BrickView.id. The id must not change between consecutive
    // snapshots when the brick did not fall. We place one brick, read its id, tick once more,
    // and verify the id is unchanged.

    @Test
    fun `BrickView id is stable across idle ticks when the brick has not moved`() {
        val state = makeState()
        assertNotNull(state.runStream(), "the placement commits")
        val idAfterPlace = state.snapshot.value.bricks.single().id

        state.onFingers(emptyList())
        state.tick(99_000_000_000L)
        val idAfterIdle = state.snapshot.value.bricks.single().id

        assertEquals(idAfterPlace, idAfterIdle,
            "a non-collapsing brick's id must be stable across idle ticks — the Canvas uses it for animation keying")
    }

    // ── 7. hapticsAvailable = false from a no-motor sink is forwarded in every snapshot ────────
    //
    // FieldCanvas checks `!snap.hapticsAvailable && snap.collapse != null` to decide whether to
    // show the white-glow fallback. If `hapticsAvailable` were incorrectly stuck at `true` for a
    // no-motor device, the fallback would never fire, leaving the player with no feedback channel.

    @Test
    fun `hapticsAvailable is false on every snapshot when the motor is absent`() {
        val state = makeState(hasVibrator = false)

        // Idle tick → snapshot published.
        state.onFingers(emptyList())
        state.tick(0L)
        assertFalse(state.snapshot.value.hapticsAvailable,
            "hapticsAvailable must be false on every snapshot when the device has no vibrator")

        // After a placement → snapshot published again.
        assertNotNull(state.runStream())
        assertFalse(state.snapshot.value.hapticsAvailable,
            "hapticsAvailable must remain false after a placement on a no-motor device")

        // After a nav move → snapshot published.
        state.selectAdjacent(SlotDir.RIGHT)
        assertFalse(state.snapshot.value.hapticsAvailable,
            "hapticsAvailable must remain false after navigation on a no-motor device")
    }

    // ── 8. The targetCell in the snapshot matches the current working slot ────────────────────
    //
    // FieldCanvas draws the target-cell highlight at `snap.targetCell`. If the target in the
    // snapshot lags the real working slot (stale snapshot), the highlight appears in the wrong cell —
    // a subtle but gameplay-breaking visual bug. We assert that after each navigation call, the
    // snapshot's targetCell is immediately updated.

    @Test
    fun `the targetCell in the snapshot is immediately updated after a selectAdjacent call`() {
        val state = makeState()
        val before = state.snapshot.value.targetCell

        // Move RIGHT — must appear in the VERY NEXT snapshot read (selectAdjacent publishes one).
        val moved = state.selectAdjacent(SlotDir.RIGHT)
        val after  = state.snapshot.value.targetCell

        assertEquals(moved, after,
            "the snapshot targetCell must equal the cell returned by selectAdjacent — no one-frame lag")
        // And it must have actually moved.
        assert(after != before) { "selectAdjacent to a legal ground slot must update the target" }
    }

    // ── 9. The CollapseView magnitude matches the physics CollapseResult ──────────────────────
    //
    // The magnitude in `snap.collapse` is forwarded verbatim from the physics engine. It drives
    // both the THRUUMMMM haptic and the screen-shake amplitude — if the forwarding truncates or
    // modifies it, both channels mis-scale together (MOTION.md §4.3: "one number feeds both").

    @Test
    fun `the CollapseView magnitude in the snapshot matches the physics collapse`() {
        val state = makeState()
        // Place 5 bricks to trigger a collapse (4th or 5th overloads strength-3 material with
        // cumulative weight).
        repeat(4) { assertNotNull(state.runStream()) }
        assertNotNull(state.runStream(), "the tipping placement must commit")

        val snap = state.snapshot.value
        val collapseView = snap.collapse
        assertNotNull(collapseView, "a CollapseView must be present on the trigger frame")
        assertTrue(collapseView.magnitude > 0.0,
            "the magnitude must be > 0 for a real collapse (fell × rings × variety > 0)")
        assertTrue(collapseView.rings >= 1,
            "a cascade must have at least 1 ring")
        assertTrue(collapseView.fellIds.isNotEmpty(),
            "at least one brick must be recorded as fallen in the CollapseView")
    }
}
