package com.thrum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thrum.deck.Glyph
import com.thrum.game.BrickView
import com.thrum.game.CollapseView
import com.thrum.game.RenderSnapshot
import com.thuruummm.physics.Cell
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The single full-screen Canvas that draws the game field from a [RenderSnapshot].
 *
 * ── Architecture contract ─────────────────────────────────────────────────────────────────────
 *
 * This composable is the ONLY place that converts grid [Cell] coordinates to screen pixels.
 * The physics engine is screen-agnostic ("up is structural up; the screen flips it later"
 * — PHYSICS.md). The snapshot carries Cell + material; this Canvas owns the cell→pixel mapping:
 *
 *   screenX(cell) = (cell.x - panCellX + gridOriginCellX) * cellPx
 *   screenY(cell) = canvasHeight - (cell.y - panCellY + 1) * cellPx   // y-flip: engine up → screen down
 *
 * where [gridOriginCellX] centres the field horizontally.
 *
 * ── Recomposition discipline (the 60fps rule) ─────────────────────────────────────────────────
 *
 * This composable reads the [RenderSnapshot] via a [State] delegate (`by snapshot`). Compose
 * reads the state INSIDE the draw lambda, which means only the DRAW phase re-runs each frame —
 * not the full composable. No per-frame `mutableStateOf` scatters across this function.
 * (ARCHITECTURE.md §3/§4; RESEARCH-NATIVE.md §4.)
 *
 * ── DrawScope APIs used (all verified 2026-06-13) ────────────────────────────────────────────
 *
 * `Canvas(modifier)` — the composable wrapping `Modifier.drawBehind`. Immediate-mode 2D surface.
 * `DrawScope.drawRoundRect` — brick bodies with rounded corners.
 * `DrawScope.drawLine`, `drawCircle`, `drawPath`, `drawArc` — glyph rendering.
 * `DrawScope.translate`, `DrawScope.rotate` — camera shake + glyph alignment.
 * `DrawScope.size` — the surface dimensions, available inside the draw lambda.
 * Source: developer.android.com/develop/ui/compose/graphics/draw/overview (verified 2026-06-13).
 *
 * @param snapshot  The State<RenderSnapshot> the game loop writes once per frame. Read inside the
 *                  draw lambda so only the draw phase triggers on each frame.
 * @param shakeOffset The current shake Offset in pixels, computed by GameScreen from CollapseView.
 *                    Passed as a plain Offset (not Compose state) so the Canvas reads it from its
 *                    caller's recomposition — the shake is driven by the game loop's snapshot swap.
 */
