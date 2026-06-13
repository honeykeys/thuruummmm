package com.thrum.deck

import com.thuruummm.physics.Material
import com.thrum.haptics.Haptic
import com.thrum.haptics.Note
import com.thrum.haptics.Primitive
import com.thrum.haptics.haptic
import com.thrum.haptics.wave
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — malformed card rejection.
 *
 * Attack surface: every structural invariant a card must satisfy. These tests confirm that
 * the init checks fire at construction time, that no invalid object can slip through into
 * Deck.CARDS, and that the structural contract is enforced uniformly regardless of how a
 * card is built (direct construction, copy(), or deserialization-proxy simulation).
 *
 * Each test is designed to FAIL on the implementation under test. If the implementation
 * removes an init check, the corresponding test here will pass construction when it should
 * throw, and assertFailsWith will catch that the test's own assertion did NOT throw — failing
 * the test.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.DeckMalformedCardRejectionTest"
 */
class DeckMalformedCardRejectionTest {

    // ── Thuruummm.id invariants ────────────────────────────────────────────────────────────

    @Test
    fun `blank id is rejected at construction`() {
        val mat = mat()
        val h = haptic("h") { tick() }
        assertFailsWith<IllegalArgumentException>("empty string id must throw") {
            Thuruummm(
                id = "",
                gesture = spec(Movement.Gather()),
                material = mat,
                rummmm = h,
                glyph = Glyph.STUB_A,
            )
        }
    }

    @Test
    fun `whitespace-only id is rejected at construction`() {
        val mat = mat()
        val h = haptic("h") { tick() }
        assertFailsWith<IllegalArgumentException>("tab-only id must throw") {
            Thuruummm(
                id = "\t",
                gesture = spec(Movement.Gather()),
                material = mat,
                rummmm = h,
                glyph = Glyph.STUB_A,
            )
        }
        assertFailsWith<IllegalArgumentException>("mixed whitespace id must throw") {
            Thuruummm(
                id = "  \n  ",
                gesture = spec(Movement.Gather()),
                material = mat,
                rummmm = h,
                glyph = Glyph.STUB_A,
            )
        }
    }

    /**
     * Attack: copy() of a valid card with a blank id must also throw — copy() re-invokes init.
     */
    @Test
    fun `copy of valid card with blank id throws`() {
        val valid = validCard("source-card", Glyph.STUB_A)
        assertFailsWith<IllegalArgumentException>("copy with blank id must re-invoke init and throw") {
            valid.copy(id = "")
        }
    }

    // ── GestureSpec invariants ─────────────────────────────────────────────────────────────

