package com.thuruummm.physics

/**
 * The five-stat character sheet for a brick material.
 *
 * Every card in the Deck carries a [Material]; the classifier and haptic engine never read it —
 * only the physics engine does. Adding a new material = adding one card to the Deck. Nothing
 * here enumerates materials; this is the structural contract.
 *
 * Parameter semantics (PHYSICS.md §"The brick: two stats, and a few dials"):
 *
 * @param weight     How much load this brick adds to whatever it rests on. Unitless; engine-relative.
 * @param strength   Maximum load this brick can bear before it buckles. When (load > strength) it fails.
 * @param cantilever How many cells sideways this brick can SPAN as a beam — how far footing extends
 *                   from it to bricks resting in its reach. 0 = no overhang; it foots only what sits
 *                   directly on top of it. A wood beam (cantilever 2) foots bricks up to two cells out
 *                   along an unbroken run; a pebble (cantilever 0) cannot span but may sit on a beam's
 *                   reach. (The reach belongs to the spanning brick, not the brick that leans on it —
 *                   see [Support] for the full footing model.)
 * @param shatterThreshold Minimum fall distance (in cells) that causes the brick to shatter on impact
 *                   rather than simply land. Shattered bricks apply lateral brittleness shoves.
 * @param brittleness 0..1 multiplier on the shove a shattering brick inflicts on its neighbours.
 *                   0 = crumbles silently; 1 = explodes outward, potentially cascading further.
 */
data class Material(
    val weight: Double,
    val strength: Double,
    val cantilever: Int,
    val shatterThreshold: Int,
    val brittleness: Double,
) {
    init {
        // Finite AND positive: POSITIVE_INFINITY passes `> 0` but would poison every downstream load /
        // stress calculation, and NaN passes nothing but must be named explicitly. A material stat is a
        // real, finite number or it is not a material.
        require(weight.isFinite() && weight > 0) { "weight must be a finite value > 0, got $weight" }
        require(strength.isFinite() && strength > 0) { "strength must be a finite value > 0, got $strength" }
        require(cantilever >= 0) { "cantilever must be >= 0, got $cantilever" }
        require(shatterThreshold >= 0) { "shatterThreshold must be >= 0, got $shatterThreshold" }
        require(brittleness.isFinite() && brittleness in 0.0..1.0) { "brittleness must be a finite value in 0..1, got $brittleness" }
    }
}
