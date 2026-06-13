package com.thrum.deck

import com.thuruummm.physics.Material
import com.thrum.haptics.Haptic
import com.thrum.haptics.haptic
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — "adding a card touches only Deck.kt" contract.
 *
 * Karl's load-bearing rule: adding a card = one object in [Deck.CARDS]. Nothing else changes.
 * These tests attack that invariant from every angle:
 *
 *   1. The registry routes through CARDS, never through a hard-coded list anywhere else.
 *   2. byId is the only join point between an id and a card — there is no parallel mapping.
 *   3. A card with a novel Movement subtype does not require changes to any other deck file.
 *   4. A card with all-default GestureSpec values is valid and accepted.
 *   5. A card that reuses an existing Haptic pattern (same notes, different label) is distinct.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*.DeckAddCardContractTest"
 */
class DeckAddCardContractTest {

    private val baseMaterial = Material(
        weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0
    )

    // ── byId is the ONLY join point ────────────────────────────────────────────────────────

    /**
     * If a future implementor adds a secondary map (e.g., byGlyph, byMovementType) that is
     * built from CARDS at init time, it must stay in sync. Attack: confirm byId is exactly
     * CARDS.associateBy — not some separately authored map.
     *
     * Failure here means someone hardcoded a parallel registry.
     */
    @Test
    fun `byId is exactly CARDS associateBy id — no parallel registry exists`() {
        val expected = Deck.CARDS.associateBy { it.id }
        assertEquals(
            expected,
            Deck.byId,
            "byId diverges from CARDS.associateBy — a parallel, manually-maintained registry exists"
        )
    }

    /**
     * Attack the negative case: byId must NOT contain an id that is not in CARDS.
     * This is impossible if byId is derived, but becomes possible if someone manually edits
     * byId to add an alias or a deprecated id mapping.
     */
    @Test
    fun `byId contains no id absent from CARDS`() {
        val cardIds = Deck.CARDS.map { it.id }.toSet()
        for ((id, _) in Deck.byId) {
            assertTrue(
                id in cardIds,
                "byId contains id='$id' which is NOT in CARDS — byId is not purely derived"
            )
        }
    }

    /**
     * The "add a card" contract means that for any card that IS in CARDS, byId[card.id]
     * returns that SAME object (by value equality, since Thuruummm is a data class).
     */
    @Test
    fun `every card in CARDS is retrievable by its id via byId and is equal`() {
        for (card in Deck.CARDS) {
            val found = Deck.byId[card.id]
            assertNotNull(found, "byId[${card.id}] returned null — lookup broken")
            assertEquals(card, found, "byId[${card.id}] returned a different card — not equal to CARDS entry")
        }
    }

    /**
     * byId must return null for unknown ids. If it throws, or returns a default, the contract
     * is violated.
     */
    @Test
    fun `byId returns null for unknown ids`() {
        assertNull(Deck.byId["__non_existent_id_xyzzy__"], "byId must return null for unknown id")
        assertNull(Deck.byId[""], "byId must return null for empty string id")
        assertNull(Deck.byId[" "], "byId must return null for whitespace id")
    }

    // ── Novel Movement subtypes are accepted without file changes ──────────────────────────

    /**
     * DESIGN RULE: "the gesture spec must be a GENERAL descriptor of any hand-choreography."
     * A Movement.Custom card must compile and instantiate without modifying any other file.
     * This test creates a custom-movement card and confirms the card class accepts it.
     *
     * Attack: if the card or spec class has an exhaustive when() on Movement types, a Custom
     * movement would cause a compilation error elsewhere. Here we just confirm instantiation
     * works — the test itself IS the proof of the contract.
     */
    @Test
    fun `a card with Movement Custom instantiates without requiring other file changes`() {
        val custom = Thuruummm(
            id = "test-custom-movement",
            gesture = GestureSpec(
                minFingers = 4,
                movement = Movement.Custom(
                    recognizerId = "test-recognizer",
                    description = "adversarial test: ensures Custom movement is structurally accepted"
                ),
                tolerance = 0.2f,
            ),
            material = baseMaterial,
            rummmm = haptic("test-custom-rummmm") { tick(scale = 0.5f) },
            glyph = Glyph.STUB_H,   // not adding to real deck; STUB_H used as a safe slot
        )
        assertEquals("test-custom-movement", custom.id)
        assertTrue(custom.gesture.movement is Movement.Custom)
    }

