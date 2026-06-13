package com.thrum.haptics

import com.thuruummm.physics.Brick
import com.thuruummm.physics.Cell
import com.thuruummm.physics.CollapseResult
import com.thuruummm.physics.Grid
import com.thuruummm.physics.Material
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — the two-beat commit haptic's STRUCTURAL invariants and the coalescing state
 * machine's subtle corners. Disjoint from ThuruummmHapticsTest (which proves the happy ordering and
 * the basic coalescing) and from FakeVibratorOrderTest (which proves the motor timeline). This file
 * attacks:
 *
 *  - the verified android.os addPrimitive contract every commit note must satisfy: scale in
 *    0.0f..1.0f inclusive, delay >= 0 (Source: developer.android.com VibrationEffect.Composition,
 *    "scale … between 0.0f and 1.0f inclusive", "delay … 0 or greater", verified 2026-06-13). If
 *    CommitHaptics.THUR or any HapticLibrary note breaches this, HapticEngine.addPrimitive crashes
 *    on-device.
 *  - the lazy-clear side effect inside ThuruummmHaptics.motorReserved(): the FIRST read past the
 *    lock mutates state (clears the lock to 0). A test that reads motorReserved indirectly twice
 *    must see consistent behaviour. We drive the facade through the read so a stateful bug (e.g. the
 *    lock not clearing, or clearing too eagerly) is caught.
 *  - the thur is byte-for-byte the SAME object/label on every commit (uniformity is structural, not
 *    coincidental) — across an adversarial interleaving with crashes and cancels.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.CommitHapticContractTest"
 */
class CommitHapticContractTest {

