package com.thrum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thrum.game.CollapseView
import com.thrum.game.GameLoop
import com.thrum.game.GameState
import com.thrum.gesture.Finger
import com.thrum.haptics.ThuruummmHaptics
import com.thuruummm.physics.Cell
import kotlinx.coroutines.isActive
import kotlin.math.floor

/**
 * The Game screen — LANDSCAPE orientation, full-screen Canvas, physics-driven build field.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────────────────────
 *
 * 1. Lock landscape orientation.
 * 2. Remember [GameState] (the @Stable plain holder, not a ViewModel — see ARCHITECTURE.md §3).
 * 3. Wire raw [pointerInput] multi-touch to [GameState.onFingers] (plus selecty / navvy routing).
 * 4. Host [GameLoop] (the `LaunchedEffect`-driven `withFrameNanos` clock).
 * 5. Drive the shake animation from [com.thrum.game.CollapseView] each frame.
 * 6. Render via [FieldCanvas] — the only composable that draws.
 *
 * ── UI is THIN — all logic lives in game/ ────────────────────────────────────────────────────
 *
 * This screen does NOT:
 * - classify gestures (game/GestureClassifier does)
 * - run physics (physics/PhysicsEngine does)
 * - decide which haptic fires (game/GameState.commit does)
 *
 * It ONLY:
 * - maps `PointerInputChange` → `Finger` and deposits into [GameState.onFingers]
 * - resolves "is this a selecty tap" (single finger, brief, no drift) vs navvy vs a minting gesture
 *   and calls [GameState.selectAdjacent] / [GameState.panBy] vs lets the classifier handle it
 * - reads [RenderSnapshot] (from [GameState.snapshot]) and the shake scalar; passes both to [FieldCanvas]
 *
 * ── Raw pointer API (verified 2026-06-13) ────────────────────────────────────────────────────
 *
 * `Modifier.pointerInput(Unit) { awaitPointerEventScope { while(true) { awaitPointerEvent() } } }`
 * is the current verified pattern for raw multi-touch (RESEARCH-NATIVE.md §4).
 *
 * `PointerInputChange` fields used:
 *   - `id: PointerId`    — stable per-finger id (Long via .value)
 *   - `position: Offset` — screen pixel coordinates
 *   - `pressed: Boolean` — whether this finger is currently down
 * Verified: developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/PointerInputChange
 * and developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures (2026-06-13).
 *
 * ── Recomposition discipline ──────────────────────────────────────────────────────────────────
 *
 * The only Compose state this screen owns:
 *  - `shakeOffsetPx` ([mutableStateOf<Offset>]) — drives the FieldCanvas translate each frame.
 *
 * The shake is driven from the SINGLE frame-clock loop (see below). No second `withFrameNanos`
 * loop runs here; the shake start is latched on the frame clock and the elapsed is computed
 * against that same clock so the subtraction is on consistent time bases (P0 fix).
 *
 * The [GameState.snapshot] state is owned by game/ and read by [FieldCanvas] inside its draw
 * lambda, so per-frame draw does NOT trigger recomposition of GameScreen itself.
 * (ARCHITECTURE.md §3; RESEARCH-NATIVE.md §4.)
 *
 * @param onSetty Called when the setty gesture is recognized (via the GestureClassifier — the setty
 *                gesture is flagged by the classifier and surfaced to the loop, which calls this).
 *                The callback switches the state machine to SettingsScreen.
 */