    /**
     * A card with Translate but NO directionRad (omitted = null, meaning "any direction")
     * must be structurally valid. The ARCHITECTURE says a card that omits directionRad matches
     * any swipe. Confirm the GestureSpec does not reject null directionRad.
     */
    @Test
    fun `a Translate card with null directionRad is structurally valid`() {
        val card = Thuruummm(
            id = "test-any-swipe",
            gesture = GestureSpec(
                minFingers = 4,
                movement = Movement.Translate(minDriftPx = 80f, directionRad = null),
            ),
            material = baseMaterial,
            rummmm = haptic("any-swipe-rummmm") { click(scale = 0.7f) },
            glyph = Glyph.STUB_G,
        )
        assertNull((card.gesture.movement as Movement.Translate).directionRad)
    }

    /**
     * A RotateContract card with clockwise = null (either direction) must be structurally valid.
     */
    @Test
    fun `a RotateContract card with null clockwise is structurally valid`() {
        val card = Thuruummm(
            id = "test-either-direction-twist",
            gesture = GestureSpec(
                minFingers = 4,
                movement = Movement.RotateContract(clockwise = null),
            ),
            material = baseMaterial,
            rummmm = haptic("either-twist-rummmm") { spin(scale = 0.6f) },
            glyph = Glyph.STUB_F,
        )
        assertNull((card.gesture.movement as Movement.RotateContract).clockwise)
    }

    // ── Cards with identical haptic patterns but different labels are distinct ─────────────

    /**
     * Two cards can use the same primitive sequence if Karl wants the same feel for different
     * gestures. The Haptic.Composed equality is by value (data class). Confirm two such cards
     * are NOT equal as Thuruummm entries (they differ in id, gesture, glyph).
     *
     * Attack: if Thuruummm equality was based only on haptic content (not all fields), duplicate
     * detection via Set would falsely collapse different cards.
     */
    @Test
    fun `two cards with identical haptic notes but different ids are distinct in a Set`() {
        val sharedPattern = haptic("shared") { tick(scale = 0.5f) }
        val cardA = Thuruummm(
            id = "test-card-alpha",
            gesture = GestureSpec(minFingers = 4, movement = Movement.Gather()),
            material = baseMaterial,
            rummmm = sharedPattern,
            glyph = Glyph.STUB_E,
        )
        val cardB = Thuruummm(
            id = "test-card-beta",
            gesture = GestureSpec(minFingers = 5, movement = Movement.Gather()),
            material = baseMaterial,
            rummmm = sharedPattern,
            glyph = Glyph.STUB_D,
        )
        assertNotEquals(cardA, cardB, "cards with different ids must not be equal")
        assertEquals(2, setOf(cardA, cardB).size, "Set collapsed cards with different ids")
    }

    // ── GestureSpec structural defaults are valid ──────────────────────────────────────────

    /**
     * A GestureSpec with all default values (minFingers=4, tolerance=0.15f) and any Movement
     * must be valid. Attack: confirm a card authored entirely from defaults compiles and passes
     * validation, proving no undocumented non-default requirements exist.
     */
    @Test
    fun `a card authored entirely from GestureSpec defaults is structurally valid`() {
        val card = Thuruummm(
            id = "test-defaults",
            gesture = GestureSpec(movement = Movement.Gather()),   // all other fields are default
            material = baseMaterial,
            rummmm = haptic("defaults-rummmm") { tick() },
            glyph = Glyph.STUB_C,
        )
        assertEquals(4, card.gesture.minFingers)
        assertEquals(0.15f, card.gesture.tolerance)
        assertTrue(card.gesture.movement is Movement.Gather)
    }

    // ── Wave haptic is also a valid rummmm ────────────────────────────────────────────────

    /**
     * The rummmm type is sealed as Haptic. A Wave rummmm is a valid alternative to Composed.
     * Attack: confirm a card with a Wave haptic is accepted and its timings/amplitudes survive
     * round-trip through the card.
     */
    @Test
    fun `a card with a Wave rummmm is structurally valid`() {
        val waveHaptic = com.thrum.haptics.wave(
            "wave-test",
            100L to 128,
            50L to 64,
            100L to 200,
        )
        val card = Thuruummm(
            id = "test-wave-card",
            gesture = GestureSpec(minFingers = 4, movement = Movement.Gather()),
            material = baseMaterial,
            rummmm = waveHaptic,
            glyph = Glyph.STUB_B,
        )
        assertTrue(card.rummmm is Haptic.Wave)
        val w = card.rummmm as Haptic.Wave
        assertEquals(3, w.timings.size)
        assertEquals(3, w.amplitudes.size)
    }

    // ── Extreme but valid GestureSpec values are accepted ─────────────────────────────────

