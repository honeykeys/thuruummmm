package com.thrum.game

import androidx.compose.runtime.Immutable
import com.thrum.deck.Glyph
import com.thuruummm.physics.Cell
import com.thuruummm.physics.Material

/**
 * The per-frame, immutable draw model — the ONE value the Canvas reads each frame (ARCHITECTURE.md
 * §3/§4: "The Canvas reads exactly one `State<RenderSnapshot>`"). The game loop builds a fresh
 * snapshot at the end of every `withFrameNanos` tick and writes it into the single draw-state holder;
 * the FieldCanvas (C5) reads it inside its draw lambda, so only the DRAW phase re-runs per frame —
 * never broad recomposition.
 *
 * This is the seam between this component (game/, C6) and the render (ui/, C5). It is a plain,
 * `@Immutable` value: every field is a read-only snapshot of physics + navigation + feedback state at
 * one instant. The Canvas never reaches back into [GameState] or the physics engine — it draws THIS,
 * nothing else. That keeps the render dumb and the loop the single writer.
 *
 * `@Immutable` (not just `@Stable`) is the correct contract here and it is load-bearing for the 60fps
 * goal: it promises Compose that, for a given instance, every public property returns the same value
 * forever, so the runtime can skip the structural-equality re-read on a brand-new instance each frame
 * and treat the snapshot reference swap as the single change that drives the draw. The lists are
 * built once per tick and never mutated after construction (the loop hands out a finished value), so
 * the promise holds. Verified current 2026-06-13:
 * developer.android.com/develop/ui/compose/performance/stability (Immutable/Stable contract).
 *
 * Screen coordinates are NOT computed here. The physics engine is screen-agnostic (PHYSICS.md: "up is
 * structural up; the screen flips it later") — the snapshot carries grid [Cell]s and engine-space
 * scalars; the FieldCanvas owns the cell→pixel mapping (including the y-flip and the [panCell] offset)
 * because only it knows the surface size. This keeps game/ free of any Compose/px concern and
 * JVM-testable.
 */
@Immutable
data class RenderSnapshot(
    /** Every placed brick, as a flat draw list. Order is not significant; the Canvas keys on [BrickView.id]. */
    val bricks: List<BrickView>,
    /**
     * The working slot the next placement commits to — the selecty target (DESIGN §"Utility gestures").
     * Null only at game start before any brick exists and before the first slot is chosen. The Canvas
     * highlights this cell so the eyes-off player can glance and confirm where the brick will land.
     */
    val targetCell: Cell?,
    /**
     * The pan offset, in CELLS, applied by navvy (DESIGN: "a whole-hand slide that does not flourish —
     * pans the view across a large build"). The Canvas subtracts this from each brick's cell before
     * mapping to pixels, so panning scrolls the field without moving any brick in the world. Carried as
     * a float cell offset so navvy can pan smoothly (sub-cell) rather than snapping cell-to-cell.
     */
    val panCellX: Float,
    val panCellY: Float,
    /**
     * The tightest stress margin across the structure (PHYSICS.md §"Time without a timer", via
     * [com.thuruummm.physics.Stress]). 1.0 = calm; → 0.0 = a brick is at its strength limit; the
     * render maps this to the margin-tremor (MOTION.md §4.4 — only a stressed structure trembles).
     */
    val stress: Double,
    /**
     * Non-null on the SINGLE frame a placement triggered a collapse — the trigger the render layer
     * latches to start its tumble + screen-shake (MOTION.md §4.2/§4.3). It carries the [magnitude] the
     * haptic engine also consumed for the THRUUMMMM, so the shake and the buzz are proportional by
     * construction (MOTION.md §4.3: "One number feeds both channels"). The render reads it on the
     * trigger frame and then runs its own decaying shake; subsequent snapshots report null again.
     */
    val collapse: CollapseView?,
    /**
     * True when this device has no vibration motor (emulator / no actuator) — surfaced from the haptic
     * capability probe so the render can show the visual fallback (ARCHITECTURE.md §6 haptics row:
     * "visual-fallback flag"). The toy is playable by feel; with no motor, the screen must carry more.
     */
    val hapticsAvailable: Boolean,
) {
    companion object {
        /** The empty field — what the Canvas draws before the first brick is placed. */
        val EMPTY = RenderSnapshot(
            bricks = emptyList(),
            targetCell = null,
            panCellX = 0f,
            panCellY = 0f,
            stress = 1.0,
            collapse = null,
            hapticsAvailable = true,
        )
    }
}

/**
 * One brick as the render sees it. Decoupled from the physics [com.thuruummm.physics.Brick] on
 * purpose: the render needs the brick's GLYPH (which the physics, being deck-ignorant, does not
 * carry), and it must NOT depend on the physics module's internal brick shape. The loop joins the
 * two — physics gives id+cell+material, the deck gives the glyph keyed by the card that minted it —
 * into this single draw atom.
 *
 * @param id       The physics brick id — stable across cascade rings (Brick.kt), so the render can
 *                 track a brick through a fall and animate it (MOTION.md §4.2 keys on this).
 * @param cell     The brick's grid cell (engine space, y-up). The Canvas applies the y-flip + pan.
 * @param glyph    What to stamp on the brick — the player's vocabulary (DESIGN §"Build model").
 * @param material The five physics stats, forwarded for render tuning (e.g. tint by weight/brittleness,
 *                 spring stiffness by material per MOTION.md §4.1). The render reads it; never mutates.
 */
@Immutable
data class BrickView(
    val id: Int,
    val cell: Cell,
    val glyph: Glyph,
    val material: Material,
)

/**
 * The collapse, as the render needs it to drive shake + tumble (MOTION.md §4.2/§4.3). A thin view over
 * the physics [com.thuruummm.physics.CollapseResult]: the loop forwards exactly what the render uses —
 * the [magnitude] (shake amplitude / impact-frame), and the ids + final cells of the bricks that fell
 * (the tumble renders these). The render layer (C5) reads the full CollapseResult shape from physics
 * if it wants per-ring staging; this view is the minimal proportional signal so the snapshot stays
 * cheap to rebuild every frame.
 *
 * @param magnitude fell × rings × variety (CollapseResult.magnitude) — the ONE number that scales both
 *                  the THRUUMMMM haptic and the screen-shake, keeping them proportional (MOTION.md §4.3).
 * @param rings     Cascade depth — staggers the tumble release ring-by-ring (MOTION.md §4.2 step 2).
 * @param fellIds   The ids of the bricks that left their place — the render's tumble particle set.
 */
@Immutable
data class CollapseView(
    val magnitude: Double,
    val rings: Int,
    val fellIds: List<Int>,
)
