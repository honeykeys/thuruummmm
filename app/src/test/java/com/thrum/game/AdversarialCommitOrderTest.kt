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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOSTILE — the commit pipeline's FIRING ORDER, the load-bearing contract of game/ (ARCHITECTURE.md
 * §2 `commit(r)` / §4 "The two-beat firing points"; the KDoc on [GameState.commit]):
 *
 *   1. thur()                  — beat one, BEFORE physics, uniform
 *   2. engine.place(...)       — physics consumes ONLY the card's Material
 *   3. rummmm(card.rummmm)     — beat two, immediately AFTER place returns
 *   4. if collapse: thruummm() — the reward, priority-locked, AFTER the two-beat
 *
 * The existing [GameStatePipelineTest] checks the happy two-beat with the SHIPPING tappy material and
 * coincidental id strings. This file attacks the corners that path never reaches: the per-card
 * character of beat two (distinct from the uniform thur), that the physics actually receives the
 * card's Material (proven by the grid state the placement produces), the collapse priority/cancel
 * interleave by position not just presence, and the gate's silence on a sub-flourish. Each test is
 * built to go red on a re-ordering or a substitution of the four steps.
 *
 * SEAM NOTE (a finding, surfaced not hidden): [GameState] depends on the CONCRETE final
 * [PhysicsEngine], not an interface, so a test cannot inject a place-spy to put `place()` on the same
 * timeline as the haptics. The order thur → place → rummmm is therefore asserted via the haptic
 * timeline (thur strictly before rummmm) PLUS the grid mutation that only `place()` between them can
 * produce. If the orchestrator is ever refactored behind a physics interface, tighten this to a single
 * interleaved timeline.
 */
class AdversarialCommitOrderTest {

    // ── The fake motor: an ordered timeline of plays + cancels (the one injectable seam) ───────────

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

    // ── A deterministic single-card gather deck (same canonical stream the classifier suite uses) ──

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
    private fun fakeHaptics(motor: FakeMotor, clockMs: () -> Long = { 0L }) = ThuruummmHaptics(motor, clockMs)

