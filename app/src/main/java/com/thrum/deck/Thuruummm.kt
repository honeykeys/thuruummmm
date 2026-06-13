package com.thrum.deck

import com.thuruummm.physics.Material
import com.thrum.haptics.Haptic

/**
 * One deck entry — the single source of truth for a gesture-material pair.
 *
 * A Thuruummm bundles all five facets of one playable gesture:
 *
 *   [gesture]  → how the GestureClassifier recognises it (start config × movement × flourish)
 *   [material] → what the PhysicsEngine places when this card fires
 *   [rummmm]   → the larger second beat of the commit haptic — this card's character
 *   [glyph]    → what the render (FieldCanvas) stamps on the placed brick
 *
 * The card is the single JOIN POINT of all four subsystems. Each subsystem reads exactly the
 * facet it owns; none reads the others.
 *
 *   GestureClassifier  consumes  card.gesture   (never hard-codes a gesture name)
 *   PhysicsEngine      consumes  card.material  (card passes the Material value; physics is ignorant of cards)
 *   HapticEngine       consumes  card.rummmm    (fires the card's character beat after physics completes)
 *   FieldCanvas        consumes  card.glyph     (stamps the visual mark; never reads gesture or material)
 *
 * DESIGN RULE (Karl, load-bearing): NO value constraints on the card's parameters. The card class
 * and the GestureClassifier impose only a STRUCTURAL contract (well-formed; gesture classifiable;
 * haptic playable). Adding a card = one object added to [Deck.CARDS]. Nothing else changes.
 *
 * @param id       Stable key used in logs and tests; never shown to the player. ASCII lowercase.
 * @param gesture  Recognition spec (see [GestureSpec] + [Movement]).
 * @param material Physics stats for the brick this card mints (see [Material]).
 * @param rummmm   The second haptic beat — this card's character. Fires after physics.place().
 * @param glyph    The visual mark stamped on the placed brick.
 */
data class Thuruummm(
    val id: String,
    val gesture: GestureSpec,
    val material: Material,
    val rummmm: Haptic,
    val glyph: Glyph,
) {
    init {
        require(id.isNotBlank()) { "Thuruummm id must not be blank" }
    }
}