    @Test
    fun `minFingers zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GestureSpec(minFingers = 0, movement = Movement.Gather())
        }
    }

    @Test
    fun `minFingers negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GestureSpec(minFingers = -100, movement = Movement.Gather())
        }
    }

    @Test
    fun `tolerance exactly at boundary 0 is accepted`() {
        // Should NOT throw:
        GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 0f)
    }

    @Test
    fun `tolerance exactly at boundary 1 is accepted`() {
        // Should NOT throw:
        GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 1f)
    }

    @Test
    fun `tolerance just above 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 1.0001f)
        }
    }

    @Test
    fun `tolerance just below 0 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = -0.0001f)
        }
    }

    /**
     * Float NaN must be rejected for tolerance. NaN comparisons in range checks behave
     * unexpectedly (NaN in 0f..1f returns false, so require() would fire — correct behaviour —
     * but only if the range check is `in 0f..1f` rather than `<= 1 && >= 0` with NaN
     * propagation quirks). Confirm NaN is rejected.
     */
    @Test
    fun `tolerance NaN is rejected`() {
        assertFailsWith<IllegalArgumentException>("NaN tolerance must throw") {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = Float.NaN)
        }
    }

    /**
     * Float positive infinity must be rejected for tolerance.
     */
    @Test
    fun `tolerance positive infinity is rejected`() {
        assertFailsWith<IllegalArgumentException>("Infinity tolerance must throw") {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = Float.POSITIVE_INFINITY)
        }
    }

    /**
     * copy() of a GestureSpec with invalid tolerance must throw.
     */
    @Test
    fun `GestureSpec copy with invalid tolerance throws`() {
        val valid = GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 0.5f)
        assertFailsWith<IllegalArgumentException>("copy with tolerance>1 must throw") {
            valid.copy(tolerance = 1.1f)
        }
        assertFailsWith<IllegalArgumentException>("copy with tolerance<0 must throw") {
            valid.copy(tolerance = -0.1f)
        }
    }

    // ── Material invariants ────────────────────────────────────────────────────────────────

    @Test
    fun `material weight zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 0.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material weight negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = -0.001, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material strength zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = 0.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material strength negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = -1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material cantilever negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = 1.0, cantilever = -1, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material shatterThreshold negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = -1, brittleness = 0.0)
        }
    }

    @Test
    fun `material brittleness exactly 0 is accepted`() {
        // Should NOT throw:
        Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
    }

    @Test
    fun `material brittleness exactly 1 is accepted`() {
        // Should NOT throw:
        Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 1.0)
    }

    @Test
    fun `material brittleness above 1 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 1.0001)
        }
    }

    @Test
    fun `material brittleness below 0 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = -0.0001)
        }
    }

    /**
     * Double NaN for weight must be rejected. Double NaN > 0 is false, so require() fires —
     * but only if the implementation uses > 0 and not some other form. Confirm.
     */
    @Test
    fun `material weight NaN is rejected`() {
        assertFailsWith<IllegalArgumentException>("NaN weight must throw") {
            Material(weight = Double.NaN, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material strength NaN is rejected`() {
        assertFailsWith<IllegalArgumentException>("NaN strength must throw") {
            Material(weight = 1.0, strength = Double.NaN, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material brittleness NaN is rejected`() {
        assertFailsWith<IllegalArgumentException>("NaN brittleness must throw — NaN in 0..1 is false") {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = Double.NaN)
        }
    }

    @Test
    fun `material weight positive infinity is rejected`() {
        // Positive infinity is > 0, so the weight check would PASS — but infinity would break
        // physics calculations. If Material does not reject Infinity, this test fails HERE
        // (assertFailsWith does not catch the exception), surfacing the gap in the implementation.
        // This is intentionally adversarial: it may expose a missing check.
        assertFailsWith<IllegalArgumentException>("Infinity weight should be rejected") {
            Material(weight = Double.POSITIVE_INFINITY, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    @Test
    fun `material strength positive infinity is rejected`() {
        assertFailsWith<IllegalArgumentException>("Infinity strength should be rejected") {
            Material(weight = 1.0, strength = Double.POSITIVE_INFINITY, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    // ── Wave haptic structural invariants ─────────────────────────────────────────────────

    /**
     * A Haptic.Wave with mismatched timings and amplitudes arrays is structurally malformed.
     * HapticEngine passes them directly to VibrationEffect.createWaveform — Android will crash
     * or throw if the arrays differ in length. The Haptic type does not enforce this in its
     * init (it is a plain class, not a data class). Attack: if a future author enforces
     * parity at construction, this test confirms the check exists. If not, the test exposes
     * the gap — the malformed Wave will be created silently.
     *
     * Current state: Wave has no init check on array sizes. This test is expected to FAIL
     * (the construction does NOT throw) — confirming the gap is real and open.
     * When the implementation adds the check, this test will pass.
     *
     * NOTE: this is a deliberately "red" test — it exposes a real missing invariant.
     */
    @Test
    fun `Wave with mismatched timings and amplitudes is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Wave with timings.size != amplitudes.size must throw — Android createWaveform will crash otherwise"
        ) {
            wave(
                "mismatched",
                100L to 128,
                // Only 1 timing but we inject a second amplitude below via the factory
            ).also { w ->
                // The wave() factory enforces parity. We bypass it with direct construction.
                // This tests whether Haptic.Wave itself guards on construction.
            }
            // Direct construction bypass — the real attack:
            Haptic.Wave(
                label = "mismatched-direct",
                timings = longArrayOf(100L, 200L),      // 2 timings
                amplitudes = intArrayOf(128),            // 1 amplitude — mismatch
            )
        }
    }

    /**
     * A Wave with an amplitude value outside 0..255 is rejected by Android's createWaveform.
     * Confirm that either the Wave init or the compiler catches this.
     * Same "red test" as above — exposes a gap if Wave has no amplitude range check.
     */
    @Test
    fun `Wave with amplitude above 255 is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Wave amplitude > 255 must throw — VibrationEffect.createWaveform will reject it"
        ) {
            Haptic.Wave(
                label = "bad-amplitude",
                timings = longArrayOf(100L),
                amplitudes = intArrayOf(256),   // 256 is out of Android's 0..255 range
            )
        }
    }

    /**
     * A Wave with negative amplitude is rejected by Android's createWaveform.
     */
    @Test
    fun `Wave with negative amplitude is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Wave negative amplitude must throw"
        ) {
            Haptic.Wave(
                label = "negative-amplitude",
                timings = longArrayOf(100L),
                amplitudes = intArrayOf(-1),
            )
        }
    }

    // ── Note structural invariants ─────────────────────────────────────────────────────────

    /**
     * A Note with negative delayMs is structurally invalid. The DSL does not reject it —
     * Note is a data class with no init. Attack: if the implementation adds an init check,
     * this confirms it. If not, the test surfaces the gap.
     */
    @Test
    fun `Note with negative delayMs is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Note with negative delayMs should throw — undefined in Android Composition API"
        ) {
            Note(primitive = Primitive.TICK, scale = 0.5f, delayMs = -1)
        }
    }

    /**
     * A Note with scale > 1 is structurally invalid. HapticEngine coerces it via coerceIn,
     * which masks the error. The Note itself should reject out-of-range scale at construction.
     */
    @Test
    fun `Note with scale above 1 is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Note scale > 1 must throw — HapticEngine's silent coercion masks author errors"
        ) {
            Note(primitive = Primitive.TICK, scale = 1.001f, delayMs = 0)
        }
    }

    /**
     * A Note with scale exactly 0 is rejected — a zero-scale note is silent but compilable.
     * The Haptic layer should reject it at construction to prevent silent no-ops.
     */
    @Test
    fun `Note with scale zero is rejected`() {
        assertFailsWith<IllegalArgumentException>(
            "Note scale=0 must throw — produces a silent note that silently degrades the haptic"
        ) {
            Note(primitive = Primitive.TICK, scale = 0f, delayMs = 0)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private fun mat() = Material(
        weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0
    )

    private fun spec(movement: Movement) = GestureSpec(minFingers = 4, movement = movement)

    private fun validCard(id: String, glyph: Glyph) = Thuruummm(
        id = id,
        gesture = spec(Movement.Gather()),
        material = mat(),
        rummmm = haptic("$id-rummmm") { tick(scale = 0.5f) },
        glyph = glyph,
    )
}