@Composable
fun FieldCanvas(
    snapshot: State<RenderSnapshot>,
    shakeOffset: Offset = Offset.Zero,
) {
    val density = LocalDensity.current
    val cellPx  = with(density) { CELL_DP.dp.toPx() }
    val cornerPx = with(density) { CORNER_DP.dp.toPx() }
    val glyphStroke = with(density) { GLYPH_STROKE_DP.dp.toPx() }
    val gridStroke   = with(density) { GRID_STROKE_DP.dp.toPx() }
    val groundStroke = with(density) { GROUND_STROKE_DP.dp.toPx() }
    val targetStroke = with(density) { TARGET_STROKE_DP.dp.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Read the snapshot INSIDE the draw lambda — this is the recomposition discipline.
        // Only the draw re-runs; the composable body does not recompose per frame.
        val snap = snapshot.value

        // Apply camera shake (translate the entire field).
        translate(left = shakeOffset.x, top = shakeOffset.y) {

            // Coordinate helpers captured from DrawScope.size.
            val w = size.width
            val h = size.height

            // Centre the grid horizontally. Grid x=0 maps to the screen centre.
            val gridOriginX = w / 2f - snap.panCellX * cellPx

            // Coordinate mapping — engine space to screen space.
            // Engine: y-up (y=0 = bedrock floor). Screen: y-down (y=0 = top).
            // Flip: screenY = h - (engineY + 1) * cellPx + panCellY*cellPx
            // "+ 1" so the floor brick's bottom edge sits AT the bottom of the canvas.
            fun cellToScreen(cell: Cell): Offset = Offset(
                x = gridOriginX + cell.x * cellPx,
                y = h - (cell.y + 1 - snap.panCellY) * cellPx,
            )

            // Grid lattice — a faint cell grid so the player can READ the building space (where bricks
            // snap, how tall the tower is, where the next slot sits). Drawn UNDER the bricks and slot so
            // it never competes with them. Aligned to the same cell→screen mapping (and pan) as bricks,
            // so the lines always frame the cells exactly. The y=0 boundary (bedrock, the bottom edge of
            // the ground row) is drawn brighter — it is the floor the whole structure stands on.
            run {
                // Vertical lines at each cell's left/right edge across the visible width.
                val firstCol = kotlin.math.floor((0f - gridOriginX) / cellPx).toInt()
                val lastCol  = kotlin.math.ceil((w - gridOriginX) / cellPx).toInt()
                for (col in firstCol..lastCol) {
                    val x = gridOriginX + col * cellPx
                    drawLine(GRID_LINE, Offset(x, 0f), Offset(x, h), gridStroke)
                }
                // Horizontal lines at each row boundary k: screenY(k) = h - (k - panCellY) * cellPx.
                // k == 0 is bedrock (the bottom edge of the ground row).
                val firstRow = kotlin.math.floor(snap.panCellY).toInt()
                val lastRow  = firstRow + kotlin.math.ceil(h / cellPx).toInt() + 1
                for (k in firstRow..lastRow) {
                    val y = h - (k - snap.panCellY) * cellPx
                    if (k == 0) {
                        drawLine(GROUND_LINE, Offset(0f, y), Offset(w, y), groundStroke)
                    } else {
                        drawLine(GRID_LINE, Offset(0f, y), Offset(w, y), gridStroke)
                    }
                }
            }

            // Draw placed bricks.
            for (brick in snap.bricks) {
                val topLeft = cellToScreen(brick.cell)
                val brickColor = materialColor(brick)
                drawRoundRect(
                    color       = brickColor,
                    topLeft     = topLeft,
                    size        = Size(cellPx, cellPx),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                )
                // Glyph — the player's vocabulary, stamped on the brick.
                drawGlyph(
                    glyph       = brick.glyph,
                    centre      = Offset(topLeft.x + cellPx / 2f, topLeft.y + cellPx / 2f),
                    radius      = cellPx * GLYPH_RADIUS_FRACTION,
                    strokeWidth = glyphStroke,
                )
            }

            // Working-slot highlight — WHERE THE NEXT BRICK LANDS. This is the player's anchor: without
            // it, selecty (the slot-moving tap) is invisible and the build feels blind. Drawn as an
            // inset "drop zone": a translucent fill + a bright rounded border + a centre cross, so it
            // reads unmistakably as an empty slot waiting for a brick (vs the solid, glyph-stamped bricks).
            snap.targetCell?.let { target ->
                val topLeft = cellToScreen(target)
                val inset   = cellPx * TARGET_INSET_FRACTION
                val slotTL  = Offset(topLeft.x + inset, topLeft.y + inset)
                val slotSz  = Size(cellPx - inset * 2f, cellPx - inset * 2f)
                // Translucent fill — the slot reads as a soft drop zone, not a solid brick.
                drawRoundRect(
                    color        = TARGET_FILL,
                    topLeft      = slotTL,
                    size         = slotSz,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                )
                // Bright border — the unmistakable "here" outline.
                drawRoundRect(
                    color        = TARGET_BORDER,
                    topLeft      = slotTL,
                    size         = slotSz,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style        = Stroke(width = targetStroke),
                )
                // Centre cross — a quiet "place here" mark, distinct from any brick glyph.
                val c    = Offset(topLeft.x + cellPx / 2f, topLeft.y + cellPx / 2f)
                val armX = cellPx * 0.16f
                drawLine(TARGET_BORDER, Offset(c.x - armX, c.y), Offset(c.x + armX, c.y), targetStroke, StrokeCap.Round)
                drawLine(TARGET_BORDER, Offset(c.x, c.y - armX), Offset(c.x, c.y + armX), targetStroke, StrokeCap.Round)
            }

            // Stress tremor — the structure trembles as it approaches collapse.
            // Rendered as a subtle overlay tint on the whole field when stress < STRESS_TREMBLE_THRESHOLD.
            val stress = snap.stress
            if (stress < STRESS_TREMBLE_THRESHOLD) {
                val alpha = ((STRESS_TREMBLE_THRESHOLD - stress) / STRESS_TREMBLE_THRESHOLD)
                    .toFloat()
                    .coerceIn(0f, 0.25f)
                drawRect(
                    color = Color(0xFFFF4444).copy(alpha = alpha),
                    size  = size,
                )
            }

            // Visual haptic fallback: if the device has no motor, pulse the field with a glow
            // on the collapse frame to carry the state the haptic channel normally would.
            if (!snap.hapticsAvailable && snap.collapse != null) {
                drawRect(
                    color = Color.White.copy(alpha = 0.18f),
                    size  = size,
                )
            }
        }
    }
}

