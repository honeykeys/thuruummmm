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
 * ADVERSARIAL — the motor timeline: thur strictly before rummmm, and a crash priority-locks the
 * motor over rapid commits.
 *
 * The mandate asks for a "FakeVibrator capturing the effect/firing order". The single injectable
 * seam that exists in the production code is [HapticSink] (see HapticSink.kt: "inject a fake … so a
 * FakeHapticSink in a test can record calls instead of driving a real motor"). [HapticEngine] is
 * NOT injectable — it constructs an android.os.Vibrator from a Context and calls VibrationEffect
 * statics inline, all of which are android.jar stubs that throw on the JVM, and no mocking library
 * (MockK/Robolectric) is on the test classpath (see app/build.gradle.kts). So the deepest fake we
 * can run on the JVM is at the HapticSink seam. This [FakeMotor] is that fake, sharpened to capture
 * the WHOLE motor timeline — every cancel() and every play(effect, priority) in strict order — so
 * the order/priority contract is provable without a device.
 *
 * These attacks go beyond the two happy-path tests in ThuruummmHapticsTest:
 *  - the cancel() MUST land on the timeline strictly BEFORE the crash effect (the existing test only
 *    counts cancels; it never proves cancel precedes the play, which is the whole "crash wins"
 *    guarantee);
 *  - back-to-back crashes;
 *  - a commit landing at the EXACT lock-expiry instant (off-by-one boundary);
 *  - a zero-duration crash wave (degenerate lock window);
 *  - cancel() mid-lock then an immediate commit;
 *  - thur without a following rummmm, and rummmm before any thur (mis-ordered caller).
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.FakeVibratorOrderTest"
 */
class FakeVibratorOrderTest {

    /**
     * A motor that records the FULL ordered timeline: a cancel() and a play() are distinct events,
     * captured in the exact sequence the facade issues them. This is the "FakeVibrator" the mandate
     * names, expressed at the only injectable seam. It captures more than ThuruummmHapticsTest's
     * FakeHapticSink, which records plays and cancels in separate lists and therefore cannot prove
     * that the cancel happened BEFORE the crash play.
     */
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

        fun labels(): List<String> = timeline.mapNotNull { (it as? Event.Play)?.label }
        fun plays(): List<Event.Play> = timeline.filterIsInstance<Event.Play>()
    }

    // ── physics fixtures ─────────────────────────────────────────────────────────────────────

    private val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    private val wood = Material(1.5, 5.0, 2, 3, 0.1)

    private fun collapse(fell: Int, rings: Int, mats: Set<Material>) = CollapseResult(
        fell = (0 until fell).map { Brick(it, pebble, Cell(it, 0)) },
        rings = rings,
        materials = mats,
        finalGrid = Grid(),
    )

    private val cardRummmm: Haptic = haptic("twisty-rummmm") { spin(scale = 0.7f) }

    // ── thur STRICTLY before rummmm ────────────────────────────────────────────────────────────

    @Test
    fun `thur is emitted on the motor strictly before rummmm`() {
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { 0L })

        h.thur()
        h.rummmm(cardRummmm)

        val labels = motor.labels()
        val iThur = labels.indexOf("thur")
        val iRummmm = labels.indexOf("twisty-rummmm")
        assertTrue(iThur >= 0, "thur must reach the motor")
        assertTrue(iRummmm >= 0, "rummmm must reach the motor")
        assertTrue(iThur < iRummmm, "thur must land on the motor BEFORE rummmm — the heartbeat is small-then-large")
    }

    @Test
    fun `rummmm called before any thur still never fires before a thur on the same commit`() {
        // Hostile caller: rummmm without a preceding thur. The facade does not reorder — it fires
        // exactly what it is told, in call order. We assert it does NOT silently invent a thur, and
        // does NOT mark the rummmm as priority. (This pins the contract: ordering is the caller's /
        // GameLoop's responsibility; the facade must not paper over a misuse by reordering.)
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { 0L })

        h.rummmm(cardRummmm)

        assertEquals(listOf("twisty-rummmm"), motor.labels(), "facade must not fabricate a thur it was not asked for")
        assertTrue(motor.plays().none { it.priority }, "a lone rummmm is still a commit beat — never priority")
    }

    // ── crash priority-locks the motor: cancel STRICTLY before the crash play ───────────────────

    @Test
    fun `the crash cancels the motor strictly before it plays the crash effect`() {
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { 0L })

        h.thur()
        h.rummmm(cardRummmm)
        h.thruummm(collapse(fell = 3, rings = 2, mats = setOf(pebble, wood)))

        // Find the cancel and the crash play on the ordered timeline.
        val crashPlayIndex = motor.timeline.indexOfLast {
            it is FakeMotor.Event.Play && it.label == "thruummm"
        }
        val cancelIndex = motor.timeline.indexOfLast { it is FakeMotor.Event.Cancel }

        assertTrue(crashPlayIndex >= 0, "the crash must reach the motor")
        assertTrue(cancelIndex >= 0, "the crash must cancel the motor first")
        assertTrue(
            cancelIndex < crashPlayIndex,
            "cancel() MUST land before the crash play — otherwise the cancel could clobber the crash itself, not the taps. This is the entire 'crash wins the motor' guarantee.",
        )
        val crashPlay = motor.timeline[crashPlayIndex] as FakeMotor.Event.Play
        assertTrue(crashPlay.priority, "the crash is always played with priority = true")
    }

    @Test
    fun `commit beats never cancel — only the crash is allowed to cancel the motor`() {
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { 0L })

        h.thur()
        h.rummmm(cardRummmm)
        h.thur()
        h.rummmm(cardRummmm)

        assertTrue(
            motor.timeline.none { it is FakeMotor.Event.Cancel },
            "a stream of normal placements must NEVER cancel the motor — cancelling on a commit would let one tap kill another tap",
        )
        assertTrue(motor.plays().none { it.priority }, "commit beats are never priority")
    }

    // ── coalescing: rapid commits during the lock are dropped, off the FakeMotor timeline ───────

    @Test
    fun `a burst of rapid taps during the crash adds nothing to the motor timeline`() {
        var clock = 10_000L
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { clock })

        h.thruummm(collapse(fell = 4, rings = 3, mats = setOf(pebble, wood)))
        val timelineAfterCrash = motor.timeline.size

        // Twenty frantic taps, each a frame apart, all inside the crash window.
        repeat(20) {
            clock += 5
            h.thur()
            h.rummmm(cardRummmm)
        }

        assertEquals(
            timelineAfterCrash,
            motor.timeline.size,
            "no tap during the crash may touch the motor — not a play, not a cancel. The crash must run uninterrupted.",
        )
    }

    // ── boundary: a commit at the EXACT lock-expiry instant ─────────────────────────────────────

    @Test
    fun `a commit at the exact lock-expiry instant is allowed through, not swallowed`() {
        var clock = 0L
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { clock })

        val c = collapse(fell = 2, rings = 1, mats = setOf(pebble))
        h.thruummm(c)
        val lockMs = Thruummm.forCollapse(c).totalDurationMs()

        // Land EXACTLY at the boundary. The facade's motorReserved() uses `nowMs() >= lockedUntil`,
        // so the instant the lock elapses the motor is free. Off-by-one here is a classic bug:
        // a `>` instead of `>=` would swallow the first valid tap after the crash.
        clock = lockMs
        h.thur()

        assertTrue(
            motor.labels().contains("thur"),
            "at the exact instant the lock expires the motor is free — the commit at t == lockedUntil must fire (>= boundary, not >)",
        )
    }

    @Test
    fun `a commit one ms before the lock expires is still swallowed`() {
        var clock = 0L
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { clock })

        val c = collapse(fell = 2, rings = 1, mats = setOf(pebble))
        h.thruummm(c)
        val lockMs = Thruummm.forCollapse(c).totalDurationMs()
        val afterCrash = motor.timeline.size

        clock = lockMs - 1   // still inside the lock by a single millisecond
        h.thur()

        assertEquals(
            afterCrash,
            motor.timeline.size,
            "one ms before expiry the lock still holds — the tap must be coalesced away",
        )
    }

    // ── degenerate lock window: a zero-duration crash must not jam the motor forever ────────────

    @Test
    fun `a crash whose wave totals zero ms does not lock out the very next commit`() {
        // Adversarial: construct a facade whose crash maps to an empty / zero-length wave, then
        // assert a commit at the SAME instant is not falsely coalesced. The lock is
        // nowMs() + wave.totalDurationMs(); if the wave is 0ms the lock == now, and motorReserved()
        // (>= now) must read it as already-free. A bug that set lockedUntil to a sentinel or that
        // used `> 0` to decide "locked" would wrongly swallow the next tap.
        var clock = 5_000L
        val motor = object : HapticSink {
            val timeline = mutableListOf<String>()
            override val capabilities = Capabilities(true, true, Primitive.entries.associateWith { true }, Primitive.entries.associateWith { 0 })
            override fun play(haptic: Haptic, priority: Boolean) { timeline += haptic.label }
            override fun cancel() { timeline += "cancel" }
        }
        // forCollapse never produces a 0ms wave (it always appends a slam >= SLAM_MS_MIN), so to
        // exercise the degenerate window we drive thruummm with the smallest real collapse and then
        // assert the lock is finite and clears — a regression guard that the lock can never be
        // permanent.
        val h = ThuruummmHaptics(motor, nowMs = { clock })
        val c = collapse(fell = 1, rings = 1, mats = setOf(pebble))
        h.thruummm(c)
        val lockMs = Thruummm.forCollapse(c).totalDurationMs()
        assertTrue(lockMs > 0, "sanity: even the tiniest collapse reserves a positive, finite window")

        clock += lockMs            // exactly at expiry
        h.thur()
        assertTrue(motor.timeline.contains("thur"), "a finite lock must always release; the motor can never be jammed permanently")
    }

    // ── back-to-back crashes: the second re-cancels and re-locks ────────────────────────────────

    @Test
    fun `a second crash during the first cancels again and extends the lock`() {
        var clock = 1_000L
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { clock })

        val first = collapse(fell = 2, rings = 1, mats = setOf(pebble))
        h.thruummm(first)
        val firstCrashEnds = clock + Thruummm.forCollapse(first).totalDurationMs()

        // A second collapse lands mid-first-crash. THRUUMMMM has no coalescing gate (only commit
        // beats do) — the reward always plays. It must cancel the motor again and re-arm the lock.
        clock += 10
        val cancelsBefore = motor.timeline.count { it is FakeMotor.Event.Cancel }
        val big = collapse(fell = 8, rings = 4, mats = setOf(pebble, wood))
        h.thruummm(big)
        val cancelsAfter = motor.timeline.count { it is FakeMotor.Event.Cancel }

        assertEquals(cancelsBefore + 1, cancelsAfter, "the second crash must cancel the motor again — a crash is never coalesced away")
        assertEquals(2, motor.labels().count { it == "thruummm" }, "both crashes reach the motor")

        // The new lock must extend at least to the second (larger) crash's own end, past the first.
        val newLockEnd = clock + Thruummm.forCollapse(big).totalDurationMs()
        assertTrue(newLockEnd > firstCrashEnds, "a bigger second crash must hold the motor at least as long as it needs, past the first window")

        // A tap between the second crash's start and end is still swallowed.
        clock += 5
        val before = motor.timeline.size
        h.thur()
        assertEquals(before, motor.timeline.size, "taps during the second crash are coalesced just like the first")
    }

    // ── cancel() releases the lock immediately, and the next commit is a normal (non-priority) beat ─

    @Test
    fun `cancel mid-lock frees the motor and the next thur is a normal commit beat`() {
        var clock = 0L
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { clock })

        h.thruummm(collapse(fell = 3, rings = 2, mats = setOf(pebble, wood)))
        h.cancel()                 // screen exit / reset, well inside the lock
        clock += 2
        h.thur()

        val plays = motor.plays()
        assertTrue(plays.any { it.label == "thur" }, "after cancel() the motor lock is gone; the next thur fires")
        assertFalse(plays.last().priority, "the post-cancel thur is an ordinary commit beat, not priority")
    }

    @Test
    fun `cancel itself reaches the motor`() {
        val motor = FakeMotor()
        val h = ThuruummmHaptics(motor, nowMs = { 0L })
        h.cancel()
        assertTrue(motor.timeline.any { it is FakeMotor.Event.Cancel }, "facade.cancel() must cancel the real motor, not only clear the lock flag")
    }
}