@Composable
fun GameScreen(onSetty: () -> Unit) {
    // Orientation is now locked at the ThuruummmApp root (P3c fix). No LockOrientation call here.
    // ThuruummmApp maps Screen.Game → SCREEN_ORIENTATION_LANDSCAPE.

    val context  = LocalContext.current
    val density  = LocalDensity.current

    // ── State holder ─────────────────────────────────────────────────────────────────────────
    //
    // `remember { GameState(...) }` — lifecycle is this composable's lifetime. No ViewModel:
    // the orientation is locked (no Activity recreation across Start→Game), no data layer,
    // no business logic to survive config change. Plain @Stable holder per ARCHITECTURE.md §3.
    val haptics = remember { ThuruummmHaptics(context.applicationContext) }
    // Inject the android logger HERE (the android edge); GameState itself stays JVM-pure. Placement +
    // lift-edge telemetry surfaces on `adb logcat -s THRUM` — the on-thumb tuning aid for the gesture.
    val state   = remember { GameState(haptics = haptics, log = { android.util.Log.d("THRUM", it) }) }

    // ── Shake animation state ─────────────────────────────────────────────────────────────────
    //
    // Only ONE piece of Compose state for the shake: the current pixel offset written by the
    // unified frame-clock loop below. Removed the former `shakeStartNanos` (mutableLongStateOf)
    // because latching it from System.nanoTime() while reading elapsed from withFrameNanos { it }
    // subtracted two different clocks — MonotonicFrameClock is implementation-defined and NOT
    // guaranteed equal to System.nanoTime() (P0 / P2a fix).
    // Verified MonotonicFrameClock time-base note: developer.android.com/reference/kotlin/
    // androidx/compose/runtime/MonotonicFrameClock (2026-06-13).
    var shakeOffsetPx by remember { mutableStateOf(Offset.Zero) }

    // ── Single unified frame-clock loop (replaces the former two-loop design) ─────────────────
    //
    // Previously there were TWO independent LaunchedEffect(Unit) withFrameNanos loops running in
    // this composable: one for the shake decay and GameLoop's own loop. That was redundant and
    // broke the shake because the shake start was latched from System.nanoTime() while the loop
    // advanced on the MonotonicFrameClock — two unrelated time bases (P0 + P2a).
    //
    // Fix: this single loop drives BOTH the GameLoop tick (via state.tick) AND the shake update.
    // Both use the SAME `frameTimeNanos` from the same `withFrameNanos` call, so elapsed is always
    // computed within a consistent time base: (currentFrameNanos - shakeStartFrameNanos).
    //
    // GameLoop.kt is kept as a @Composable that holds the DisposableEffect(state) for onLeave()
    // cleanup but no longer runs its own frame loop — see GameLoop.kt. The actual tick is called
    // here with the same frameTimeNanos this loop obtains, eliminating the second wakeup.
    //
    // LaunchedEffect(Unit) — constant key so this loop runs for the GameScreen's lifetime.
    // Verified: developer.android.com/reference/kotlin/androidx/compose/runtime/LaunchedEffect.composable
    // (verified 2026-06-13, RESEARCH-NATIVE.md §4).
    LaunchedEffect(Unit) {
        // 0 = no active shake. Set from the frame clock, never System.nanoTime() (P0 fix).
        var shakeStartFrameNanos = 0L
        // The snapshot.collapse is non-null for exactly ONE frame (the placement frame that triggered
        // the collapse). We cache it here so the shake can continue drawing for SHAKE_DURATION_MS
        // even after the snapshot has cleared the collapse field to null on subsequent frames.
        var cachedCollapse: CollapseView? = null

        while (isActive) {
            val frameTimeNanos = withFrameNanos { it }

            // ── game tick ──────────────────────────────────────────────────────────────────────
            state.tick(frameTimeNanos)

            // ── shake update ───────────────────────────────────────────────────────────────────
            //
            // Detect the leading edge of a collapse from the snapshot the tick just published.
            // The snapshot.collapse is non-null for exactly one frame (the placement frame).
            // We latch shakeStartFrameNanos HERE, on the frame clock — same time base as the
            // elapsed computation below. This is the P0 fix: no System.nanoTime() anywhere.
            val snap     = state.snapshot.value
            val collapse = snap.collapse

            if (collapse != null && shakeStartFrameNanos == 0L) {
                // Leading edge: start the shake, anchored to this frame's nanos.
                // Cache the CollapseView so subsequent frames can compute amplitude even after
                // the snapshot has cleared collapse to null (one-frame non-null contract).
                shakeStartFrameNanos = frameTimeNanos
                cachedCollapse       = collapse
            }

            shakeOffsetPx = if (shakeStartFrameNanos > 0L) {
                val elapsedMs = (frameTimeNanos - shakeStartFrameNanos) / 1_000_000L
                val active    = cachedCollapse
                if (active != null && elapsedMs < SHAKE_DURATION_MS) {
                    computeShake(active, elapsedMs)
                } else {
                    // Shake complete: reset for the next collapse.
                    shakeStartFrameNanos = 0L
                    cachedCollapse       = null
                    Offset.Zero
                }
            } else {
                Offset.Zero
            }
        }
    }

    // NOTE: GameLoop is called for its DisposableEffect(state) cleanup (onLeave on screen exit).
    // Its own LaunchedEffect frame loop has been removed — the loop above calls state.tick directly
    // so there is exactly ONE withFrameNanos loop per GameScreen (P2a fix).
    GameLoop(state)

    // ── Layout ───────────────────────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Track per-pointer state for selecty/navvy detection.
                    // selecty = a brief single-finger tap (< SELECTY_HOLD_MS, no drift).
                    // navvy   = a whole-hand slide WITHOUT a flourish (the classifier handles this
                    //           once the game loop sees the flourish-less drag; we only need to pan
                    //           the camera when the pointer stream looks like a navvy).
                    //
                    // P1 fix: track down position KEYED BY pointer id, not inferred from pressed.size.
                    // The old code only recorded lastSingleFingerPos when pressed.size == 1, which
                    // dropped taps whose down+up were batched (pressed.size briefly != 1 between events).
                    //
                    // P0b fix: the old navvy branch fired on pressed.size >= 2 && Move, which panned
                    // the camera on EVERY multi-finger move event — including during all minting
                    // gestures (which are 4–5 fingers and always Move). Fix: require a sustained hold
                    // window before the first pan fires. Only pan after the centroid has been stable
                    // (no finger-count change) for NAVVY_HOLD_FRAMES consecutive frames.

                    // P1 fix: map pointer id → down position for reliable single-finger press detection.
                    val downPositions = mutableMapOf<Long, Offset>()    // key = PointerId.value
                    val downTimes     = mutableMapOf<Long, Long>()      // key = PointerId.value, value = System.nanoTime() at down
                    // selecty is a LONG PRESS: each held finger fires its slot-move at most once. Track
                    // which down-fingers already fired so a sustained hold does not repeat-fire every event.
                    val selectyFiredIds = mutableSetOf<Long>()

                    var lastPanCentroid: Offset?  = null
                    // navvy hold gate: count consecutive multi-finger-move frames before panning starts.
                    var navvyHoldFrames: Int      = 0
                    // Track pressed count to reset the hold gate on any finger-count change.
                    var lastNavvyPressedCount: Int = 0

                    while (true) {
                        val event: PointerEvent = awaitPointerEvent()

                        val allChanges = event.changes
                        val pressed    = allChanges.filter { it.pressed }
                        val released   = allChanges.filter { !it.pressed && it.previousPressed }

                        // P1 fix: record down position keyed by pointer id on changedToDown().
                        // `changedToPressed` (previousPressed == false && pressed == true) is the
                        // down event regardless of how many other fingers are on the glass at that moment.
                        // Verified: PointerInputChange.previousPressed + pressed fields, same source as
                        // the existing comment in this file (developer.android.com, 2026-06-13).
                        for (change in allChanges) {
                            if (!change.previousPressed && change.pressed) {
                                // Finger just came down.
                                downPositions[change.id.value] = change.position
                                downTimes[change.id.value]     = System.nanoTime()
                            }
                        }

                        // Map every change to a plain Finger and deposit for the game loop.
                        val fingers = allChanges.map { c ->
                            Finger(
                                id      = c.id.value,
                                x       = c.position.x,
                                y       = c.position.y,
                                pressed = c.pressed,
                            )
                        }
                        state.onFingers(fingers)

                        when {
                            // Single finger HELD still → selecty LONG PRESS (Karl: more intuitive than a tap,
                            // and a stray tap no longer moves the slot). Fires ONCE per press (tracked by id)
                            // the instant the hold crosses SELECTY_MIN_HOLD_MS with little drift, WHILE the
                            // finger is still down — so it reads as a deliberate press-and-hold. A real held
                            // finger keeps emitting micro-move events (sensor jitter) that re-enter this branch;
                            // the lift branch below is the fallback for a perfectly dead-still finger that
                            // emits nothing until it leaves. Direction is resolved from the press position
                            // relative to the field centre (press left → slot moves left, etc.).
                            pressed.size == 1 && released.isEmpty() -> {
                                val f  = pressed.first()
                                val id = f.id.value
                                if (id !in selectyFiredIds) {
                                    val down  = downPositions[id]
                                    val downT = downTimes[id]
                                    if (down != null && downT != null) {
                                        val driftPx = (f.position - down).getDistance()
                                        val holdMs  = (System.nanoTime() - downT) / 1_000_000L
                                        if (driftPx < SELECTY_MAX_DRIFT_PX && holdMs >= SELECTY_MIN_HOLD_MS) {
                                            val snap   = state.snapshot.value
                                            val cellPx = with(density) { CELL_DP.dp.toPx() }
                                            state.selectCell(
                                                pressToCell(down, size.width.toFloat(), size.height.toFloat(), cellPx, snap.panCellX, snap.panCellY),
                                            )
                                            selectyFiredIds.add(id)
                                        }
                                    }
                                }
                            }

                            // Single finger lifted → selecty FALLBACK fire for a dead-still finger that never
                            // emitted a move (so the held branch never ran), plus per-finger cleanup. If the
                            // press already fired while held, this only cleans up.
                            pressed.isEmpty() && released.size == 1 -> {
                                val liftedChange = released.first()
                                val id    = liftedChange.id.value
                                val down  = downPositions.remove(id)
                                val downT = downTimes.remove(id) ?: 0L
                                val up    = liftedChange.position
                                val alreadyFired = selectyFiredIds.remove(id)
                                if (!alreadyFired && down != null) {
                                    val driftPx = (up - down).getDistance()
                                    val holdMs  = (System.nanoTime() - downT) / 1_000_000L
                                    if (driftPx < SELECTY_MAX_DRIFT_PX && holdMs >= SELECTY_MIN_HOLD_MS) {
                                        val snap   = state.snapshot.value
                                        val cellPx = with(density) { CELL_DP.dp.toPx() }
                                        state.selectCell(
                                            pressToCell(down, size.width.toFloat(), size.height.toFloat(), cellPx, snap.panCellX, snap.panCellY),
                                        )
                                    }
                                }
                            }

                            // 2–3-finger move → navvy candidate (the swipey/navvy disambiguation fix).
                            //
                            // navvy (pan the view) and swipey (mint a brick) are BOTH whole-hand slides —
                            // the only real-time signal that separates them is FINGER COUNT. Minting
                            // gestures require the 4-finger floor (every card's GestureSpec.minFingers = 4),
                            // so we reserve 4–5 fingers exclusively for minting and let a SMALLER 2–3-finger
                            // drag pan the view. A 4–5-finger swipe therefore never enters this branch and
                            // can no longer pan the camera while it places a brick (the bug Karl hit: the
                            // whole field slid on every swipe because panBy accumulates and is never reset).
                            //
                            // The earlier NAVVY_HOLD_FRAMES hold-gate alone was insufficient — a committed
                            // swipey holds a STABLE 5-finger count through its slide, so it reached the gate
                            // and panned. The finger-count ceiling is the clean separator; the hold-gate is
                            // kept on top to debounce jitter on a genuine 2–3-finger pan.
                            //
                            // The fully-correct production design (route navvy through GameState only after
                            // the classifier rules out a mint on the flourish) needs a classifier API change;
                            // the count split is the right pragmatic disambiguation for now.
                            pressed.size in 2..NAVVY_MAX_FINGERS && released.isEmpty() &&
                                    event.type == PointerEventType.Move -> {

                                // Reset the hold gate if the finger count changed.
                                if (pressed.size != lastNavvyPressedCount) {
                                    navvyHoldFrames = 0
                                    lastPanCentroid = null
                                }
                                lastNavvyPressedCount = pressed.size
                                navvyHoldFrames++

                                if (navvyHoldFrames >= NAVVY_HOLD_FRAMES) {
                                    val centroid = Offset(
                                        x = pressed.map { it.position.x }.average().toFloat(),
                                        y = pressed.map { it.position.y }.average().toFloat(),
                                    )
                                    val prev = lastPanCentroid
                                    if (prev != null) {
                                        val dx = centroid.x - prev.x
                                        val dy = centroid.y - prev.y
                                        // Only forward as navvy if the move is above a noise threshold.
                                        if ((dx * dx + dy * dy) > NAVVY_NOISE_PX * NAVVY_NOISE_PX) {
                                            // Convert px delta → cell delta for GameState.panBy.
                                            // CELL_DP is the canonical constant from FieldCanvas (internal)
                                            // — shared so the navvy px→cell scale matches the render scale
                                            // exactly, eliminating the former CELL_DP_APPROX divergence (P2d).
                                            val cellPx = with(density) { CELL_DP.dp.toPx() }
                                            // y-flip note: screen-down (positive dy) → engine-up (negative dCellY).
                                            // FieldCanvas.cellToScreen also applies the y-flip in coordinate
                                            // mapping (h - (engineY+1)*cellPx + panCellY*cellPx). These are
                                            // complementary operations in different spaces, not double-negation.
                                            // Verify sign correctness on device (P3b — cannot resolve statically).
                                            state.panBy(dx / cellPx, -dy / cellPx)
                                        }
                                    }
                                    lastPanCentroid = centroid
                                }
                            }

                            // All fingers lifted → reset all navvy and selecty tracking.
                            pressed.isEmpty() -> {
                                lastPanCentroid      = null
                                navvyHoldFrames      = 0
                                lastNavvyPressedCount = 0
                                selectyFiredIds.clear()  // defensive: no held single finger remains
                                // Do NOT clear downPositions/downTimes here — we already removed entries
                                // in the released loop above. Any stale entries from a held-but-not-lifted
                                // finger are cleaned by the per-pointer remove on lift.
                            }

                            else -> { /* other events — no action needed */ }
                        }
                    }
                }
            },
    ) {
        FieldCanvas(
            snapshot    = state.snapshot,
            shakeOffset = shakeOffsetPx,
        )
    }
}

