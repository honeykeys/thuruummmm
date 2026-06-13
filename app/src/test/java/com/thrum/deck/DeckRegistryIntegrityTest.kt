package com.thrum.deck

import com.thuruummm.physics.Material
import com.thrum.haptics.Haptic
import com.thrum.haptics.Note
import com.thrum.haptics.Primitive
import com.thrum.haptics.haptic
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADVERSARIAL — registry integrity.
 *
 * Attack surface: the structural contract that DECK.CARDS is the single add-a-gesture surface.
 * Every test here is designed to expose a regression in the invariants the deck docblock
 * claims to enforce. The happy-path suite (DeckTest) already covers the basic property read;
 * these tests attack the corners and edge conditions that can silently break.
 *
 * Tests are JVM-only. No Android runtime. Run:
 *   ./gradlew testDebugUnitTest --tests "*.DeckRegistryIntegrityTest"
 */
class DeckRegistryIntegrityTest {

    // ── Uniqueness attacks ─────────────────────────────────────────────────────────────────

    /**
     * The existing DeckTest checks ids.size == ids.toSet().size.
     * That passes for an empty deck. Force it to fail for a known-bad injection.
     *
     * Attack: two cards with the same id but different data. byId.associateBy silently drops
     * one — byId.size < CARDS.size would be the symptom, but only if someone checks. We
     * assert both that the DECK is clean AND that the structural check itself is tight.
     */
    @Test
    fun `duplicate id injected into a synthetic deck is detected`() {
        val mat = Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        val h = haptic("h") { tick() }
        val cardA = Thuruummm(
            id = "collision",
            gesture = GestureSpec(minFingers = 4, movement = Movement.Gather()),
            material = mat,
            rummmm = h,
            glyph = Glyph.STUB_A,
        )
        val cardB = Thuruummm(
            id = "collision",       // SAME id — this is the attack
            gesture = GestureSpec(minFingers = 5, movement = Movement.Gather()),
            material = mat,
            rummmm = h,
            glyph = Glyph.STUB_B,
        )
        val synthetic = listOf(cardA, cardB)
        val ids = synthetic.map { it.id }
        val byId = synthetic.associateBy { it.id }

        // The collision means byId drops a card silently. CARDS.size > byId.size is the signal.
        assertTrue(
            ids.size != ids.toSet().size,
            "test setup failure: expected duplicate ids in synthetic list"
        )
        assertTrue(
            byId.size < synthetic.size,
            "associateBy must silently drop the collision — byId.size should be smaller"
        )
        // Confirm the real DECK never has this condition:
        assertEquals(
            Deck.CARDS.size,
            Deck.byId.size,
            "real Deck: byId.size != CARDS.size — a collision is silently swallowing a card"
        )
    }

    /**
     * Glyph uniqueness — the existing test checks glyphs.toSet().size == glyphs.size.
     * This is the correct check, BUT it does NOT catch the case where two cards share a glyph
     * AND that glyph is a STUB slot.
     *
     * Attack: enumerate every glyph value in Glyph that appears in CARDS and assert each
     * appears exactly once, including STUB_* entries. A card authored with STUB_A while
     * another card already uses STUB_A should fail.
     */
    @Test
    fun `every glyph entry in Glyph enum used by the deck appears exactly once`() {
        val glyphCounts = mutableMapOf<Glyph, Int>()
        for (card in Deck.CARDS) {
            glyphCounts[card.glyph] = (glyphCounts[card.glyph] ?: 0) + 1
        }
        val duplicates = glyphCounts.filter { it.value > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "duplicate glyphs found in Deck.CARDS — each card must own a unique glyph: $duplicates"
        )
    }

    /**
     * byId is a derived view. If CARDS is mutated after byId is computed (e.g., a lazy init
     * that re-evaluates CARDS), byId can become stale.
     *
     * Attack: read byId twice and check it is referentially stable and equals the fresh
     * associateBy — it must not diverge on repeated access.
     */
    @Test
    fun `byId is stable across multiple reads`() {
        val first = Deck.byId
        val second = Deck.byId
        assertEquals(first, second, "byId is not stable — it diverges between reads")
        // Also check against the ground truth:
        assertEquals(
            Deck.CARDS.associateBy { it.id },
            first,
            "byId does not match a fresh associateBy — CARDS and byId are out of sync"
        )
    }

    /**
     * The registry must be non-empty. An object whose CARDS list is accidentally empty
     * satisfies every property test vacuously (no cards → no violations). If init fails
     * silently and CARDS = emptyList(), the whole game ships without cards.
     *
     * Attack: assert a minimum count. Any regression that empties the list fails here.
     */
    @Test
    fun `deck is non-empty — vacuous property-test trap`() {
        assertTrue(
            Deck.CARDS.isNotEmpty(),
            "Deck.CARDS is empty — all property tests are vacuously passing"
        )
    }

