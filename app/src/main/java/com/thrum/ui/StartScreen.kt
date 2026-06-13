package com.thrum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thrum.gesture.Finger

/**
 * The Start screen — PORTRAIT orientation, "twist to start".
 *
 * Layout: full-screen dark surface. Three finger-blob indicators pulse at rest. A twist gesture
 * (5 fingers rotating) triggers [onTwist] which causes the state machine to switch to GameScreen
 * and lock landscape.
 *
 * ── What this screen does ─────────────────────────────────────────────────────────────────────
 *
 * The Start screen runs a MINIMAL version of the game's gesture pipeline — it only needs to detect
 * one gesture (the "twist to start" rotate) without placing bricks. Rather than instantiate a full
 * GameState, it uses a lightweight local classifier:
 *  - Tracks live finger positions via raw `pointerInput`.
 *  - Draws three finger-blob circles on a Canvas to teach the 3-5 finger posture.
 *  - Detects a rotate commit locally (spread fingers, then rotate, then lift = twist → start).
 *
 * The visual is deliberately sparse: three glowing circles, no text, no labels. The player learns
 * to place hands by feel + the visual hint. A "twist" arc indicator appears when enough fingers
 * are down to hint the next step.
 *
 * ── Gesture detection strategy ────────────────────────────────────────────────────────────────
 *
 * The Start screen does NOT use the full GestureClassifier (which is wired to the Deck for
 * brick-minting). Instead, it runs a simple heuristic inline:
 *  1. Track fingers via `pointerInput` → `awaitPointerEventScope` → `awaitPointerEvent()`.
 *  2. Wait for >= 3 fingers down simultaneously.
 *  3. Measure the angular sweep of the finger centroid over the touch window.
 *  4. On all-fingers-lift after >= MIN_ROTATE_RAD of angular travel: fire [onTwist].
 *
 * This is intentionally simple — the Start screen is a one-time tutorial moment, not a gameplay
 * surface. If the rotate detection is ever promoted to the full classifier, the rewrite is
 * mechanical (the gesture pipeline already has Movement.RotateContract).
 *
 * ── Raw pointer API (verified 2026-06-13) ────────────────────────────────────────────────────
 *
 * `Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } }`
 * is the current verified pattern for raw multi-touch.
 *
 * `PointerInputChange` fields used:
 *   - `id: PointerId`   — stable per-finger identifier across a stroke (PointerId.value: Long)
 *   - `position: Offset` — finger position in element pixels, origin top-left
 *   - `pressed: Boolean` — true while the finger is in contact
 * Verified: developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/PointerInputChange
 * and developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures (2026-06-13).
 *
 * @param onTwist Called when the twist gesture is detected — switches to Game screen and locks landscape.
 */