// ── Constants ──────────────────────────────────────────────────────────────────────────────────

/** A selecty press must stay within this many pixels of its down point to count as a still hold (not a
 *  drag). A touch more tolerant than a tap since a long press accrues more settle. */
private const val SELECTY_MAX_DRIFT_PX  = 36f

/** A single finger must be held at least this long to fire selecty — a deliberate LONG PRESS, not a tap.
 *  (~Android long-press feel; short enough to stay snappy for a fidget toy.) Tuned on the thumb. */
private const val SELECTY_MIN_HOLD_MS   = 350L

/** Navvy centroid move must exceed this many pixels to avoid noise jitter. */
private const val NAVVY_NOISE_PX        = 4f

/**
 * Max fingers for a navvy (view-pan) drag. Must stay STRICTLY BELOW the minting finger floor
 * (every card's [com.thrum.deck.GestureSpec.minFingers] = 4) so a 4–5-finger swipey can never be
 * read as a pan. 2–3 fingers pan the view; 4–5 fingers mint a brick.
 */
private const val NAVVY_MAX_FINGERS     = 3

/**
 * Consecutive multi-finger-move frames required before navvy panning starts (P0b fix).
 * A minting gesture (4–5 fingers, brief burst) rarely holds a stable count this long before
 * its flourish fires. A deliberate whole-hand slide reaches this threshold naturally.
 * At 60fps: ~5 frames ≈ 83ms of stable multi-finger contact before pan fires.
 * First guess — tuned on device.
 */