    /**
     * The minimum card count is 9 (tappy + twisty + 7 swipeys per ARCHITECTURE.md §7 C2).
     * An author who accidentally removes a stub card while editing should see this fail.
     */
    @Test
    fun `deck contains at least 9 cards — the MVP floor`() {
        assertTrue(
            Deck.CARDS.size >= 9,
            "Deck.CARDS has only ${Deck.CARDS.size} cards; MVP floor is 9 (ARCHITECTURE.md §7 C2)"
        )
    }

    // ── Facet-completeness attacks ─────────────────────────────────────────────────────────

    /**
     * Every card must have a non-null, non-blank id. The Thuruummm init checks isNotBlank()
     * but only at construction. Attack: confirm no card in the real deck has a whitespace-only
     * id that might have slipped past if the check was accidentally removed.
     */
    @Test
    fun `no card has a whitespace-only id`() {
        for (card in Deck.CARDS) {
            assertTrue(
                card.id.trim().isNotEmpty(),
                "card at index ${Deck.CARDS.indexOf(card)} has a whitespace-only id"
            )
        }
    }

    /**
     * All five facets must be present (Kotlin data class guarantees this by construction —
     * but we also confirm the haptic is structurally populated, not a zero-note composed haptic
     * that compiled silently. A Wave with empty timings is also invalid.
     *
     * Attack: a haptic built with haptic("label") {} (no notes) compiles fine but produces a
     * Composed with notes = emptyList(). HapticEngine.compileComposed returns null for it,
     * meaning the card fires no rummmm haptic. That is silent breakage.
     */
    @Test
    fun `no card carries a zero-note Composed haptic`() {
        for (card in Deck.CARDS) {
            when (val h = card.rummmm) {
                is Haptic.Composed -> assertTrue(
                    h.notes.isNotEmpty(),
                    "${card.id}: Composed haptic has zero notes — HapticEngine will silently drop it"
                )
                is Haptic.Wave -> {
                    assertTrue(h.timings.isNotEmpty(), "${card.id}: Wave haptic has empty timings")
                    assertEquals(
                        h.timings.size,
                        h.amplitudes.size,
                        "${card.id}: Wave timings.size != amplitudes.size — malformed Wave"
                    )
                }
            }
        }
    }

    /**
     * Every Note in a Composed haptic must have scale in 0..1.
     * HapticEngine.compileComposed calls n.scale.coerceIn(0f, 1f) — so an out-of-range scale
     * does not crash; it silently clamps. But a scale of 0.0 means the note is inaudible/unfelt,
     * which is likely a copy-paste error. Attack: reject scales outside (0..1].
     * (Scale = 0 is clamped but effectively silent; the lower bound is exclusive.)
     */
    @Test
    fun `all Composed haptic notes have scale in range zero exclusive to one inclusive`() {
        for (card in Deck.CARDS) {
            val h = card.rummmm
            if (h is Haptic.Composed) {
                for ((index, note) in h.notes.withIndex()) {
                    assertTrue(
                        note.scale > 0f,
                        "${card.id} note[$index] (${note.primitive}): scale=${note.scale} is <= 0 — note is silent"
                    )
                    assertTrue(
                        note.scale <= 1f,
                        "${card.id} note[$index] (${note.primitive}): scale=${note.scale} > 1 — will silently clamp"
                    )
                }
            }
        }
    }

    /**
     * Every Note's delayMs must be >= 0. A negative delay is undefined behaviour in the
     * Android Composition API; it may crash or be silently ignored.
     */
    @Test
    fun `all Composed haptic notes have non-negative delayMs`() {
        for (card in Deck.CARDS) {
            val h = card.rummmm
            if (h is Haptic.Composed) {
                for ((index, note) in h.notes.withIndex()) {
                    assertTrue(
                        note.delayMs >= 0,
                        "${card.id} note[$index] (${note.primitive}): delayMs=${note.delayMs} is negative"
                    )
                }
            }
        }
    }

    /**
     * Every Note's primitive must be a known Primitive enum entry. This is trivially true for
     * notes built via the DSL, but could be broken by a raw Note() construction with a
     * primitive ordinal that does not exist. If Primitive ever gets refactored to hold an
     * arbitrary Int id, this test catches a regressed mapping.
     *
     * Attack: confirm every Primitive used in a note is present in Primitive.entries.
     */
    @Test
    fun `all Composed haptic notes reference known Primitive enum entries`() {
        val known = Primitive.entries.toSet()
        for (card in Deck.CARDS) {
            val h = card.rummmm
            if (h is Haptic.Composed) {
                for ((index, note) in h.notes.withIndex()) {
                    assertTrue(
                        note.primitive in known,
                        "${card.id} note[$index]: primitive ${note.primitive} is not in Primitive.entries"
                    )
                }
            }
        }
    }

    // ── Material boundary attacks ──────────────────────────────────────────────────────────

