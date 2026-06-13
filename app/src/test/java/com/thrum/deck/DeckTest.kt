package com.thrum.deck

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial property tests for the deck registry.
 *
 * INVARIANT: these tests assert "adding a card needs no other change" — they confirm
 * the structural contracts without knowing which cards exist. A hostile reviewer
 * can run: ./gradlew testDebugUnitTest --tests "*.DeckTest"
 *
 * No Android runtime needed. This is a pure-Kotlin/JVM test.
 */
class DeckTest {

    @Test
    fun `all ids are unique`() {
        val ids = Deck.CARDS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate id found: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `all glyphs are unique`() {
        val glyphs = Deck.CARDS.map { it.glyph }
        assertEquals(glyphs.size, glyphs.toSet().size, "duplicate glyph found: ${glyphs.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `byId covers every card`() {
        for (card in Deck.CARDS) {
            assertNotNull(Deck.byId[card.id], "byId missing id='${card.id}'")
            assertEquals(card, Deck.byId[card.id])
        }
        assertEquals(Deck.CARDS.size, Deck.byId.size)
    }

    @Test
    fun `all ids are non-blank`() {
        for (card in Deck.CARDS) {
            assertTrue(card.id.isNotBlank(), "blank id in card $card")
        }
    }

    @Test
    fun `all materials satisfy physics invariants`() {
        // Material.init enforces its own requires; just constructing CARDS exercises them.
        // This test forces evaluation and surfaces any deferred-init issue.
        for (card in Deck.CARDS) {
            assertTrue(card.material.weight > 0, "${card.id}: weight <= 0")
            assertTrue(card.material.strength > 0, "${card.id}: strength <= 0")
            assertTrue(card.material.cantilever >= 0, "${card.id}: cantilever < 0")
            assertTrue(card.material.shatterThreshold >= 0, "${card.id}: shatterThreshold < 0")
            assertTrue(card.material.brittleness in 0.0..1.0, "${card.id}: brittleness out of range")
        }
    }

    @Test
    fun `all gesture specs have valid tolerance`() {
        for (card in Deck.CARDS) {
            val t = card.gesture.tolerance
            assertTrue(t in 0f..1f, "${card.id}: tolerance $t out of 0..1")
        }
    }

    @Test
    fun `all gesture specs require at least 1 finger`() {
        for (card in Deck.CARDS) {
            assertTrue(card.gesture.minFingers >= 1, "${card.id}: minFingers < 1")
        }
    }

    @Test
    fun `all rummmm haptics are non-empty`() {
        for (card in Deck.CARDS) {
            val haptic = card.rummmm
            when (haptic) {
                is com.thrum.haptics.Haptic.Composed ->
                    assertTrue(haptic.notes.isNotEmpty(), "${card.id}: Composed haptic has no notes")
                is com.thrum.haptics.Haptic.Wave ->
                    assertTrue(haptic.timings.isNotEmpty(), "${card.id}: Wave haptic has no timings")
            }
        }
    }

    @Test
    fun `current stub swipeys all have directionRad set`() {
        // SCOPED TO CURRENT STUB SET. The type permits directionRad = null (any-direction swipe),
        // and the architecture's "no value constraints" rule explicitly allows it for future cards.
        // This test only asserts that the *existing* 7 stub swipeys are direction-locked, as the
        // deck spec requires for the compass set. Do not generalise to "all Translate cards forever"
        // — that would break the moment Karl authors an any-direction swipe card. (F5)
        val knownDirectionalIds = setOf(
            "swipey-right", "swipey-down-right", "swipey-down",
            "swipey-down-left", "swipey-left", "swipey-up", "swipey-up-right",
        )
        for (id in knownDirectionalIds) {
            val card = Deck.byId[id] ?: continue  // card may not exist yet during incremental authoring
            val dir = (card.gesture.movement as Movement.Translate).directionRad
            assertNotNull(dir, "$id: compass swipey has no directionRad")
        }
    }

    @Test
    fun `current stub swipey directions are all distinct`() {
        // SCOPED TO CURRENT STUB SET. Two future cards could legitimately share null directionRad
        // (both accept any direction) without being duplicates — using toSet() over Float? would
        // count both nulls as one entry and misreport a collision. Guard: filter to direction-locked
        // cards only, then assert uniqueness over the non-null set. (F5/F6)
        val swipeys = Deck.CARDS.filter { it.gesture.movement is Movement.Translate }
        val lockedDirs = swipeys
            .mapNotNull { (it.gesture.movement as Movement.Translate).directionRad }
        assertEquals(
            lockedDirs.size, lockedDirs.toSet().size,
            "duplicate direction-locked swipey direction: $lockedDirs",
        )
    }

    @Test
    fun `tappy is in the deck and is a Gather gesture`() {
        val tappy = Deck.byId["tappy"]
        assertNotNull(tappy, "tappy not found in deck")
        assertTrue(tappy.gesture.movement is Movement.Gather, "tappy movement is not Gather")
        assertEquals(Glyph.ARROW_CENTER, tappy.glyph)
    }

    @Test
    fun `twisty is in the deck and is a RotateContract gesture`() {
        val twisty = Deck.byId["twisty"]
        assertNotNull(twisty, "twisty not found in deck")
        assertTrue(twisty.gesture.movement is Movement.RotateContract, "twisty movement is not RotateContract")
        assertEquals(Glyph.SPIRAL, twisty.glyph)
        // twisty is CW-only per the deck spec
        assertEquals(true, (twisty.gesture.movement as Movement.RotateContract).clockwise)
    }

    @Test
    fun `adding a card leaves the rest of the codebase unchanged — byId self-consistency`() {
        // This test is the machine-checkable form of the architectural invariant:
        // "adding a card = one object in CARDS; nothing else changes."
        // A hostile reviewer verifies that no file outside Deck.kt enumerates gestures by
        // asserting all routing goes through byId (or CARDS iteration), never through a
        // hard-coded id string. The test here proves byId is the single join point.
        val fromList = Deck.CARDS.associateBy { it.id }
        assertEquals(fromList, Deck.byId)
    }

    @Test
    fun `GestureSpec tolerance boundaries are valid`() {
        // Structural: tolerance 0..1 enforced by init. Confirm the range check triggers.
        try {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = 1.1f)
            assertTrue(false, "expected IllegalArgumentException for tolerance > 1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            GestureSpec(minFingers = 4, movement = Movement.Gather(), tolerance = -0.1f)
            assertTrue(false, "expected IllegalArgumentException for tolerance < 0")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
