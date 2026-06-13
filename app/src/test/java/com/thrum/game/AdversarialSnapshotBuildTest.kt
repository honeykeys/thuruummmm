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
import com.thuruummm.physics.Stress
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HOSTILE — the snapshot construction & feedback-state transitions: the part of [GameState] that
 * bridges physics-space and deck-space into the single immutable [RenderSnapshot] the Canvas reads
 * (GameState.buildSnapshot / glyphFor / glyphByMaterial; RenderSnapshot.kt).
 *
 * These attack the joins the happy path glosses over:
 *
 *   - GLYPH RECOVERY by Material is a MAP INVERSION with a documented "first card wins" tie-break when
 *     two cards share a Material. The existing tests never build that collision; this does, and pins
 *     the tie-break so a regression to last-wins (associateBy semantics) goes red.
 *   - the classifier and cardsById are INDEPENDENTLY injectable, so a placed brick's material may be
 *     ABSENT from the glyph map. The KDoc promises a real fallback glyph ("never silent corruption"),
 *     not a crash — pinned here.
 *   - onFingers is latest-wins within a frame; the loop drains one PointerFrame per tick.
 *   - stress is published every tick; an empty field reads calm (1.0) and agrees with Stress.margin.
 *   - hapticsAvailable is probed from the motor and forwarded to every snapshot.
 */
class AdversarialSnapshotBuildTest {

    private class FakeMotor(private val vibrator: Boolean = true) : HapticSink {
        override val capabilities = Capabilities(
            hasVibrator = vibrator,
            hasAmplitudeControl = vibrator,
            supported = Primitive.entries.associateWith { vibrator },
            durationsMs = Primitive.entries.associateWith { 10 },
        )

        override fun play(haptic: Haptic, priority: Boolean) {}
        override fun cancel() {}
    }

    private val tappyMaterial =
        Material(weight = 1.0, strength = 3.0, cantilever = 0, shatterThreshold = 2, brittleness = 0.2)

    private fun gatherCard(id: String, glyph: Glyph, material: Material = tappyMaterial) = Thuruummm(
        id = id,
        gesture = GestureSpec(minFingers = 4, movement = Movement.Gather(maxSpreadRatio = 0.6f), tolerance = 0.20f),
        material = material,
        rummmm = haptic("rummmm-$id") { tick(scale = 0.55f) },
        glyph = glyph,
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

    // ── 1. glyph recovery: when two cards share a Material, the FIRST card's glyph wins ────────────

    @Test
    fun `glyph recovery resolves a shared-material collision to the first card deterministically`() {
        // Two cards, SAME Material, DIFFERENT glyphs. The classifier recognises whichever it is given;
        // we give it only `first`, so `first` is placed. The glyph map is built over BOTH (cardsById),
        // and must resolve the shared material to the FIRST card's glyph (putIfAbsent over an ordered
        // map), never the second's. A regression to associateBy/last-wins flips this to SPIRAL.
        val first = gatherCard("first", Glyph.ARROW_CENTER)
        val second = gatherCard("second", Glyph.SPIRAL)   // same material, different glyph

        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(first)),     // only `first` can be recognised
            haptics = ThuruummmHaptics(FakeMotor()) { 0L },
            cardsById = linkedMapOf(first.id to first, second.id to second), // ordered: first precedes second
        )