    /**
     * Material has five stats. All are unconstrained by the Deck (design rule: no value
     * constraints on card parameters). BUT the physics engine will misbehave for extreme
     * values — specifically, a material with weight > strength cannot support even itself.
     *
     * This is NOT a structural violation; the design rule allows it. But it is worth surfacing
     * as a signal test that Karl can choose to gate or ignore. We mark it clearly so a
     * future reviewer understands the intent.
     *
     * Attack: report any card whose weight > strength (it will buckle the moment it is placed
     * under any load from above). This is not a hard failure — it is a design-attention flag.
     */
    @Test
    fun `design signal — no card has weight strictly greater than strength (would self-buckle under load)`() {
        val suspicious = Deck.CARDS.filter { it.material.weight > it.material.strength }
        // This test is a DESIGN SIGNAL. If Karl intentionally adds a self-buckling material
        // (e.g., a fuse brick), change this to assertTrue(suspicious.isEmpty(), ...) to hard-fail,
        // or remove this test. For now we assert and explain.
        assertTrue(
            suspicious.isEmpty(),
            "cards whose weight > strength — will buckle under their own imposed load: " +
                suspicious.joinToString { it.id }
        )
    }

    /**
     * Material.brittleness must be in 0.0..1.0 (enforced by Material.init).
     * Attack: confirm the init check cannot be bypassed by a deserialization path or
     * a copy() that skips init. Kotlin data class copy() re-invokes init.
     */
    @Test
    fun `Material init rejects brittleness out of range`() {
        assertFailsWith<IllegalArgumentException>("brittleness > 1 must throw") {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 1.001)
        }
        assertFailsWith<IllegalArgumentException>("brittleness < 0 must throw") {
            Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = -0.001)
        }
    }

    /**
     * Material.weight and strength must be > 0 (enforced by Material.init).
     * Attack: zero and negative values.
     */
    @Test
    fun `Material init rejects non-positive weight and strength`() {
        assertFailsWith<IllegalArgumentException>("weight = 0 must throw") {
            Material(weight = 0.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("weight negative must throw") {
            Material(weight = -1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("strength = 0 must throw") {
            Material(weight = 1.0, strength = 0.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("strength negative must throw") {
            Material(weight = 1.0, strength = -1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        }
    }

    /**
     * Material.copy() re-runs init checks. Confirm that a valid Material cannot be mutated
     * into an invalid one via copy() without throwing.
     */
    @Test
    fun `Material copy rejects invalid values — copy re-runs init`() {
        val valid = Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.5)
        assertFailsWith<IllegalArgumentException>("copy with weight=0 must throw") {
            valid.copy(weight = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("copy with brittleness > 1 must throw") {
            valid.copy(brittleness = 2.0)
        }
    }

    // ── Structural contract attacks ────────────────────────────────────────────────────────

    /**
     * Thuruummm.init rejects a blank id. Attack: confirm it is enforced.
     */
    @Test
    fun `Thuruummm rejects blank id`() {
        val mat = Material(weight = 1.0, strength = 1.0, cantilever = 0, shatterThreshold = 1, brittleness = 0.0)
        val h = haptic("h") { tick() }
        assertFailsWith<IllegalArgumentException>("blank id must throw") {
            Thuruummm(
                id = "",
                gesture = GestureSpec(minFingers = 4, movement = Movement.Gather()),
                material = mat,
                rummmm = h,
                glyph = Glyph.STUB_A,
            )
        }
        assertFailsWith<IllegalArgumentException>("whitespace id must throw") {
            Thuruummm(
                id = "   ",
                gesture = GestureSpec(minFingers = 4, movement = Movement.Gather()),
                material = mat,
                rummmm = h,
                glyph = Glyph.STUB_A,
            )
        }
    }

    /**
     * GestureSpec.init rejects minFingers < 1. Attack: confirm boundary enforcement.
     */
    @Test
    fun `GestureSpec rejects minFingers less than 1`() {
        assertFailsWith<IllegalArgumentException>("minFingers=0 must throw") {
            GestureSpec(minFingers = 0, movement = Movement.Gather())
        }
        assertFailsWith<IllegalArgumentException>("minFingers=-1 must throw") {
            GestureSpec(minFingers = -1, movement = Movement.Gather())
        }
    }

    /**
     * GestureSpec.init rejects tolerance outside 0..1. Attack: boundary and just-outside-boundary.
     */
    @Test
    fun `GestureSpec rejects tolerance outside 0 to 1`() {
        assertFailsWith<IllegalArgumentException>("tolerance=1.001 must throw") {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 1.001f)
        }
        assertFailsWith<IllegalArgumentException>("tolerance=-0.001 must throw") {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = -0.001f)
        }
        // Exact boundary must NOT throw:
        GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 0f)  // should not throw
        GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 1f)  // should not throw
    }
}
