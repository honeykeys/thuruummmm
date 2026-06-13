package com.thrum.game

import com.thrum.deck.GestureSpec
import com.thrum.deck.Glyph
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
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
 * HOSTILE — the orchestrator's STATE TRANSITIONS: how [GameState] moves the working slot, accumulates
 * pan, rebuilds the snapshot, and stays consistent across repeated place/navigate/reset (ARCHITECTURE.md
 * §6 "game/" row: "assert the firing order AND that a RenderSnapshot is produced"; PHYSICS.md test 7:
 * "Repeated place / collapse leaves the structure in a consistent, non-contradictory state").
 *
 * The existing [GameStatePipelineTest] checks one happy commit and one happy nav move. This file
 * attacks the transitions BETWEEN commits — the parts that bite when a real player drums the glass:
 *
 *   - the working slot auto-advances after a surviving placement, but must NEVER advance to an
 *     illegal slot (the next gesture has to land somewhere real);
 *   - after a collapse consumes the placed brick, the slot must FALL BACK to a legal ground cell so
 *     play can always continue;
 *   - the loop ticking the SAME lifted fingers after a commit must NOT double-fire the commit;
 *   - reset() must return EVERY field to first-frame state, not just the visible ones;
 *   - navigation publishes a fresh snapshot every call without ever placing or buzzing.
 */
class AdversarialStateTransitionTest {

    private class FakeMotor : HapticSink {
        sealed interface Event {
            data object Cancel : Event
            data class Play(val label: String, val priority: Boolean) : Event
        }

        val timeline = mutableListOf<Event>()
        val labels: List<String> get() = timeline.filterIsInstance<Event.Play>().map { it.label }

        override val capabilities = Capabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            supported = Primitive.entries.associateWith { true },
            durationsMs = Primitive.entries.associateWith { 10 },
        )

        override fun play(haptic: Haptic, priority: Boolean) {
            timeline += Event.Play(haptic.label, priority)
        }

