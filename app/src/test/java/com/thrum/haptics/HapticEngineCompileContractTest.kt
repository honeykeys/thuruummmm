package com.thrum.haptics

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — the [HapticEngine] compile/gate logic, and a load-bearing TESTABILITY finding.
 *
 * ── The finding (read this first) ───────────────────────────────────────────────────────────────
 *
 * HapticEngine CANNOT be unit-tested at the motor seam as written, on this toolchain:
 *   1. Its constructor takes a Context and does `context.getSystemService(Vibrator::class.java)` —
 *      a stubbed android.jar call that throws on the JVM.
 *   2. compile()/compileComposed() call `VibrationEffect.startComposition()`, `.addPrimitive(...)`,
 *      `.compose()`, and `VibrationEffect.createWaveform(...)` — all android.jar stubs that throw
 *      `RuntimeException("Stub!")` on the JVM.
 *   3. No mocking library is on the test classpath. app/build.gradle.kts declares only `junit` and
 *      `kotlin-test-junit` for tests — no MockK, no Robolectric, no android-all. So neither the
 *      Vibrator nor the VibrationEffect statics can be faked at runtime.
 *
 * ARCHITECTURE.md §6 (haptics row) claims: "HapticEngine takes a Vibrator behind a thin interface;
 * inject a fake." THE CODE DOES NOT DO THIS. HapticEngine holds a concrete `Vibrator?` it builds
 * itself and calls VibrationEffect statics inline. The thin-interface seam exists one level UP
 * ([HapticSink], around the whole engine), not around the Vibrator. Therefore the engine's
 * compile/gate/fallback decisions — capability gating, the fallback substitution chain, the
 * empty-composition guard, scale coercion — are UNREACHABLE by a JVM unit test.
 *
 * RECOMMENDATION (the refactor that makes the engine attackable): introduce
 *
 *     interface VibratorPort {
 *         fun hasVibrator(): Boolean
 *         fun hasAmplitudeControl(): Boolean
 *         fun arePrimitivesSupported(vararg ids: Int): BooleanArray
 *         fun getPrimitiveDurations(vararg ids: Int): IntArray
 *         fun play(effect: VibrationSpec)          // VibrationSpec = a pure description, not android's VibrationEffect
 *         fun cancel()
 *     }
 *
 * with the android.os.Vibrator/VibrationEffect translation living in a single AndroidVibratorPort,
 * and HapticEngine depending on VibratorPort. Then a FakeVibratorPort captures the COMPILED
 * description (primitives chosen after fallback, scales, the empty-drop decision) and these tests
 * become real assertions against the engine instead of against a mirror.
 *
 * ── What this file does until that seam exists ──────────────────────────────────────────────────
 *
 * It attacks the engine's DECISIONS through the only types the engine and the test share — the pure
 * [Haptic]/[Note]/[Primitive] model and the verified android contracts those decisions must honour:
 *
 *  A. The fallback-chain decision: the table HapticEngine.resolve walks is reproduced from the
 *     source as the contract under test; we attack it for the properties the engine relies on
 *     (every primitive resolves to SOMETHING on a CLICK/TICK-only motor; no fallback cycles;
 *     no primitive falls back to itself; the API floor of every primitive is cleared by minSdk 31).
 *  B. The empty-composition crash: VibrationEffect.startComposition().compose() with zero primitives
 *     throws IllegalStateException (verified: AOSP CTS VibrationEffectTest
 *     testComposeEmptyCompositionIsInvalid). HapticEngine.compileComposed guards this with
 *     `if (added > 0) compose() else null`. We pin the exact conditions under which `added` is 0 so
 *     a regression that drops the guard (or miscounts) is caught the moment the engine is wired to a
 *     port.
 *  C. The scale-coercion masking: the engine does `n.scale.coerceIn(0f, 1f)` — silently swallowing
 *     an out-of-range author scale instead of surfacing it. addPrimitive itself requires scale in
 *     0.0f..1.0f inclusive (verified). We document that the coercion hides author error, and pin the
 *     boundary the engine MUST keep clamping to so a future "remove the coerce" change is caught.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.HapticEngineCompileContractTest"
 */
