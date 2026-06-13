package com.thuruummm.physics

/**
 * The result of a collapse cascade.
 *
 * @param fell      Every brick that fell during the cascade (all rings combined).
 * @param rings     How many cascade rings it took before the grid stabilised. A deep cascade
 *                  (many rings) means the structure was highly interconnected — tangled ambition.
 * @param materials The distinct [Material] types involved in the collapse. Variety × rings ×
 *                  count drives the THRUUMMMM magnitude.
 * @param finalGrid The stabilised grid after all falling and shattering is resolved.
 */
data class CollapseResult(
    val fell: List<Brick>,
    val rings: Int,
    val materials: Set<Material>,
    val finalGrid: Grid,
) {
    /**
     * The THRUUMMMM magnitude: (bricks that fell) × (cascade rings) × (material variety).
     * PHYSICS.md §"The collapse → the THRUUMMMM": tangled complexity scores higher than a tall
     * plain tower with more bricks, because rings and variety multiply the count.
     */
    val magnitude: Double
        get() = fell.size.toDouble() * rings * materials.size
}