    private fun newState(motor: FakeMotor, engine: PhysicsEngine = PhysicsEngine()) = GameState(
        engine = engine,
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

    // ── 1. beat one (thur) strictly precedes beat two (rummmm); and place ran between (grid proves) ─

    @Test
    fun `beat one precedes beat two and the placement happened between them`() {
        val motor = FakeMotor()
        val engine = PhysicsEngine()
        val state = newState(motor, engine)

        val fired = state.playStream(gatherStream())
        assertNotNull(fired, "the canonical gather must commit")

        // thur first, rummmm second — the two beats in order, nothing else on a clean placement.
        assertEquals(listOf("thur", "char-rummmm"), motor.labels, "thur (beat one) strictly before rummmm (beat two)")

        // place() ran between the beats: the only way a brick exists on the grid is engine.place().
        // The KDoc orders place() after thur() and before rummmm(); the grid mutation is its fingerprint.
        assertEquals(1, engine.grid.bricks.size, "engine.place must have minted exactly one brick during commit")
        assertEquals(tappyMaterial, engine.grid.at(Cell(0, 0))?.material, "the placed brick carries the card's Material")
    }

    // ── 2. the placed brick carries the card's Material verbatim — no substitution by the orchestrator

    @Test
    fun `physics receives the exact card material at the working slot`() {
        val motor = FakeMotor()
        val engine = PhysicsEngine()
        val state = newState(motor, engine)

        state.playStream(gatherStream())

        val placed = engine.grid.at(Cell(0, 0))
        assertNotNull(placed, "the first placement must land at the ground working slot (0,0)")
        assertEquals(tappyMaterial, placed.material, "physics must receive the EXACT card Material, untouched")
    }

    // ── 3. beat two carries THIS card's character, not the uniform thur ────────────────────────────

    @Test
    fun `beat two is the card's own rummmm, distinct from the uniform beat one`() {
        val motor = FakeMotor()
        val state = newState(motor)

        state.playStream(gatherStream())

        assertEquals(2, motor.labels.size, "exactly two beats on a clean placement")
        assertEquals("thur", motor.labels[0], "beat one is the uniform thur")
        assertEquals("char-rummmm", motor.labels[1], "beat two is the per-card rummmm authored by the deck")
        assertTrue(motor.labels[0] != motor.labels[1], "the two beats must differ — character lives in beat two")
    }

    // ── 4. a stable placement fires neither cancel nor thruummm ────────────────────────────────────

    @Test
    fun `a stable placement fires neither cancel nor thruummm`() {
        val motor = FakeMotor()
        val state = newState(motor)

        val fired = state.playStream(gatherStream())
        assertNotNull(fired)

        assertTrue(motor.timeline.none { it is FakeMotor.Event.Cancel }, "no collapse ⇒ no cancel")
        assertTrue(motor.labels.none { it == "thruummm" }, "no collapse ⇒ no THRUUMMMM")
        assertNull(state.snapshot.value.collapse, "no collapse ⇒ snapshot.collapse is null")
    }

    // ── 5. on a collapse: thur → rummmm → (cancel, thruummm-priority), in that exact order ─────────

    @Test
    fun `a collapsing placement ends in a cancel-then-priority crash after the two-beat`() {
        val motor = FakeMotor()
        val state = newState(motor)

        // Prime a 4-brick column at the strength limit (tappy strength 3.0, weight 1.0); the 5th tips it.
        repeat(4) { assertNotNull(state.playStream(gatherStream()), "each priming gather must commit") }
        assertEquals(4, state.snapshot.value.bricks.size, "a 4-brick column stands at the load limit")
        motor.timeline.clear()

        val fired = state.playStream(gatherStream())
        assertNotNull(fired, "the 5th gather commits and overloads the base")

        val thurIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Play && it.label == "thur" }
        val rummmmIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Play && it.label == "char-rummmm" }
        val cancelIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Cancel }
        val crashIdx = motor.timeline.indexOfFirst { it is FakeMotor.Event.Play && it.label == "thruummm" }

        assertTrue(thurIdx >= 0 && rummmmIdx >= 0 && crashIdx >= 0, "thur, rummmm, and the crash must all fire")
        assertTrue(thurIdx < rummmmIdx, "beat one before beat two")
        assertTrue(rummmmIdx < crashIdx, "the two-beat completes before the crash")
        assertTrue(cancelIdx in (rummmmIdx + 1) until crashIdx, "cancel() must land between rummmm and the crash (crash wins motor)")
        assertTrue(
            (motor.timeline[crashIdx] as FakeMotor.Event.Play).priority,
            "the THRUUMMMM must be played with priority = true",
        )
        // The crash is the LAST thing on the motor — nothing fires after it on the collapse frame.
        assertEquals(crashIdx, motor.timeline.indexOfLast { it is FakeMotor.Event.Play }, "the crash is the final play")
    }

    // ── 6. a sub-flourish stream touches neither the motor nor the grid ────────────────────────────

    @Test
    fun `a sub-flourish stream touches neither the motor nor the grid`() {
        val motor = FakeMotor()
        val engine = PhysicsEngine()
        val state = newState(motor, engine)

        val noFlourish = SyntheticStream.stroke(
            count = 4,
            keys = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
            withFlourish = false,
        )
        val fired = state.playStream(noFlourish)

        assertNull(fired, "no flourish ⇒ no commit")
        assertTrue(motor.timeline.isEmpty(), "no commit ⇒ the motor stays silent")
        assertEquals(0, engine.grid.bricks.size, "no commit ⇒ engine.place was never called")
        assertEquals(0, state.snapshot.value.bricks.size, "no brick placed")
    }
}