class HapticEngineCompileContractTest {

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // A. The fallback chain (mirror of HapticEngine.fallbacks — kept in lockstep with the source).
    //    If the source table changes, these tests are the contract that must change with it; a
    //    silent drift breaks substitution on real hardware (a primitive resolving to nothing = a
    //    silently dropped note = a thinner haptic the player feels as "did my gesture even land?").
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private val fallbacks: Map<Primitive, List<Primitive>> = mapOf(
        Primitive.THUD to listOf(Primitive.CLICK, Primitive.LOW_TICK, Primitive.TICK),
        Primitive.SPIN to listOf(Primitive.QUICK_RISE, Primitive.TICK, Primitive.CLICK),
        Primitive.LOW_TICK to listOf(Primitive.TICK, Primitive.CLICK),
        Primitive.QUICK_RISE to listOf(Primitive.CLICK, Primitive.TICK),
        Primitive.SLOW_RISE to listOf(Primitive.QUICK_RISE, Primitive.CLICK, Primitive.TICK),
        Primitive.QUICK_FALL to listOf(Primitive.TICK, Primitive.CLICK),
        Primitive.CLICK to listOf(Primitive.TICK),
        Primitive.TICK to listOf(Primitive.CLICK),
    )

    /** Reproduces HapticEngine.resolve against an arbitrary "what this motor supports" predicate. */
    private fun resolve(p: Primitive, supported: (Primitive) -> Boolean): Primitive? {
        if (supported(p)) return p
        for (alt in fallbacks[p].orEmpty()) if (supported(alt)) return alt
        return null
    }

    @Test
    fun `every primitive has a fallback entry — none can fall through to a silent drop unexpectedly`() {
        for (p in Primitive.entries) {
            assertTrue(
                fallbacks.containsKey(p),
                "Primitive.$p has no fallback chain. On a motor lacking it, the note is silently dropped — the haptic thins with no warning. Every primitive the Deck can author needs a substitution path.",
            )
        }
    }

    @Test
    fun `no primitive lists itself as a fallback — that would be a no-op loop`() {
        for ((p, chain) in fallbacks) {
            assertFalse(p in chain, "Primitive.$p lists itself as a fallback — a wasted, confusing entry that resolves nothing new")
        }
    }

    @Test
    fun `on a CLICK-and-TICK-only motor every primitive still resolves to something playable`() {
        // The realistic worst case: a budget motor exposing only the two API-30 ticks. The whole
        // point of the fallback table is that NO note is dropped on such hardware. A single primitive
        // resolving to null here means a card's rummmm goes partly silent on cheap phones.
        val cheapMotor = setOf(Primitive.CLICK, Primitive.TICK)
        for (p in Primitive.entries) {
            val resolved = resolve(p) { it in cheapMotor }
            assertTrue(
                resolved != null,
                "Primitive.$p resolves to NOTHING on a CLICK/TICK-only motor — the note is dropped. Every primitive must reach CLICK or TICK through its chain.",
            )
            assertTrue(resolved in cheapMotor, "Primitive.$p resolved to ${resolved} which this motor does not support — the fallback walk is wrong")
        }
    }

    @Test
    fun `on a motor supporting NOTHING every primitive resolves to null — the drop path is reachable`() {
        // The other extreme: a (broken / non-haptic) motor. resolve must return null, NOT loop
        // forever and NOT throw. This is the branch compileComposed relies on to skip a note.
        for (p in Primitive.entries) {
            assertNull(resolve(p) { false }, "with zero supported primitives, resolve(Primitive.$p) must be null so the note is cleanly skipped")
        }
    }