        assertNotNull(state.playStream(gatherStream()), "the gather commits the first card")
        assertEquals(
            Glyph.ARROW_CENTER,
            state.snapshot.value.bricks.single().glyph,
            "a shared Material must resolve to the FIRST card's glyph, not the last (documented tie-break)",
        )
    }

    // ── 2. a placed material absent from cardsById falls back to a real glyph, never a crash ───────

    @Test
    fun `a placed material missing from the glyph map falls back to a real glyph`() {
        // The classifier recognises `placed`, but cardsById does NOT contain it (independent injection).
        // glyphFor cannot find the material → it must fall back to a concrete glyph (ARROW_CENTER per
        // the KDoc), not throw and not emit null.
        val placed = gatherCard("placed-only", Glyph.SPIRAL)
        val other = gatherCard("other", Glyph.ARROW_RIGHT, material = tappyMaterial.copy(strength = 9.0))

        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(placed)),
            haptics = ThuruummmHaptics(FakeMotor()) { 0L },
            cardsById = mapOf(other.id to other),   // does NOT contain `placed`'s material
        )

        assertNotNull(state.playStream(gatherStream()), "the gather commits even when its glyph is unmapped")
        val brick = state.snapshot.value.bricks.single()
        assertEquals(Glyph.ARROW_CENTER, brick.glyph, "an unmapped material must fall back to ARROW_CENTER, never crash")
        assertEquals(tappyMaterial, brick.material, "the BrickView still carries the true physics Material")
    }

    // ── 3. onFingers is latest-wins within a frame: the loop drains the freshest deposit ──────────

    @Test
    fun `onFingers coalesces intra-frame deposits to the latest before the tick reads it`() {
        // A deck whose Gather requires 5 fingers. For EVERY frame of a clean 5-finger gather, deposit a
        // bogus 4-finger set FIRST, then OVERWRITE with the real 5-finger frame, then tick. Latest-wins
        // means each frame the loop reads is the 5-finger one, so the gather commits. If onFingers were
        // first-wins / queued / merged, a 4-finger frame would survive into the window and the 5-finger
        // card's per-card finger gate (fingerCount 4 < minFingers 5) would reject → NO commit.
        val fiveFingerCard = gatherCard("five", Glyph.ARROW_CENTER).let {
            it.copy(gesture = it.gesture.copy(minFingers = 5))
        }
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(fiveFingerCard)),
            haptics = ThuruummmHaptics(FakeMotor()) { 0L },
            cardsById = mapOf(fiveFingerCard.id to fiveFingerCard),
        )

        val bogusFour = SyntheticStream.ring(900f, 100f, 300f, count = 4)   // wrong count, wrong place
        val fiveStream = SyntheticStream.stroke(
            count = 5,
            keys = listOf(floatArrayOf(500f, 400f, 200f, 0f), floatArrayOf(500f, 400f, 50f, 0f)),
        )

        var fired: Recognized? = null
        for (frame in fiveStream) {
            state.onFingers(bogusFour)        // a stale deposit the latest must overwrite
            state.onFingers(frame.fingers)    // the real 5-finger frame — latest-wins
            state.tick(frame.timeNanos)?.let { fired = it }
        }

        assertNotNull(fired, "latest-wins kept every frame at 5 fingers, so the 5-finger gather commits")
        assertEquals("five", fired.card.id, "the 5-finger card fired — the bogus 4-finger deposits never leaked in")
    }

    // ── 4. stress is published every tick; an empty field is calm and agrees with Stress.margin ────

    @Test
    fun `an idle tick publishes the engine's stress margin and an empty field reads calm`() {
        val engine = PhysicsEngine()
        val state = GameState(
            engine = engine,
            classifier = GestureClassifier(cards = listOf(gatherCard("a", Glyph.ARROW_CENTER))),
            haptics = ThuruummmHaptics(FakeMotor()) { 0L },
            cardsById = mapOf("a" to gatherCard("a", Glyph.ARROW_CENTER)),
        )

        // Tick once with no fingers: a snapshot is published and its stress equals the engine margin.
        state.onFingers(emptyList())
        state.tick(0L)
        val snap = state.snapshot.value
        assertEquals(Stress.margin(engine.grid), snap.stress, "an idle tick republishes the live stress margin")
        assertEquals(1.0, snap.stress, "an empty field is maximally calm (margin 1.0)")
        // buildSnapshot always stamps the working slot (default Cell(0,0)), so an idle tick is NOT the
        // null-target EMPTY constant — but it must still carry no bricks and no collapse.
        assertEquals(0, snap.bricks.size, "an idle tick over an empty field carries no bricks")
        assertEquals(Cell(0, 0), snap.targetCell, "the default working slot is the ground origin")
        assertTrue(snap.collapse == null, "an idle tick carries no collapse")
    }

    // ── 5. hapticsAvailable is forwarded from the motor probe to the snapshot (visual-fallback flag) ─

    @Test
    fun `the no-motor capability is surfaced on every snapshot for the visual fallback`() {
        val noMotor = FakeMotor(vibrator = false)
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(gatherCard("a", Glyph.ARROW_CENTER))),
            haptics = ThuruummmHaptics(noMotor) { 0L },
            cardsById = mapOf("a" to gatherCard("a", Glyph.ARROW_CENTER)),
        )

        // An idle tick over an empty field is EMPTY, whose hapticsAvailable default is true — so to read
        // the forwarded probe we must publish a NON-empty snapshot. selectAdjacent to a legal ground
        // cell publishes a fresh built snapshot carrying the probed flag.
        state.selectAdjacent(SlotDir.RIGHT)
        assertEquals(
            false,
            state.snapshot.value.hapticsAvailable,
            "a device with no motor must surface hapticsAvailable = false so the render shows the visual fallback",
        )
    }

    // ── 6. a single brick on the ground produces exactly one BrickView at the right cell/material ──

    @Test
    fun `a committed brick appears once in the snapshot at its cell with its material`() {
        val card = gatherCard("solo", Glyph.ARROW_CENTER)
        val state = GameState(
            engine = PhysicsEngine(),
            classifier = GestureClassifier(cards = listOf(card)),
            haptics = ThuruummmHaptics(FakeMotor()) { 0L },
            cardsById = mapOf(card.id to card),
        )

        assertNotNull(state.playStream(gatherStream()))
        val views = state.snapshot.value.bricks
        assertEquals(1, views.size, "exactly one BrickView for one placement")
        val view = views.single()
        assertEquals(Cell(0, 0), view.cell, "the BrickView sits at the ground working slot")
        assertEquals(tappyMaterial, view.material, "the BrickView carries the physics Material")
        assertEquals(Glyph.ARROW_CENTER, view.glyph, "the BrickView carries the recovered glyph")
    }
}