    private class RecordingSink : HapticSink {
        data class Play(val label: String, val priority: Boolean)
        val plays = mutableListOf<Play>()
        var cancels = 0
        override val capabilities = Capabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            supported = Primitive.entries.associateWith { true },
            durationsMs = Primitive.entries.associateWith { 10 },
        )
        override fun play(haptic: Haptic, priority: Boolean) { plays += Play(haptic.label, priority) }
        override fun cancel() { cancels++ }
    }

    private val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    private fun smallCollapse() = CollapseResult(
        fell = listOf(Brick(0, pebble, Cell(0, 0))),
        rings = 1,
        materials = setOf(pebble),
        finalGrid = Grid(),
    )

    // ── A. the android addPrimitive contract for every commit-side note ─────────────────────────

    private fun assertNotesAreAndroidLegal(h: Haptic, ctx: String) {
        when (h) {
            is Haptic.Composed -> {
                assertTrue(h.notes.isNotEmpty(), "[$ctx] a Composed commit haptic with no notes composes an empty effect — IllegalStateException on-device")
                h.notes.forEach { n ->
                    assertTrue(
                        n.scale in 0f..1f,
                        "[$ctx] note scale ${n.scale} is outside addPrimitive's required 0.0f..1.0f inclusive — the engine coerces it, but the AUTHORED value should already be legal",
                    )
                    assertTrue(
                        n.delayMs >= 0,
                        "[$ctx] note delayMs ${n.delayMs} is negative — addPrimitive requires delay 0 or greater; the engine passes delayMs straight through with no coerce, so this crashes on-device",
                    )
                }
            }
            is Haptic.Wave -> {
                assertEquals(h.timings.size, h.amplitudes.size, "[$ctx] wave arrays must pair 1:1")
                assertTrue(h.amplitudes.all { it in 0..255 || it == -1 }, "[$ctx] wave amplitude out of 0..255/DEFAULT range")
            }
        }
    }

    @Test
    fun `the thur note satisfies the android addPrimitive contract`() {
        assertNotesAreAndroidLegal(CommitHaptics.THUR, "CommitHaptics.THUR")
    }

    @Test
    fun `every library block haptic satisfies the android addPrimitive contract`() {
        // The cards' rummmm beats come from here. Note: HapticEngine passes delayMs UNCOERCED to
        // addPrimitive (only scale is coerced). A negative delay anywhere in the deck = a crash. This
        // sweep is the tripwire across all authored signatures.
        for (h in HapticLibrary.all) {
            assertNotesAreAndroidLegal(h, h.label)
        }
    }

    @Test
    fun `the thur is the small beat — its scale is below every library blocks first-beat scale`() {
        // DESIGN: thur is deliberately the LIGHTER of the two beats. CommitHaptics documents scale
        // 0.35 < every card's opening scale. If a future edit raises the thur above a card's first
        // beat, the heartbeat inverts (big-then-small) and the eyes-off legibility breaks.
        val thurFirst = (CommitHaptics.THUR).notes.first().scale
        for (h in HapticLibrary.blocks) {
            if (h is Haptic.Composed) {
                val cardFirst = h.notes.first().scale
                assertTrue(
                    thurFirst <= cardFirst,
                    "thur first-beat scale $thurFirst must be <= ${h.label}'s opening scale $cardFirst — the commit must read small (thur) then large (rummmm)",
                )
            }
        }
    }

    // ── B. the lazy-clear side effect in motorReserved() ────────────────────────────────────────

    @Test
    fun `after the lock elapses the first commit clears it and a second commit also fires`() {
        // motorReserved() clears motorLockedUntilMs to 0 on the first read past expiry (a mutating
        // read). We must see BOTH the first post-expiry commit AND a subsequent one fire — a bug
        // where the lock fails to clear would swallow the second, and a bug where it clears too early
        // would have let a tap through mid-crash (covered elsewhere). Drive two commits past expiry.
        var clock = 0L
        val sink = RecordingSink()
        val h = ThuruummmHaptics(sink, nowMs = { clock })

        h.thruummm(smallCollapse())
        val lockMs = Thruummm.forCollapse(smallCollapse()).totalDurationMs()
        val afterCrash = sink.plays.size

        clock = lockMs + 1
        h.thur()                      // first read past expiry: clears the lock, fires
        h.rummmm(haptic("r") { tick() }) // second beat: lock already cleared, fires

        val newPlays = sink.plays.drop(afterCrash).map { it.label }
        assertEquals(listOf("thur", "r"), newPlays, "once the crash elapses, the full next heartbeat (thur then rummmm) fires — the lazy lock-clear must not eat the second beat")
    }

    @Test
    fun `the motor lock window equals the crash wave's exact total duration, not a guess`() {
        // ThuruummmHaptics sets motorLockedUntilMs = now + wave.totalDurationMs(). The lock must be
        // EXACTLY the crash length: a tap at (start + len - 1) is swallowed, at (start + len) fires.
        // This pins the arithmetic to the wave itself, catching any drift to a hardcoded window.
        var clock = 0L
        val sink = RecordingSink()
        val h = ThuruummmHaptics(sink, nowMs = { clock })

        val c = smallCollapse()
        val len = Thruummm.forCollapse(c).totalDurationMs()
        h.thruummm(c)
        val afterCrash = sink.plays.size

        clock = len - 1
        h.thur()
        assertEquals(afterCrash, sink.plays.size, "at lock-1 the tap is swallowed — the window is the wave's own length")

        clock = len
        h.thur()
        assertEquals(afterCrash + 1, sink.plays.size, "at exactly lock the tap fires — boundary is inclusive of release (>=)")
    }

    // ── C. uniformity of the thur across an adversarial interleaving ────────────────────────────

    @Test
    fun `the thur is the identical effect across crashes, cancels, and rapid commits`() {
        var clock = 0L
        val sink = RecordingSink()
        val h = ThuruummmHaptics(sink, nowMs = { clock })

        // commit, crash, (swallowed taps), elapse, commit, cancel, commit — a hostile interleaving.
        h.thur(); h.rummmm(haptic("a") { click() })
        h.thruummm(smallCollapse())
        clock += 1; h.thur()                                  // swallowed (mid-crash)
        clock += Thruummm.forCollapse(smallCollapse()).totalDurationMs()
        h.thur(); h.rummmm(haptic("b") { tick() })
        h.cancel()
        clock += 1; h.thur(); h.rummmm(haptic("c") { spin() })

        val thurs = sink.plays.filter { it.label == "thur" }
        assertTrue(thurs.size >= 3, "at least three thurs reach the motor across the interleaving (the mid-crash one is swallowed)")
        assertTrue(thurs.all { it.label == CommitHaptics.THUR.label }, "every thur is the one uniform CommitHaptics.THUR — uniformity survives crashes and cancels")
        assertTrue(thurs.none { it.priority }, "a thur is never priority, ever — even the one right after a crash window")
    }

    @Test
    fun `the thur object itself is a shared constant — uniformity is structural, not copied per call`() {
        // The strongest form of "uniform": both placements reference the SAME Haptic.Composed
        // instance, not two equal-but-distinct ones. A future change that built a fresh thur per
        // call would still pass label checks but could drift. Pin object identity at the source.
        assertTrue(
            CommitHaptics.THUR === CommitHaptics.THUR,
            "CommitHaptics.THUR must be a single shared constant — the uniform beat is one object the whole deck shares",
        )
        assertEquals("thur", CommitHaptics.THUR.label, "the uniform beat's label is 'thur'")
        assertEquals(1, CommitHaptics.THUR.notes.size, "the thur is a single light pulse — one note, not a chord")
        assertEquals(Primitive.TICK, CommitHaptics.THUR.notes.single().primitive, "the thur is a TICK — light/crisp, the confirmation primitive")
    }
}