    @Test
    fun `the fallback chain terminates for every primitive — no cyclic substitution`() {
        // Walk each chain following first-supported-only semantics over the full enum, asserting we
        // never revisit. A cycle (e.g. CLICK->TICK->CLICK if a future edit broke it) on a motor
        // missing the whole chain would still terminate via resolve's bounded loop, but a cycle is a
        // design smell that masks the true substitution intent. Assert chains are acyclic by content.
        for ((p, chain) in fallbacks) {
            assertEquals(chain.size, chain.toSet().size, "Primitive.$p has a repeated entry in its fallback chain — ambiguous, likely a typo")
        }
    }

    @Test
    fun `minSdk 31 clears the API floor of every primitive the engine can author`() {
        // app/build.gradle.kts: minSdk = 31. Every Primitive.minApi must be <= 31 or the app
        // references a constant that does not exist on its own minimum device and crashes at class
        // load. THUD/SPIN/LOW_TICK are API 31; the rest API 30. A future minSdk drop to 30 would
        // make THUD/SPIN/LOW_TICK illegal — this test is the tripwire.
        val minSdk = 31
        for (p in Primitive.entries) {
            assertTrue(
                p.minApi <= minSdk,
                "Primitive.$p requires API ${p.minApi} but minSdk is $minSdk — referencing it crashes on the app's own minimum device",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // B. The empty-composition crash guard.
    //    Verified: VibrationEffect.startComposition().compose() with no primitives throws
    //    IllegalStateException (AOSP CTS VibrationEffectTest#testComposeEmptyCompositionIsInvalid).
    //    HapticEngine.compileComposed must return null (NOT call compose()) whenever every note was
    //    dropped, else the toy crashes when a Composed haptic meets a motor that supports none of
    //    its primitives.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors compileComposed's add-counting decision: how many notes survive resolution. The engine
     * calls compose() iff this is > 0. We attack the inputs that drive it to 0.
     */
    private fun survivingNotes(notes: List<Note>, supported: (Primitive) -> Boolean): Int =
        notes.count { resolve(it.primitive) { p -> supported(p) } != null }

    @Test
    fun `a Composed haptic authored with zero notes would compose an empty effect — must be guarded`() {
        // An author error: haptic("x") {} with no notes. survivingNotes == 0 regardless of motor, so
        // the engine MUST short-circuit to null and never reach compose(). If the guard is dropped,
        // this is an instant on-device IllegalStateException.
        val empty = Haptic.Composed("empty", emptyList())
        assertEquals(0, survivingNotes(empty.notes, supported = { true }), "a no-note Composed has nothing to survive — compose() would throw IllegalStateException")
    }

    @Test
    fun `a Composed haptic whose every primitive is unsupported drops to zero notes — must be guarded`() {
        // Every note authored, but the motor supports NOTHING (broken / non-haptic device). resolve
        // returns null for each, so added == 0, so compose() must be skipped. This is the exact path
        // the `if (added > 0) ... else null` guard protects. A regression dropping that guard crashes
        // precisely on the cheapest hardware — the users least able to tolerate it.
        val rich: Haptic.Composed = HapticLibrary.ember // quickRise + spin — two notes
        assertEquals(0, survivingNotes(rich.notes) { false }, "with zero motor support, all notes drop; compileComposed MUST return null, not compose() an empty effect")
    }

    @Test
    fun `a Composed haptic with at least one supported primitive survives and composes`() {
        // The positive control: on a CLICK/TICK motor, every library haptic keeps >= 1 note (proven
        // by the fallback test above), so compose() is correctly reached. If this were 0, a real card
        // would be silent on a real phone.
        val cheapMotor = setOf(Primitive.CLICK, Primitive.TICK)
        for (h in HapticLibrary.blocks) {
            if (h is Haptic.Composed) {
                assertTrue(
                    survivingNotes(h.notes) { it in cheapMotor } > 0,
                    "${h.label} drops to zero notes on a CLICK/TICK motor — it would be silent on a budget phone, and worse, compose() would throw",
                )
            }
        }
    }

    @Test
    fun `the uniform thur survives on the cheapest motor — the one beat that must never be silent`() {
        // The thur is the universal "your gesture landed" pulse — if it is ever silent the player
        // cannot tell a placement registered (DESIGN.md: eyes-off legibility). It is a single TICK
        // note; TICK is API 30 and on a TICK-less motor falls back to CLICK. It must NEVER drop to 0.
        val thur = CommitHaptics.THUR
        assertTrue(survivingNotes(thur.notes) { it == Primitive.CLICK } > 0, "thur must survive on a CLICK-only motor (TICK->CLICK fallback)")
        assertTrue(survivingNotes(thur.notes) { it == Primitive.TICK } > 0, "thur must survive on a TICK-only motor (its native primitive)")
        assertTrue(survivingNotes(thur.notes) { false } == 0, "on a no-haptic motor even the thur drops — the visual fallback must then carry the confirmation (capabilities.hasVibrator == false path)")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // C. Scale coercion masks author error.
    //    addPrimitive requires scale in 0.0f..1.0f inclusive (verified). The engine does
    //    `n.scale.coerceIn(0f, 1f)`. That keeps the app from crashing, but it SILENTLY corrects an
    //    out-of-range author scale — a 1.5f velocity becomes 1.0f with no signal. We pin the clamp
    //    so a future "trust the author, drop the coerce" change (which WOULD crash addPrimitive on a
    //    >1 scale) is caught, and we document that the masking is a design smell.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Mirrors the engine's coercion. */
    private fun engineScale(authored: Float): Float = authored.coerceIn(0f, 1f)

    @Test
    fun `the engine clamps an out-of-range authored scale into the android-legal band`() {
        // Whatever an author writes, what reaches addPrimitive must be in [0,1] inclusive — the
        // verified addPrimitive precondition. The engine's coerce is what guarantees this today.
        for (authored in floatArrayOf(-1f, -0.001f, 0f, 0.5f, 1f, 1.001f, 5f, Float.MAX_VALUE)) {
            val s = engineScale(authored)
            assertTrue(s in 0f..1f, "authored scale $authored must clamp into [0,1] before addPrimitive (it requires 0.0f..1.0f inclusive); got $s")
        }
    }

    @Test
    fun `coercion silently rewrites an over-1 authored scale — documented masking, pinned for regression`() {
        // This is the smell: a card author who fat-fingers spin(scale = 1.5f) gets 1.0f with no
        // error. The Note construction does not reject it (Note has no init), and the engine hides it.
        // We pin the behaviour so it is a CONSCIOUS contract, not an accident: if someone removes the
        // coerce, addPrimitive(1.5f) throws IllegalArgumentException on-device. Better long-term: the
        // Deck's malformed-card test should reject Note.scale > 1 at construction (see
        // DeckMalformedCardRejectionTest `Note with scale above 1 is rejected`, currently red).
        assertEquals(1f, engineScale(1.5f), "1.5f authored is masked to 1.0f by the engine's coerce")
        assertEquals(0f, engineScale(-0.2f), "-0.2f authored is masked to 0.0f — note becomes minimum-perceivable, not the negative the author wrote")
    }

    @Test
    fun `a NaN authored scale is the one coercion can't save — surfaced, not swallowed`() {
        // Float.coerceIn(0f, 1f) on NaN returns NaN (NaN compares false against both bounds). So an
        // NaN scale would pass straight to addPrimitive, which would reject it on-device. The engine's
        // coerce does NOT guard NaN. This test surfaces that gap: NaN is the hole in the clamp.
        // Expected to expose the gap — if the engine later guards NaN (e.g. coerce then
        // `.takeUnless { it.isNaN() }`), update this assertion.
        val coerced = engineScale(Float.NaN)
        assertTrue(coerced.isNaN(), "coerceIn does NOT sanitise NaN — an NaN authored scale reaches addPrimitive and is rejected on-device. The clamp is not a complete guard; Note construction should reject NaN scale upstream.")
    }
}
