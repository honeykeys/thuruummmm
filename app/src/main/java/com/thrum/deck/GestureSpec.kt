package com.thrum.deck

/**
 * The recognition contract a card carries. The classifier consumes ONLY this — it never
 * hard-codes a gesture name or switches on a fixed enum. Every card describes itself here;
 * the classifier's job is to measure the pointer stream and find the best-matching card.
 *
 * DESIGN RULE (Karl, load-bearing): this is a GENERAL descriptor of any hand-choreography,
 * not a fixed enum. No value constraints live here — only the structural contract. Two cards
 * may describe completely different hand shapes while sharing the same Movement subtype.
 *
 * Recognition model (GESTURES.md, three recognizer families):
 *
 *   net centroid translation  → [Movement.Translate] (the "swipey" family; direction = any angle)
 *   rotation + contraction    → [Movement.RotateContract]  (the "twisty" family)
 *   pure contraction (gather) → [Movement.Gather]  (the "tappy" family)
 *
 * The classifier extracts three geometric features from the pointer stream and pattern-matches
 * against each card's spec; [tolerance] controls the match slack. No other code changes when
 * a card is added.
 *
 * @param minFingers  Minimum simultaneous finger count to trigger recognition. Gestures with
 *                    fewer fingers are rejected before spec matching even begins.
 *                    DESIGN: 4–5 fingers; a single finger or two pinkies do not register.
 * @param movement    The primary hand-choreography this card requires (see [Movement]).
 * @param tolerance   Match slack 0..1. At 1.0 any measurement matches; at 0.0 only a perfect
 *                    measurement matches. Tuned per card — harder gestures can afford looser
 *                    tolerance because their movement signature is geometrically distinctive.
 *                    First-guess values; tuned on the thumb, not computed.
 */
data class GestureSpec(
    val minFingers: Int = 4,
    val movement: Movement,
    val tolerance: Float = 0.15f,
) {
    init {
        require(minFingers >= 1) { "minFingers must be >= 1, got $minFingers" }
        require(tolerance in 0f..1f) { "tolerance must be in 0..1, got $tolerance" }
    }
}

/**
 * The primary movement a gesture makes — the classifier's core distinguisher.
 *
 * This is a GENERAL descriptor, not a closed vocabulary. It describes the geometric shape
 * of the hand's motion between landing and the flourish. New movement types can be added
 * (sealed — all callers are in this module) without touching any existing card or classifier
 * branch; the classifier dispatches on the runtime type.
 *
 * Feature space (what the classifier measures from the pointer stream):
 *
 *   centroidDrift   — net displacement of finger-centroid (|Δposition|)
 *   spreadChange    — ratio of final spread to initial spread (< 1 = converging, > 1 = expanding)
 *   rotationRad     — net rotation of the finger cloud about its centroid
 *
 * The three families partition this space cleanly (GESTURES.md):
 *
 *   Gather          — large spreadChange < 1,  small centroidDrift,  small rotation
 *   RotateContract  — spreadChange < 1,         small centroidDrift,  large |rotationRad|
 *   Translate       — large centroidDrift,       spreadChange ≈ 1,    small rotation
 *
 * Additional families (Expand, Pinch, custom Path, …) can be added here by Karl; the
 * classifier sees them via the sealed hierarchy and dispatches accordingly.
 */
sealed interface Movement {

    /**
     * Fingers converge inward to a point — "tappy" family.
     * Classifier looks for: spreadChange < [maxSpreadRatio] with small centroid drift and
     * small net rotation. [maxSpreadRatio] is the gather's "how tight" — 0.5 = fingers must
     * halve their spread; 0.8 = a gentler gather allowed. Tuned per card.
     */
    data class Gather(
        val maxSpreadRatio: Float = 0.6f,
    ) : Movement

    /**
     * Fingers rotate about their centre while spiralling inward — "twisty" family.
     * Classifier looks for: |rotationRad| >= [minRotationRad] AND spreadChange < [maxSpreadRatio].
     * [clockwise] constrains the rotation direction (true = CW, false = CCW, null = either).
     * First-guess rotation threshold; tuned on the thumb.
     */
    data class RotateContract(
        val minRotationRad: Float = 0.5f,   // ~28°; enough to distinguish from gather jitter
        val maxSpreadRatio: Float = 0.8f,
        val clockwise: Boolean? = null,     // null = either direction; set if a card is direction-locked
    ) : Movement

    /**
     * The whole hand slides together in a direction — "swipey" family.
     * Classifier looks for: centroidDrift >= [minDriftPx] (in raw pixels; 48dp at ~160dpi =
     * roughly 76px — first guess, tuned on device) AND spread and rotation are roughly constant.
     * [directionRad] is the target bearing in radians (0 = right, π/2 = down, π = left, 3π/2 = up)
     * in screen coordinates (y-down); null means any direction is accepted.
     * [maxDirectionErrorRad] is the angular tolerance around [directionRad].
     * A card that omits [directionRad] matches any swipe and should have a very high specificity
     * check elsewhere (e.g. via [GestureSpec.tolerance]).
     */
    data class Translate(
        val minDriftPx: Float = 80f,
        val directionRad: Float? = null,            // null = any; set for directional swipeys
        val maxDirectionErrorRad: Float = 0.4f,     // ~23° — fits within one octant
    ) : Movement

    /**
     * Extension point: a complex, multi-phase choreography not captured by the three geometric
     * families above. [description] is a human-readable note for Karl; the classifier treats
     * this as an opaque entry requiring its own purpose-built recogniser branch, identified by
     * [recognizerId]. Adding a [Custom] card leaves the classifier's geometry branches untouched
     * but requires a matching recogniser to be registered in GestureClassifier's dispatch table.
     *
     * Use sparingly — the three families cover the whole deck right now. This is the extension
     * hatch, not the default.
     */
    data class Custom(
        val recognizerId: String,               // key into GestureClassifier's custom dispatch
        val description: String,                // authoring note; not shown to the player
    ) : Movement
}

/** Compass directions in screen coordinates (y-down), in radians. Convenience for Deck authoring. */
object Dir {
    const val RIGHT      = 0f
    const val DOWN_RIGHT = (Math.PI / 4).toFloat()
    const val DOWN       = (Math.PI / 2).toFloat()
    const val DOWN_LEFT  = (3 * Math.PI / 4).toFloat()
    const val LEFT       = Math.PI.toFloat()
    const val UP_LEFT    = (5 * Math.PI / 4).toFloat()
    const val UP         = (3 * Math.PI / 2).toFloat()
    const val UP_RIGHT   = (7 * Math.PI / 4).toFloat()
}
