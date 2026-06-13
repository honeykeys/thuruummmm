package com.thuruummm.physics

/**
 * A request to place a brick: what [material] the card specifies, and which [cell] the gesture
 * committed to. Produced by the game orchestrator; consumed by [PhysicsEngine.place].
 *
 * This is the only value that crosses the `:app` → `:physics` boundary in the inbound direction.
 * The engine never imports deck, gesture, or haptic types.
 */
data class Placement(
    val material: Material,
    val cell: Cell,
)