// ── Constants ──────────────────────────────────────────────────────────────────────────────────

/**
 * Side length of one grid cell in dp. Sized to FINGER SCALE: a brick must read as at least as big as
 * the fingertip that places it, or the player cannot intuit where it lands (Karl, on device — bricks at
 * 52dp ≈ 14mm felt much smaller than a finger). 96dp ≈ 15–16mm on a Pixel 7, comfortably finger-sized,
 * and still fits ~9–10 cells across a landscape phone. A pure feel parameter — tune on the thumb.
 * `internal` so [GameScreen] can reference this same constant for its navvy px→cell conversion,
 * eliminating the [GameScreen.CELL_DP_APPROX] duplicate that was a latent divergence bug (P2d).
 */
internal const val CELL_DP = 96

/** Corner radius for brick roundrects. */
private const val CORNER_DP = 8

/** Stroke width for glyphs, in dp. */
private const val GLYPH_STROKE_DP = 2

/** Glyph is inscribed in this fraction of the cell radius. */
private const val GLYPH_RADIUS_FRACTION = 0.32f

/** Stress margin below which the tremor effect activates. 1.0 = calm, 0.0 = at limit. */
private const val STRESS_TREMBLE_THRESHOLD = 0.25

// ── Grid + working-slot visuals ─────────────────────────────────────────────────────────────────

/** Faint cell-lattice line — visible enough to read the build, quiet enough not to fight the bricks. */
private val GRID_LINE   = Color.White.copy(alpha = 0.07f)
/** Bedrock line (y=0 boundary) — the floor the structure stands on; brighter than the lattice. */
private val GROUND_LINE = Color(0xFF7EC8E3).copy(alpha = 0.28f)
private const val GRID_STROKE_DP   = 1
private const val GROUND_STROKE_DP = 2

/** Working-slot fill — a soft teal drop-zone tint inside the next-placement cell. */
private val TARGET_FILL   = Color(0xFF7EC8E3).copy(alpha = 0.16f)
/** Working-slot border + centre cross — the bright, unmistakable "next brick lands here" outline. */
private val TARGET_BORDER = Color(0xFF7EC8E3).copy(alpha = 0.95f)
private const val TARGET_STROKE_DP    = 3
/** Inset of the slot drop-zone inside its cell, as a fraction of the cell — keeps it off the grid lines. */
private const val TARGET_INSET_FRACTION = 0.06f

// ── Material → colour ──────────────────────────────────────────────────────────────────────────

/**
 * Map a brick's material stats to a render colour. No labels, no text — the colour IS the
 * material identity, alongside the glyph. The player decodes the vocabulary through play.
 *
 * Heuristic: higher strength = warmer/brighter; higher brittleness = more saturated/fragile-looking.
 * These are first guesses — Karl tunes the palette once the game runs on device.
 */