@Composable
fun StartScreen(onTwist: () -> Unit) {
    // Orientation is now locked at the ThuruummmApp root to avoid the restore→set thrash that
    // occurred when each screen's own DisposableEffect fired on transitions (P3c fix). No
    // LockOrientation call here. ThuruummmApp maps Screen.Start → SCREEN_ORIENTATION_PORTRAIT.

    // Live finger positions for blob rendering (Compose state so Canvas redraws on every event).
    var fingers by remember { mutableStateOf<List<Finger>>(emptyList()) }

    // Lightweight twist detector state — not Compose state (loop-internal, not draw-affecting).
    val twistState = remember { StartTwistDetector(onTwist) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Raw multi-touch: awaitPointerEventScope + awaitPointerEvent loop.
                // Verified current 2026-06-13:
                // developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures
                awaitPointerEventScope {
                    while (true) {
                        val event: PointerEvent = awaitPointerEvent()
                        // Map Compose PointerInputChange → our plain Finger type.
                        // Only the pressed+position snapshot matters for the Start screen.
                        val current = event.changes.map { c ->
                            Finger(
                                id      = c.id.value,
                                x       = c.position.x,
                                y       = c.position.y,
                                pressed = c.pressed,
                            )
                        }
                        fingers = current
                        twistState.feed(current, event.type)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StartCanvas(fingers = fingers)
    }
}

/**
 * The Start screen's Canvas — draws the three finger-blob hint circles and a twist arc when
 * enough fingers are present.
 *
 * ── Drawing strategy ──────────────────────────────────────────────────────────────────────────
 *
 * The three circles are arranged in a triangle around the screen centre — enough visual spread
 * to teach "use multiple fingers" without resembling a button. When 3+ fingers are detected the
 * filled blobs track the live finger positions; at rest the static triangle hint renders.
 * A partial arc sweeps CW around the centroid to cue "rotate" once fingers are down.
 *
 * DrawScope APIs used (all verified current 2026-06-13):
 *   `drawCircle`, `drawArc`, `rotate`, `withTransform`, `Stroke` — all in `DrawScope`.
 *   Source: developer.android.com/develop/ui/compose/graphics/draw/overview.
 */
@Composable
private fun StartCanvas(fingers: List<Finger>) {
    val density = LocalDensity.current
    val blobRadius = with(density) { 28.dp.toPx() }
    val hintRadius = with(density) { 20.dp.toPx() }
    val arcRadius  = with(density) { 90.dp.toPx() }
    val strokePx   = with(density) { 3.dp.toPx() }

    val pressedFingers = fingers.filter { it.pressed }
    val showLive = pressedFingers.size >= 3

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        if (showLive) {
            // Live finger blobs — track actual touch positions.
            for (f in pressedFingers) {
                // Outer glow ring
                drawCircle(
                    color  = Color(0xFF7EC8E3).copy(alpha = 0.25f),
                    radius = blobRadius * 1.6f,
                    center = Offset(f.x, f.y),
                )
                // Filled blob
                drawCircle(
                    color  = Color(0xFF7EC8E3).copy(alpha = 0.85f),
                    radius = blobRadius,
                    center = Offset(f.x, f.y),
                )
            }

            // Twist arc hint: a partial CW arc centred on the finger centroid.
            val centroid = Offset(
                x = pressedFingers.map { it.x }.average().toFloat(),
                y = pressedFingers.map { it.y }.average().toFloat(),
            )
            drawArc(
                color       = Color(0xFF7EC8E3).copy(alpha = 0.55f),
                startAngle  = -30f,
                sweepAngle  = 200f,
                useCenter   = false,
                style       = Stroke(width = strokePx * 2f, cap = StrokeCap.Round),
                topLeft     = Offset(centroid.x - arcRadius, centroid.y - arcRadius),
                size        = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
            )
        } else {
            // Static hint: three circles in a triangle around the centre, subdued.
            val positions = listOf(
                Offset(cx,                cy - arcRadius * 0.9f),  // top
                Offset(cx - arcRadius * 0.8f, cy + arcRadius * 0.5f),  // bottom-left
                Offset(cx + arcRadius * 0.8f, cy + arcRadius * 0.5f),  // bottom-right
            )
            for (pos in positions) {
                drawCircle(
                    color  = Color(0xFF7EC8E3).copy(alpha = 0.15f),
                    radius = hintRadius * 1.5f,
                    center = pos,
                )
                drawCircle(
                    color  = Color(0xFF7EC8E3).copy(alpha = 0.45f),
                    radius = hintRadius,
                    center = pos,
                    style  = Stroke(width = strokePx),
                )
            }

            // Faint CW arc at rest — seeds the "rotate" expectation.
            withTransform({ rotate(degrees = 0f, pivot = Offset(cx, cy)) }) {
                drawArc(
                    color       = Color(0xFF7EC8E3).copy(alpha = 0.20f),
                    startAngle  = -60f,
                    sweepAngle  = 240f,
                    useCenter   = false,
                    style       = Stroke(width = strokePx, cap = StrokeCap.Round),
                    topLeft     = Offset(cx - arcRadius, cy - arcRadius),
                    size        = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
                )
            }
        }
    }
}

/**
 * Minimal rotate-gesture detector for the Start screen. Not a full GestureClassifier — it only
 * needs to fire [onTwist] once on a "spread fingers, rotate, lift" sequence.
 *
 * State machine:
 *  IDLE → TRACKING (>= 3 fingers down) → COMMITTED (angular travel >= MIN, then all lift).
 *
 * This is a plain class (not @Stable, not @Composable) because it is internal to the screen,
 * lives in `remember { }`, and holds no Compose state. The detector resets after each commit so
 * the screen can be navigated away and back cleanly.
 */
private class StartTwistDetector(private val onTwist: () -> Unit) {

    private companion object {
        /** Minimum cumulative angular travel (radians) for the twist to be recognised. ~57 degrees. */
        const val MIN_ROTATE_RAD = 1.0f
        /** Minimum fingers that must be pressed simultaneously to enter TRACKING. */
        const val MIN_FINGERS = 3
    }

    private var tracking    = false
    private var totalAngle  = 0f    // cumulative angular change in radians
    private var lastCentroid: Offset? = null
    private var lastAngle: Float?   = null
    private var committed   = false

    fun feed(fingers: List<Finger>, eventType: PointerEventType) {
        if (committed) return

        val pressed = fingers.filter { it.pressed }

        if (!tracking) {
            if (pressed.size >= MIN_FINGERS) {
                tracking = true
                val c = centroid(pressed)
                lastCentroid = c
                // Seed lastAngle from the ENTRY frame so the very first rotation step is counted, not
                // discarded. Leaving it null lost one frame of travel, so a true MIN_ROTATE_RAD rotation
                // fell fractionally short of the `>= MIN_ROTATE_RAD` commit gate — the documented
                // boundary (≥, line below) was never actually reachable at exactly the threshold.
                val f = pressed.first()
                lastAngle = kotlin.math.atan2(f.y - c.y, f.x - c.x)
                totalAngle = 0f
            }
            return
        }

        if (pressed.size < MIN_FINGERS) {
            // All (or enough) fingers lifted — check commit.
            if (kotlin.math.abs(totalAngle) >= MIN_ROTATE_RAD) {
                committed = true
                onTwist()
            }
            reset()
            return
        }

        // Accumulate angular delta relative to the centroid.
        val c = centroid(pressed)
        val prev = lastCentroid
        val prevAngle = lastAngle
        if (prev != null) {
            // Angle of the finger-spread vector (from centroid to first finger) this frame vs last.
            // A simple proxy: measure the signed angle of centroid→first-finger across frames.
            val f = pressed.first()
            val dx = f.x - c.x
            val dy = f.y - c.y
            val angle = kotlin.math.atan2(dy, dx)
            if (prevAngle != null) {
                var delta = angle - prevAngle
                // Wrap to [-π, π] to handle 0/2π boundary.
                while (delta > kotlin.math.PI) delta -= (2.0 * kotlin.math.PI).toFloat()
                while (delta < -kotlin.math.PI) delta += (2.0 * kotlin.math.PI).toFloat()
                totalAngle += delta
            }
            lastAngle = angle
        }
        lastCentroid = c
    }

    private fun reset() {
        tracking     = false
        totalAngle   = 0f
        lastCentroid = null
        lastAngle    = null
        // Do NOT reset `committed` — once the twist fires, the screen switches away.
    }

    private fun centroid(pressed: List<Finger>): Offset = Offset(
        x = pressed.map { it.x }.average().toFloat(),
        y = pressed.map { it.y }.average().toFloat(),
    )
}
