package com.thrum.game

import com.thrum.deck.GestureSpec
import com.thrum.deck.Glyph
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thrum.gesture.DeliberateHold
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
import com.thuruummm.physics.Material
import com.thuruummm.physics.PhysicsEngine
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HOSTILE — the flourish-form COUPLING the orchestrator hides in its pre-classify gate.
 *
 * DESIGN.md and ARCHITECTURE.md §4 make the flourish form a SWAPPABLE strategy: "Flourish is a single
 * isolable predicate so the form can be retuned without touching the classifier or the deck." The two
 * shipped forms are [com.thrum.gesture.AllLiftAfterSettle] (commit on the all-fingers-lift) and
 * [DeliberateHold] (commit WHILE the hand is still pressed). The classifier itself asks one question —
 * `flourish.committed(frames)` — and is form-agnostic.
 *
 * BUT [GameState.tick] gates that question behind `if (frame.pressedCount == 0 && buffer.size >= 2)`.
 * That precondition is the all-lift form's precondition baked into the orchestrator: a [DeliberateHold]
 * commits while pressed (pressedCount > 0), so under a hold flourish the orchestrator NEVER calls
 * classify and NEVER commits. The form is therefore NOT swappable without touching tick — a violation
 * of the stated contract. The GameState KDoc even admits it ("If a future flourish form commits while
 * pressed … flip this to always classify").
 *
 * These tests pin the SPEC, not the current code. The paired test proves the synthetic hold stream is
 * a valid commit for a hold-configured classifier (so the failure localises to the orchestrator gate,
 * not the input). The orchestrator test then drives the SAME stream through [GameState.tick] and
 * asserts a commit — it will go RED until tick stops hard-coding the all-lift precondition. That red is
 * the finding.
 */
class AdversarialFlourishGateCouplingTest {

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

        override fun play(haptic: Haptic, priority: Boolean) { timeline += Event.Play(haptic.label, priority) }
        override fun cancel() { timeline += Event.Cancel }
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

    /**
     * A 4-finger gather held STILL at full count for ~480ms and NEVER lifted (withFlourish = false).
     * This is exactly a DeliberateHold commit: the hand converges, then holds — no lift. The window
     * ends with the hand still pressed.
     *
     * Two keyframes contract 200px → 50px (so the Gather scorer reads a real contraction), then 30
     * still frames at the final pose give the hold its 480ms still tail (DeliberateHold default
     * holdMs = 280ms; 30 × 16ms = 480ms clears it with margin).
     */
    private fun heldGatherStream(): List<PointerFrame> {
        val approach = SyntheticStream.stroke(
            count = 4,
            keys = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
            withFlourish = false,
        )
        // Append a long still hold at the final pose, continuing the timeline monotonically.
        val holdStart = approach.last().timeNanos + SyntheticStream.FRAME_NS
        val hold = SyntheticStream.stroke(
            count = 4,
            keys = listOf(floatArrayOf(500f, 400f, 50f, 0f)),
            steps = 30,
            withFlourish = false,
            startNs = holdStart,
        )
        return approach + hold
    }

    // ── PAIRED PROBE: a hold-configured classifier DOES commit on the held stream (input is valid) ─

    @Test
    fun `a DeliberateHold classifier commits on a held gather - the stream is a valid hold`() {
        val classifier = GestureClassifier(
            cards = listOf(gatherCard),
            flourish = DeliberateHold(minFingers = 4),
        )

        // The full window ends pressed; the hold flourish should have fired by the end of it.
        val recognized: Recognized? = classifier.classify(heldGatherStream(), com.thuruummm.physics.Cell(0, 0))
        assertNotNull(
            recognized,
            "sanity: a DeliberateHold classifier MUST recognise a 480ms still 4-finger gather — " +
                "if this fails the synthetic stream is wrong, not the orchestrator",
        )
    }

    // ── THE FINDING: the same hold stream through the orchestrator must commit, but the gate blocks it

    @Test
    fun `GameState commits a held-flourish gesture - tick must not hard-code the all-lift precondition`() {
        val motor = FakeMotor()
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(gatherCard), flourish = DeliberateHold(minFingers = 4)),
            haptics = ThuruummmHaptics(motor) { 0L },
            cardsById = mapOf(gatherCard.id to gatherCard),
        )

        // Drive the held stream through tick exactly as the GameLoop would. The hand never lifts, so
        // every frame has pressedCount > 0 — and GameState.tick only classifies when pressedCount == 0.
        var fired: Recognized? = null
        for (frame in heldGatherStream()) {
            state.onFingers(frame.fingers)
            state.tick(frame.timeNanos)?.let { fired = it }
        }

        assertNotNull(
            fired,
            "a DeliberateHold gesture must commit through GameState.tick — the flourish form is a " +
                "swappable strategy (DESIGN/ARCHITECTURE §4) and tick must not bake in the all-lift " +
                "pressedCount==0 precondition. This is RED until tick gates on flourish, not on lift.",
        )
        assertTrue(state.snapshot.value.bricks.size == 1, "the held gesture must place exactly one brick")
    }
}