private fun materialColor(brick: BrickView): Color {
    val m = brick.material
    // Normalise strength into [0,1] assuming max interesting strength ~ 10.
    val s = (m.strength / 10.0).coerceIn(0.0, 1.0).toFloat()
    // Normalise brittleness into [0,1] — already 0..1 by Material contract.
    val b = m.brittleness.toFloat().coerceIn(0f, 1f)

    // Palette: low-strength = cool slate; high-strength = warm amber; brittle = desaturated blue.
    // Lerp between anchors.
    val base = lerpColor(
        from   = Color(0xFF5577AA),  // slate — cheap/weak bricks
        to     = Color(0xFFE8A23A),  // amber — strong/durable bricks
        factor = s,
    )
    return lerpColor(
        from   = base,
        to     = Color(0xFF88AACC),  // ice-blue — brittle bricks shift cool
        factor = b * 0.5f,
    )
}

private fun lerpColor(from: Color, to: Color, factor: Float): Color = Color(
    red   = from.red   + (to.red   - from.red)   * factor,
    green = from.green + (to.green - from.green) * factor,
    blue  = from.blue  + (to.blue  - from.blue)  * factor,
    alpha = 1f,
)

// ── Glyph rendering ────────────────────────────────────────────────────────────────────────────

/**
 * Draw the glyph for a brick at [centre] with the given [radius].
 *
 * Each glyph maps to a distinct shape drawn with [DrawScope] primitives. The player learns the
 * vocabulary through play — no label is ever shown (DESIGN.md: "never labelled").
 *
 * Arrow glyphs (ARROW_CENTER, ARROW_RIGHT, etc.) indicate the gesture direction that minted the
 * brick. SPIRAL indicates a rotation gesture. STUB glyphs render a simple dot.
 *
 * DrawScope primitives used:
 *  - `drawLine` — arrow shafts and stems
 *  - `drawPath` — arrow heads, spiral curves
 *  - `rotate`   — rotates directional arrows without needing per-direction path math
 *  - `drawCircle` — SPIRAL center dot and STUB markers
 *  - `drawArc`  — spiral arc
 * All verified 2026-06-13: developer.android.com/develop/ui/compose/graphics/draw/overview.
 */
private fun DrawScope.drawGlyph(
    glyph: Glyph,
    centre: Offset,
    radius: Float,
    strokeWidth: Float,
) {
    val glyphColor = Color.White.copy(alpha = 0.75f)
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    when (glyph) {
        // ARROW_CENTER: four inward-pointing arrows converging on centre (tappy — gather gesture).
        Glyph.ARROW_CENTER -> {
            val arrowLen = radius * 0.9f
            val headLen  = radius * 0.4f
            for (angleDeg in listOf(0f, 90f, 180f, 270f)) {
                rotate(degrees = angleDeg, pivot = centre) {
                    val tip   = Offset(centre.x + arrowLen, centre.y)
                    val tail  = Offset(centre.x, centre.y)
                    drawLine(glyphColor, tail, tip, strokeWidth, StrokeCap.Round)
                    // Arrowhead: two lines back from tip.
                    drawLine(glyphColor, tip, Offset(tip.x - headLen, tip.y - headLen * 0.5f), strokeWidth, StrokeCap.Round)
                    drawLine(glyphColor, tip, Offset(tip.x - headLen, tip.y + headLen * 0.5f), strokeWidth, StrokeCap.Round)
                }
            }
        }

        // SPIRAL: clockwise arc with a dot at centre (twisty — rotate+contract gesture).
        Glyph.SPIRAL -> {
            drawArc(
                color      = glyphColor,
                startAngle = -30f,
                sweepAngle = 300f,
                useCenter  = false,
                style      = stroke,
                topLeft    = Offset(centre.x - radius, centre.y - radius),
                size       = Size(radius * 2f, radius * 2f),
            )
            drawCircle(glyphColor, radius = strokeWidth * 0.9f, center = centre)
        }

        // Directional arrows: one arrow pointing in the swipe direction.
        // `rotate` pivots the draw around [centre] — reuse a single right-pointing arrow shape.
        Glyph.ARROW_RIGHT      -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 0f)
        Glyph.ARROW_DOWN_RIGHT -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 45f)
        Glyph.ARROW_DOWN       -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 90f)
        Glyph.ARROW_DOWN_LEFT  -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 135f)
        Glyph.ARROW_LEFT       -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 180f)
        Glyph.ARROW_UP         -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 270f)
        Glyph.ARROW_UP_RIGHT   -> drawDirectionalArrow(centre, radius, strokeWidth, glyphColor, 315f)

        // STUB glyphs — a small dot while Karl assigns the final design.
        else -> drawCircle(
            color  = glyphColor.copy(alpha = 0.45f),
            radius = strokeWidth * 1.5f,
            center = centre,
        )
    }
}

