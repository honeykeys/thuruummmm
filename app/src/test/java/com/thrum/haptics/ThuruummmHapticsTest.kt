package com.thrum.haptics

import com.thuruummm.physics.Brick
import com.thuruummm.physics.Cell
import com.thuruummm.physics.CollapseResult
import com.thuruummm.physics.Grid
import com.thuruummm.physics.Material
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial test of the play-side facade ([ThuruummmHaptics]): the two-beat firing ORDER and
 * the crash-wins-the-motor COALESCING, with no device. We inject a [FakeHapticSink] that records
 * every call and a controllable clock, exactly as ARCHITECTURE.md §6's haptics row prescribes.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.ThuruummmHapticsTest"
 */
class ThuruummmHapticsTest {

    /** Records what would have been played, in order — the seam the architecture asks for. */
    private class FakeHapticSink : HapticSink {
        data class Played(val label: String, val priority: Boolean)
        val plays = mutableListOf<Played>()
        var cancels = 0
        override val capabilities = Capabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            supported = Primitive.entries.associateWith { true },
            durationsMs = Primitive.entries.associateWith { 10 },
        )
        override fun play(haptic: Haptic, priority: Boolean) {
            plays += Played(haptic.label, priority)
        }
        override fun cancel() { cancels++ }
    }

    private val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    private fun smallCollapse() = CollapseResult(
        fell = listOf(Brick(0, pebble, Cell(0, 0))),
        rings = 1,
        materials = setOf(pebble),
        finalGrid = Grid(),
    )

    private val cardRummmm: Haptic = haptic("twisty-rummmm") { spin(scale = 0.7f) }

    // ── the heartbeat: thur (uniform) then rummmm (card character), in that order ───────────────

    @Test
    fun `a normal placement fires thur then the card rummmm, in order, no priority`() {
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { 0L })

        haptics.thur()             // beat 1 — fired on flourish, before physics
        haptics.rummmm(cardRummmm) // beat 2 — fired after physics.place() returns

        assertEquals(
            listOf("thur", "twisty-rummmm"),
            sink.plays.map { it.label },
            "the commit must read as thur → rummmm, in that order",
        )
        assertTrue(sink.plays.none { it.priority }, "commit beats are never priority")
        assertEquals(0, sink.cancels, "a normal placement never cancels the motor")
    }

    @Test
    fun `the thur is uniform across cards — same effect label regardless of which card follows`() {
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { 0L })
        val cardA: Haptic = haptic("tappy-rummmm") { tick(scale = 0.55f) }
        val cardB: Haptic = haptic("swipey-left-rummmm") { quickRise(scale = 0.5f) }

        haptics.thur(); haptics.rummmm(cardA)
        haptics.thur(); haptics.rummmm(cardB)

        val thurs = sink.plays.filter { it.label == "thur" }
        assertEquals(2, thurs.size, "every placement fires exactly one thur")
        // The thur is authored once in CommitHaptics; both are literally the same constant.
        assertTrue(thurs.all { it.label == CommitHaptics.THUR.label }, "thur is the one uniform beat")
    }

    // ── the reward: THRUUMMMM priority-locks the motor (cancel-first) ───────────────────────────

    @Test
    fun `a collapse fires the thruummm with priority, cancelling whatever was buzzing`() {
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { 0L })

        haptics.thur()
        haptics.rummmm(cardRummmm)
        haptics.thruummm(smallCollapse())

        val last = sink.plays.last()
        assertEquals("thruummm", last.label, "the collapse haptic is the THRUUMMMM")
        assertTrue(last.priority, "the THRUUMMMM must be priority — it cancels rapid taps, crash wins the motor")
        assertEquals(1, sink.cancels, "priority play cancels the motor exactly once for the crash")
    }

    // ── coalescing: commit beats during an in-flight crash are dropped ──────────────────────────

    @Test
    fun `commit beats are coalesced away while a thruummm owns the motor`() {
        var clock = 1_000L
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { clock })

        haptics.thruummm(smallCollapse())   // locks the motor for the crash's full duration
        val playsAfterCrash = sink.plays.size

        // A frantic tap lands DURING the crash (clock barely advances).
        clock += 5
        haptics.thur()
        haptics.rummmm(cardRummmm)

        assertEquals(
            playsAfterCrash,
            sink.plays.size,
            "no commit beat may play while the THRUUMMMM still owns the motor — the crash is not cut short",
        )
    }

    @Test
    fun `once the crash has elapsed, commit beats fire again`() {
        var clock = 0L
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { clock })

        haptics.thruummm(smallCollapse())
        val crashLockMs = Thruummm.forCollapse(smallCollapse()).totalDurationMs()

        // Jump past the end of the crash.
        clock += crashLockMs + 1
        haptics.thur()

        assertTrue(
            sink.plays.any { it.label == "thur" },
            "after the crash has fully played, the next placement's thur fires normally",
        )
    }

    @Test
    fun `cancel releases the motor lock immediately`() {
        var clock = 0L
        val sink = FakeHapticSink()
        val haptics = ThuruummmHaptics(sink, nowMs = { clock })

        haptics.thruummm(smallCollapse())
        haptics.cancel()                    // e.g. screen exit / reset
        clock += 1                          // still well within the original lock window
        haptics.thur()

        assertTrue(sink.plays.any { it.label == "thur" }, "cancel() frees the motor; the next thur fires")
        assertFalse(sink.plays.last().priority)
    }
}