private const val NAVVY_HOLD_FRAMES     = 5

// ── Selecty press → grid cell mapping ──────────────────────────────────────────────────────────

/**
 * Invert a screen press position to the grid [Cell] under it — the EXACT inverse of
 * [FieldCanvas]'s `cellToScreen`, so a long press picks the cell the player is actually pointing at:
 *
 *   cellToScreen(cell).x = (screenW/2 - panCellX*cellPx) + cell.x*cellPx      (cell LEFT edge)
 *   cellToScreen(cell).y = screenH - (cell.y + 1 - panCellY)*cellPx           (cell TOP edge, y-flip)
 *
 * Solving for the cell that CONTAINS ([press]):
 *   cell.x = floor((press.x - screenW/2)/cellPx + panCellX)
 *   cell.y = floor((screenH - press.y)/cellPx + panCellY)
 *
 * [GameState.selectCell] then accepts it only if it is a legal build slot. The pan is read from the
 * same snapshot the Canvas draws, so press→cell and cell→screen always agree (no scale divergence).
 */
private fun pressToCell(
    press: Offset,
    screenW: Float,
    screenH: Float,
    cellPx: Float,
    panCellX: Float,
    panCellY: Float,
): Cell {
    val cellX = floor((press.x - screenW / 2f) / cellPx + panCellX).toInt()
    val cellY = floor((screenH - press.y) / cellPx + panCellY).toInt()
    return Cell(cellX, cellY)
}