/**
 * A single right-pointing arrow, rotated to [directionDeg] degrees (0 = right, 90 = down, etc.).
 * `DrawScope.rotate` pivots around [centre] — verified 2026-06-13:
 * developer.android.com/develop/ui/compose/graphics/draw/overview (DrawScope.rotate).
 */
private fun DrawScope.drawDirectionalArrow(
    centre: Offset,
    radius: Float,
    strokeWidth: Float,
    color: Color,
    directionDeg: Float,
) {
    rotate(degrees = directionDeg, pivot = centre) {
        val tail    = Offset(centre.x - radius * 0.8f, centre.y)
        val tip     = Offset(centre.x + radius * 0.8f, centre.y)
        val headLen = radius * 0.45f
        drawLine(color, tail, tip, strokeWidth, StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x - headLen, tip.y - headLen * 0.55f), strokeWidth, StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x - headLen, tip.y + headLen * 0.55f), strokeWidth, StrokeCap.Round)
    }
}

// ── Shake helper ────────────────────────────────────────────────────────────────────────────────

/**
 * Compute a screen-shake Offset for the current frame from a [CollapseView].
 *
 * The shake is a decaying sinusoidal translation proportional to the collapse magnitude.
 * Called by GameScreen each frame (the game loop's snapshot swap drives recomposition of the
 * GameScreen, which reads the elapsed time and calls this to produce the Offset passed to
 * [FieldCanvas]).
 *
 * @param collapse   The collapse that triggered the shake (from RenderSnapshot).
 * @param elapsedMs  Milliseconds since the collapse was first reported.
 * @return           A pixel Offset to translate the field by; Offset.Zero when shake has decayed.
 */
fun computeShake(collapse: CollapseView, elapsedMs: Long): Offset {
    val totalMs   = SHAKE_DURATION_MS
    if (elapsedMs >= totalMs) return Offset.Zero

    val progress  = elapsedMs.toFloat() / totalMs.toFloat()
    val decay     = 1f - progress
    // kotlin.math.PI matches the rest of the codebase (Math.PI was an outlier — P2c).
    val frequency = SHAKE_FREQUENCY_HZ * 2f * kotlin.math.PI.toFloat()
    val t         = elapsedMs / 1000f

    // Scale amplitude by collapse magnitude (capped so even vast collapses stay legible).
    val amplitude = min(
        SHAKE_AMPLITUDE_MAX_PX,
        SHAKE_AMPLITUDE_PER_MAGNITUDE * collapse.magnitude.toFloat(),
    ) * decay

    // Nothing to shake for — return a CLEAN zero. `0f * sin(θ)` yields -0.0 for negative θ, and
    // -0.0f.equals(0.0f) is false (IEEE signed zero), which would fail a magnitude-0 "Offset is Zero"
    // assertion. Short-circuit a zero amplitude to the canonical Offset.Zero so a no-shake collapse
    // (magnitude 0, or fully decayed) is exactly zero, never negative zero.
    if (amplitude == 0f) return Offset.Zero

    return Offset(
        x = amplitude * sin(frequency * t),
        y = amplitude * cos(frequency * t * 0.73f),  // slight y-offset frequency for organic feel
    )
}

/**
 * `internal` so [GameScreen]'s shake loop can read this constant instead of hardcoding 800L (P2b).
 * The loop must stop at the same time [computeShake] starts returning Offset.Zero.
 */
internal const val SHAKE_DURATION_MS         = 700L
private const val SHAKE_FREQUENCY_HZ         = 14f
private const val SHAKE_AMPLITUDE_MAX_PX     = 18f
private const val SHAKE_AMPLITUDE_PER_MAGNITUDE = 2.5f
