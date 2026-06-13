package com.thrum.gesture

import com.thrum.deck.Thuruummm
import com.thuruummm.physics.Cell

/**
 * The classifier's output on a committed gesture: WHICH card fired, WHERE it lands, and the evidence.
 *
 * This is the value the game loop consumes to drive the rest of the pipeline (ARCHITECTURE.md §2):
 *
 *   hapticEngine.thur()                                  // beat 1, uniform — the flourish landed
 *   physics.place(Placement(card.material, targetCell))  // physics consumes card.material
 *   hapticEngine.rummmm(card.rummmm)                     // beat 2 — this card's character
 *   snapshot.stamp(targetCell, card.glyph)               // render consumes card.glyph
 *
 * The classifier returns the full [Thuruummm] (not just its id) so the loop needs no second lookup;
 * `Deck.byId` remains available for id-keyed paths (logs, tests).
 *
 * @param card        The deck entry whose [com.thrum.deck.GestureSpec] best matched the stream.
 * @param targetCell  The grid cell this placement commits to — supplied by the game layer's working
 *                    slot (selecty), not computed by the classifier. The classifier owns recognition,
 *                    not board state.
 * @param features    The measurements that produced the match — kept for on-thumb tuning and tests
 *                    (e.g. asserting a swipe's measured direction), never shown to the player.
 * @param score       Match quality in [0, 1]; 1.0 = a perfect fit to the card's spec. The classifier
 *                    only returns a [Recognized] when this cleared the card's tolerance gate.
 */
data class Recognized(
    val card: Thuruummm,
    val targetCell: Cell,
    val features: GestureFeatures,
    val score: Float,
)
