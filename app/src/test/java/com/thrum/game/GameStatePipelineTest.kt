package com.thrum.game

import com.thrum.deck.Deck
import com.thrum.deck.GestureSpec
import com.thrum.deck.Glyph
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C6 — the orchestrator's test surface (ARCHITECTURE.md §6/§7, "game/" row): drive [GameState.tick]
 * with fakes/synthetic input and assert (a) the commit FIRING ORDER (thur → place → rummmm →
 * maybe-thruummm) and (b) that a [RenderSnapshot] is produced. Pure JVM — no Compose, no device, no
 * frame loop: [GameLoop] is the only Compose-coupled piece and it merely calls `tick(nanos)`, so the
 * whole pipeline is exercised by calling tick directly with hand-authored frame nanos.
 *
 * The motor is faked at the one injectable seam, [HapticSink] (HapticSink.kt; same pattern as
 * FakeVibratorOrderTest), wrapped in the real [ThuruummmHaptics] facade so the real two-beat +
 * coalescing logic runs. The classifier and physics are REAL — this is an honest integration test of
 * the actual recognition + placement, with only the device motor stubbed.
 *
 * Input is a canonical synthetic GATHER stream (the same builder the classifier's own tests use), so
 * the real classifier deterministically recognises a gather card. We drive it through a single-card
 * deck (just a gather card) so recognition is unambiguous and the test does not couple to the whole
 * shipping deck's tuning.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.GameStatePipelineTest"
 */
class GameStatePipelineTest {

    // ── The fake motor: records the ordered timeline of plays + cancels (HapticSink seam) ─────────

    private class FakeMotor : HapticSink {
        sealed interface Event {
            data object Cancel : Event
            data class Play(val label: String, val priority: Boolean) : Event
        }

        val timeline = mutableListOf<Event>()

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

        val playLabels: List<String> get() = timeline.filterIsInstance<Event.Play>().map { it.label }
    }

    // ── A deterministic single-card gather deck ───────────────────────────────────────────────────

    private val tappyMaterial = Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)

    private val gatherCard = Thuruummm(
        id = "test-tappy",
        gesture = GestureSpec(minFingers = 4, movement = Movement.Gather(maxSpreadRatio = 0.6f), tolerance = 0.20f),
        material = tappyMaterial,
        rummmm = haptic("test-rummmm") { tick(scale = 0.55f) },
        glyph = Glyph.ARROW_CENTER,
    )

    /** A real classifier over just the gather card — recognition is unambiguous for a gather stream. */
    private fun gatherClassifier() = GestureClassifier(cards = listOf(gatherCard))

    /** A real haptics facade over a fake motor with a deterministic (test) clock so coalescing is exact. */
    private fun fakeHaptics(motor: FakeMotor, clockMs: () -> Long = { 0L }) = ThuruummmHaptics(motor, clockMs)

    /**
     * A canonical gather: 4 fingers contracting from radius 200 → 50 about (500, 400), ending in the
     * default settle+lift flourish. Same builder the classifier's own suite uses.
     */
    private fun gatherStream() = SyntheticStream.stroke(
        count = 4,
        keys = listOf(
            floatArrayOf(500f, 400f, 200f, 0f),
            floatArrayOf(500f, 400f, 50f, 0f),
        ),
    )

    /**
     * Feed a whole synthetic gesture stream into a [GameState] frame-by-frame, exactly as the real
     * GameLoop would: deposit each frame's fingers via onFingers, then tick with that frame's nanos.
     * Returns the last [com.thrum.gesture.Recognized] tick (the commit), or null if none fired.
     */
    private fun GameState.playStream(stream: List<com.thrum.gesture.PointerFrame>) =
        stream.fold<_, com.thrum.gesture.Recognized?>(null) { fired, frame ->
            onFingers(frame.fingers)
            tick(frame.timeNanos) ?: fired
        }

    // ── 1. Firing order: thur → place → rummmm, no collapse, on a clean ground placement ──────────

    @Test
    fun `commit fires thur then rummmm in order and produces a snapshot`() {
        val motor = FakeMotor()
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = gatherClassifier(),
            haptics = fakeHaptics(motor),
            cardsById = mapOf(gatherCard.id to gatherCard),
        )

        val fired = state.playStream(gatherStream())

        assertNotNull(fired, "the synthetic gather must commit a card")
        assertEquals("test-tappy", fired.card.id, "the gather card must be the one recognised")

        // The two-beat: thur (beat one, uniform) strictly before rummmm (beat two, card character).
        // No collapse on a single ground brick, so no thruummm.
        assertEquals(
            listOf("thur", "test-rummmm"),
            motor.playLabels,
            "commit must fire exactly thur then the card's rummmm, in that order",
        )
        assertTrue(motor.timeline.none { it is FakeMotor.Event.Cancel }, "no cancel without a collapse")

        // A RenderSnapshot was produced and carries the placed brick at the working slot (ground).
        val snap = state.snapshot.value
        assertEquals(1, snap.bricks.size, "the placed brick must appear in the snapshot")
        assertEquals(Cell(0, 0), snap.bricks.single().cell, "first brick lands on the ground working slot")
        assertEquals(Glyph.ARROW_CENTER, snap.bricks.single().glyph, "snapshot recovers the card's glyph by material")
        assertNull(snap.collapse, "a stable single placement reports no collapse")
    }

    // ── 2. A collapse fires the THRUUMMMM (priority, motor-locked) after the two-beat ─────────────

    @Test
    fun `a placement that collapses fires thruummm with priority`() {
        val motor = FakeMotor()
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = gatherClassifier(),
            haptics = fakeHaptics(motor),
            cardsById = mapOf(gatherCard.id to gatherCard),
        )

        // Build a column of the weak gather material THROUGH the pipeline, so the working slot walks up
        // the column automatically (nextDefaultTarget → the free cell above each placed brick). tappy
        // strength 3.0, weight 1.0: a 4-brick column loads the bottom to 3.0 (at limit); the 5th brick
        // tips it to 4.0 > 3.0 and the bottom buckles (PHYSICS.md test 1b). Each placement is its own
        // committed gather, starting at (0,0) and climbing.
        repeat(4) {
            val committed = state.playStream(gatherStream())
            assertNotNull(committed, "each priming gather must commit")
        }
        assertEquals(4, state.snapshot.value.bricks.size, "a 4-brick column should stand (load 3.0 at limit)")
        assertEquals(Cell(0, 4), state.snapshot.value.targetCell, "the working slot has climbed to the column top")

        // Clear the motor timeline so we assert only the collapsing placement's haptics.
        motor.timeline.clear()

        val fired = state.playStream(gatherStream())
        assertNotNull(fired, "the gather must commit the load-tipping 5th brick")

        // The two-beat fired, then — because the placement buckled the column — the THRUUMMMM, which
        // priority-locks the motor (HapticEngine.play(priority=true) → cancel() then crash).
        val labels = motor.playLabels
        assertEquals("thur", labels[0], "beat one first")
        assertEquals("test-rummmm", labels[1], "beat two second")
        assertTrue(labels.any { it == "thruummm" }, "a collapse must fire the THRUUMMMM reward")
        // The crash play is a PRIORITY play, and a cancel precedes it on the timeline (crash wins motor).
        val crashIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Play && it.label == "thruummm" }
        val cancelIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Cancel }
        assertTrue(cancelIdx in 0 until crashIdx, "cancel() must land before the crash play (crash wins the motor)")
        assertTrue(
            (motor.timeline[crashIdx] as FakeMotor.Event.Play).priority,
            "the THRUUMMMM must be played with priority = true",
        )

        // The snapshot reports the collapse on the trigger frame (proportional shake/tumble signal).
        assertNotNull(state.snapshot.value.collapse, "the collapse-trigger frame carries a CollapseView")
        assertTrue(state.snapshot.value.collapse!!.magnitude > 0.0, "the collapse has a positive magnitude")
    }

    // ── 3. Sub-flourish input commits nothing and still publishes a (calm) snapshot ───────────────

    @Test
    fun `a gesture in progress with no flourish commits nothing`() {
        val motor = FakeMotor()
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = gatherClassifier(),
            haptics = fakeHaptics(motor),
            cardsById = mapOf(gatherCard.id to gatherCard),
        )

        // A gather with the flourish tail OMITTED — the hand never lifts, so nothing should commit.
        val noFlourish = SyntheticStream.stroke(
            count = 4,
            keys = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
            withFlourish = false,
        )
        val fired = state.playStream(noFlourish)

        assertNull(fired, "no flourish ⇒ no commit")
        assertTrue(motor.timeline.isEmpty(), "no commit ⇒ no haptics fired")
        assertEquals(0, state.snapshot.value.bricks.size, "no brick placed")
        // A snapshot is still produced every tick (the Canvas always has a value to draw).
        assertNotNull(state.snapshot.value, "a snapshot is published every tick even with no commit")
    }

    // ── 4. selecty / navvy navigation updates the snapshot without placing ────────────────────────

    @Test
    fun `selecty moves the working slot and navvy pans, both without placing`() {
        val motor = FakeMotor()
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = gatherClassifier(),
            haptics = fakeHaptics(motor),
            cardsById = mapOf(gatherCard.id to gatherCard),
        )

        // selecty: from the ground origin, RIGHT is a legal ground slot (y == 0 always buildable).
        val moved = state.selectAdjacent(SlotDir.RIGHT)
        assertEquals(Cell(1, 0), moved, "selecty moves the working slot to the adjacent ground cell")
        assertEquals(Cell(1, 0), state.snapshot.value.targetCell, "the snapshot reflects the new working slot")

        // selecty UP from a ground cell with no brick is NOT buildable (no adjacency) → no move.
        val blocked = state.selectAdjacent(SlotDir.UP)
        assertEquals(Cell(1, 0), blocked, "an illegal (unsupported) slot is a no-op, target unchanged")

        // navvy: pan the camera; no brick moves, the pan offset is carried to the snapshot.
        state.panBy(2.5f, -1.0f)
        assertEquals(2.5f, state.snapshot.value.panCellX, "navvy accumulates the x pan")
        assertEquals(-1.0f, state.snapshot.value.panCellY, "navvy accumulates the y pan")
        assertEquals(0, state.snapshot.value.bricks.size, "navigation never places a brick")
        assertTrue(motor.timeline.isEmpty(), "navigation never fires haptics")
    }
}