    /**
     * Design rule: no value constraints on card parameters — only structural contract.
     * Attack: a card with extreme-but-valid GestureSpec values (minFingers=1, tolerance=1.0f)
     * must not be rejected. The classifier may classify it loosely, but the card is well-formed.
     */
    @Test
    fun `card with extreme valid GestureSpec values is accepted`() {
        val card = Thuruummm(
            id = "test-extreme-spec",
            gesture = GestureSpec(minFingers = 1, movement = Movement.Gather(), tolerance = 1.0f),
            material = Material(
                weight = 1.0, strength = 99.0, cantilever = 100, shatterThreshold = 999, brittleness = 0.0
            ),
            rummmm = haptic("extreme-rummmm") { tick(scale = 0.01f) },
            glyph = Glyph.STUB_A,
        )
        assertEquals(1, card.gesture.minFingers)
        assertEquals(1.0f, card.gesture.tolerance)
    }

    // ── Dir constants are within the expected radian range ────────────────────────────────

    /**
     * Dir provides convenience constants for Deck authoring. They must all be in [0, 2π).
     * An authoring error that sets a direction to an angle outside this range would not fail
     * at construction (Translate accepts any Float) but would cause the classifier to
     * mismatch directions. Attack: validate all Dir constants.
     */
    @Test
    fun `all Dir constants are in the range 0 inclusive to 2pi exclusive`() {
        val twoPi = (2 * Math.PI).toFloat()
        val dirs = mapOf(
            "RIGHT"      to Dir.RIGHT,
            "DOWN_RIGHT" to Dir.DOWN_RIGHT,
            "DOWN"       to Dir.DOWN,
            "DOWN_LEFT"  to Dir.DOWN_LEFT,
            "LEFT"       to Dir.LEFT,
            "UP_LEFT"    to Dir.UP_LEFT,
            "UP"         to Dir.UP,
            "UP_RIGHT"   to Dir.UP_RIGHT,
        )
        for ((name, value) in dirs) {
            assertTrue(
                value >= 0f,
                "Dir.$name = $value is negative — out of [0, 2π) radian range"
            )
            assertTrue(
                value < twoPi,
                "Dir.$name = $value is >= 2π — out of [0, 2π) radian range"
            )
        }
    }

    /**
     * All Dir constants must be distinct — if two directions have the same value, the
     * classifier cannot distinguish them and one swipey card can never fire.
     */
    @Test
    fun `all Dir constants are distinct`() {
        val all = listOf(
            Dir.RIGHT, Dir.DOWN_RIGHT, Dir.DOWN, Dir.DOWN_LEFT,
            Dir.LEFT, Dir.UP_LEFT, Dir.UP, Dir.UP_RIGHT,
        )
        assertEquals(all.size, all.toSet().size, "Dir has duplicate direction values: $all")
    }

    /**
     * Swipey cards in the real deck must use only values from the Dir object (or equivalent
     * angles), and their directions must be angularly separated enough that maxDirectionErrorRad
     * (0.4 rad ≈ 23°) leaves no ambiguous overlap.
     *
     * Two adjacent 45°-spaced Dir constants are 45° apart (π/4 ≈ 0.785 rad). With a max error
     * of 0.4 rad on each side, the "acceptance cone" is 0.8 rad wide. For adjacent directions
     * separated by π/4 ≈ 0.785 rad, the cones overlap by 0.015 rad — dangerously close.
     * This test exposes that for human review even if it is not a hard failure.
     *
     * Attack: compute the minimum angular separation between all swipey card directions and
     * confirm it exceeds maxDirectionErrorRad * 2 (the combined cone width).
     */
    @Test
    fun `swipey direction pairs have adequate angular separation for the given error tolerance`() {
        val swipeys = Deck.CARDS
            .filter { it.gesture.movement is Movement.Translate }
            .map { card ->
                val t = card.gesture.movement as Movement.Translate
                Triple(card.id, t.directionRad, t.maxDirectionErrorRad)
            }
            .filter { it.second != null }
            .map { Triple(it.first, it.second!!, it.third) }

        if (swipeys.size < 2) return   // not enough cards to compare; trivially ok

        for (i in swipeys.indices) {
            for (j in (i + 1) until swipeys.size) {
                val (idA, dirA, errA) = swipeys[i]
                val (idB, dirB, errB) = swipeys[j]
                val rawDiff = Math.abs(dirA - dirB)
                val angular = minOf(rawDiff, (2 * Math.PI).toFloat() - rawDiff)
                val combinedCone = errA + errB
                // We do not hard-fail — the design may intentionally accept overlap for
                // distinct gestures. But we surface any pair within the combined cone.
                assertTrue(
                    angular > combinedCone,
                    "Swipey cards '$idA' (${dirA} rad) and '$idB' (${dirB} rad) are only " +
                        "${angular} rad apart — combined cone is ${combinedCone} rad. " +
                        "Ambiguous classification possible. Tighten maxDirectionErrorRad or increase angular gap."
                )
            }
        }
    }
}