        override fun cancel() {
            timeline += Event.Cancel
        }
    }

    private val tappyMaterial =
        Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)

    private val gatherCard = Thuruummm(
        id = "test-tappy",
        gesture = GestureSpec(minFingers = 4, movement = Movement.Gather(maxSpreadRatio = 0.6f), tolerance = 0.20f),
        material = tappyMaterial,
        rummmm = haptic("char-rummmm") { tick(scale = 0.55f) },
        glyph = Glyph.ARROW_CENTER,
    )

    private fun gatherClassifier() = GestureClassifier(cards = listOf(gatherCard))
    private fun fakeHaptics(motor: FakeMotor) = ThuruummmHaptics(motor) { 0L }

    private fun newState(motor: FakeMotor = FakeMotor()) = GameState(
        engine = PhysicsEngine(),
        classifier = gatherClassifier(),
        haptics = fakeHaptics(motor),
        cardsById = mapOf(gatherCard.id to gatherCard),
    )

    private fun gatherStream() = SyntheticStream.stroke(
        count = 4,
        keys = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
    )

    private fun GameState.playStream(stream: List<PointerFrame>): Recognized? =
        stream.fold<PointerFrame, Recognized?>(null) { fired, frame ->
            onFingers(frame.fingers)
            tick(frame.timeNanos) ?: fired
        }

    // ── 1. after a surviving placement the working slot auto-advances ABOVE the brick ──────────────

    @Test
    fun `the working slot climbs above a surviving placement so the build grows outward`() {
        val state = newState()

        val first = state.playStream(gatherStream())
        assertNotNull(first, "the first gather commits")
        assertEquals(Cell(0, 0), first.targetCell, "the first brick lands on the ground working slot")
        // The post-commit transition: nextDefaultTarget walks the slot to the free cell above (0,0).
        assertEquals(Cell(0, 1), state.snapshot.value.targetCell, "the working slot must advance to the cell above")
    }

    // ── 2. the auto-advanced slot is ALWAYS a legal build slot (never floating, never below bedrock)

    @Test
    fun `every auto-advanced working slot is a legal build slot`() {
        val state = newState()

        // Drum five surviving placements (a 5-tall column of strength-3 material does NOT collapse: the
        // bottom bears 4.0 > 3.0 only at the FIFTH — so cap at four to keep them all surviving) and after
        // EACH, the published target must be a free cell adjacent to a brick or on the ground.
        repeat(4) {
            val fired = state.playStream(gatherStream())
            assertNotNull(fired, "each gather commits while the column stands")
            val target = state.snapshot.value.targetCell
            assertNotNull(target, "a target is always published after a placement")
            assertFalse(state.snapshot.value.bricks.any { it.cell == target }, "the target must be a FREE cell")
            val legal = target.y == 0 ||
                target.neighbours().any { n -> state.snapshot.value.bricks.any { it.cell == n } }
            assertTrue(legal, "the target must be on the ground or adjacent to a placed brick, was $target")
            assertTrue(target.y >= 0, "the target must never be below bedrock")
        }
    }

    // ── 3. after a collapse eats the placed brick, the slot falls back to a legal ground cell ──────

    @Test
    fun `a collapse retargets the working slot to a legal cell so play continues`() {
        val state = newState()

        // Build the column to the limit, then tip it. After the collapse the prior target (the top of
        // the column) may no longer be valid; the orchestrator must publish a target that is still a
        // legal build slot — never a floating or occupied cell.
        repeat(4) { assertNotNull(state.playStream(gatherStream())) }
        val fired = state.playStream(gatherStream())
        assertNotNull(fired, "the load-tipping gather commits")
        assertNotNull(state.snapshot.value.collapse, "the placement collapsed the column")

        val target = state.snapshot.value.targetCell
        assertNotNull(target, "a target is published even after a collapse")
        assertTrue(target.y >= 0, "the fallback target is never below bedrock")
        assertFalse(state.snapshot.value.bricks.any { it.cell == target }, "the fallback target is a FREE cell")
        val legal = target.y == 0 ||
            target.neighbours().any { n -> state.snapshot.value.bricks.any { it.cell == n } }
        assertTrue(legal, "the fallback target is legal (ground or adjacency), was $target")
    }

    // ── 4. the loop ticking the SAME lifted fingers after a commit must NOT double-fire ────────────

    @Test
    fun `re-ticking the post-commit lifted frame does not place a second brick`() {
        val motor = FakeMotor()
        val state = newState(motor)

        // Play one full gather (ending in the lifted flourish frame). It commits exactly once.
        val fired = state.playStream(gatherStream())
        assertNotNull(fired, "the gather commits once")
        assertEquals(1, state.snapshot.value.bricks.size, "exactly one brick after the first commit")
        val beatsAfterOne = motor.labels.size

        // The real GameLoop ticks every frame whether or not new pointer events arrive. Simulate the
        // loop continuing to tick with the SAME (stale) lifted fingers still deposited — no new gesture.
        // pendingFingers is NOT cleared on commit, so this re-pushes the lifted frame; buffer was
        // cleared, so it refills with lifted-only frames. A lifted-only window has no last-pressed
        // frame, so the flourish can never re-fire. Anything else is a phantom double-commit.
        val staleLifted = SyntheticStream.lifted(count = 4)
        var laterNanos = 10_000_000_000L
        repeat(10) {
            state.onFingers(staleLifted)
            val again = state.tick(laterNanos)
            assertNull(again, "a lifted-only window must never re-commit a brick")
            laterNanos += SyntheticStream.FRAME_NS
        }

        assertEquals(1, state.snapshot.value.bricks.size, "still exactly one brick — no phantom second placement")
        assertEquals(beatsAfterOne, motor.labels.size, "no extra haptic beats fired on the idle re-ticks")
    }

    // ── 5. reset() returns EVERY field to first-frame state ────────────────────────────────────────

    @Test
    fun `reset clears bricks, target, pan, snapshot, and the motor`() {
        val motor = FakeMotor()
        val state = newState(motor)

        // Dirty every field: place a brick, pan the camera, move the slot.
        assertNotNull(state.playStream(gatherStream()), "place a brick")
        state.panBy(3.5f, -2.0f)
        state.selectAdjacent(SlotDir.RIGHT)
        assertTrue(state.snapshot.value.bricks.isNotEmpty(), "precondition: the field is dirty")
        assertTrue(state.snapshot.value.panCellX != 0f, "precondition: the camera is panned")

        motor.timeline.clear()
        state.reset()

        val snap = state.snapshot.value
        assertEquals(0, snap.bricks.size, "reset clears all bricks")
        assertEquals(0f, snap.panCellX, "reset zeroes the x pan")
        assertEquals(0f, snap.panCellY, "reset zeroes the y pan")
        assertNull(snap.collapse, "reset clears any collapse view")
        assertEquals(RenderSnapshot.EMPTY, snap, "reset publishes the canonical EMPTY snapshot")
        assertTrue(motor.timeline.any { it is FakeMotor.Event.Cancel }, "reset stops any in-flight vibration")

        // And the engine truly restarted: the next placement lands at the ground origin again.
        val afterReset = state.playStream(gatherStream())
        assertNotNull(afterReset, "play resumes after reset")
        assertEquals(Cell(0, 0), afterReset.targetCell, "the first post-reset brick lands at the ground origin")
    }

    // ── 6. selecty rejects illegal slots; navvy accumulates pan; neither places nor buzzes ─────────

    @Test
    fun `navigation moves the slot or pans without ever placing or buzzing`() {
        val motor = FakeMotor()
        val state = newState(motor)

        // selecty RIGHT from the origin: a ground cell, always buildable.
        assertEquals(Cell(1, 0), state.selectAdjacent(SlotDir.RIGHT), "selecty moves to the adjacent ground cell")
        assertEquals(Cell(1, 0), state.snapshot.value.targetCell, "the snapshot reflects the move")

        // selecty UP from a bare ground cell: no adjacency, illegal → no move.
        assertEquals(Cell(1, 0), state.selectAdjacent(SlotDir.UP), "an unsupported slot is a no-op")

        // selecty DOWN below the ground row: y == -1 is below bedrock, illegal → no move.
        assertEquals(Cell(1, 0), state.selectAdjacent(SlotDir.DOWN), "a sub-bedrock slot is a no-op")

        // navvy accumulates across calls.
        state.panBy(2.5f, -1.0f)
        state.panBy(0.5f, 1.0f)
        assertEquals(3.0f, state.snapshot.value.panCellX, "navvy accumulates the x pan across calls")
        assertEquals(0.0f, state.snapshot.value.panCellY, "navvy accumulates the y pan across calls")

        assertEquals(0, state.snapshot.value.bricks.size, "navigation never places a brick")
        assertTrue(motor.timeline.isEmpty(), "navigation never fires a haptic")
    }

    // ── 7. the snapshot's collapse is a ONE-FRAME trigger: it clears on the next non-collapse tick ──

    @Test
    fun `the collapse view appears only on the trigger frame and clears on the next tick`() {
        val state = newState()

        repeat(4) { assertNotNull(state.playStream(gatherStream())) }
        assertNotNull(state.playStream(gatherStream()), "the tipping gather commits")
        assertNotNull(state.snapshot.value.collapse, "the trigger frame carries a CollapseView")

        // One more idle tick (no commit): the collapse trigger must be gone — it is a single-frame
        // latch the render reads once, not a sticky flag (RenderSnapshot.kt: "subsequent snapshots
        // report null again").
        state.onFingers(emptyList())
        state.tick(99_000_000_000L)
        assertNull(state.snapshot.value.collapse, "the collapse view must clear on the next non-collapse tick")
    }
}
