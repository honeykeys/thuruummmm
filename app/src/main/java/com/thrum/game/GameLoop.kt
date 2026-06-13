package com.thrum.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Lifecycle guard for the game session — the composable that wires [GameState.onLeave] to screen
 * exit (ARCHITECTURE.md §3/§4, C6).
 *
 * ── Design change (P0 / P2a fix) ─────────────────────────────────────────────────────────────────
 *
 * Originally this composable hosted its own `LaunchedEffect(Unit) { while(isActive) withFrameNanos
 * { it } }` frame loop and called `state.tick(frameTimeNanos)` from inside it. GameScreen ran a
 * SECOND `LaunchedEffect(Unit)` loop for the shake-decay animation — two independent loops both
 * waking every frame.
 *
 * That design had two defects:
 *
 *  1. **P0 — wrong time base.** GameScreen latched `shakeStartNanos = System.nanoTime()` at the
 *     collapse moment but the shake loop computed `elapsed = withFrameNanos { it } - shakeStartNanos`.
 *     `withFrameNanos` uses the MonotonicFrameClock, whose time base is implementation-defined and
 *     NOT guaranteed equal to `System.nanoTime()` (official note:
 *     developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock 2026-06-13).
 *     The subtraction produced a garbage delta; the shake never rendered.
 *
 *  2. **P2a — redundant wakeup.** Two `withFrameNanos` loops woke every frame for work that could
 *     share one frame-time value.
 *
 * Both are fixed by moving `state.tick` into the SINGLE `LaunchedEffect(Unit)` that GameScreen
 * already owns for the shake loop — one `withFrameNanos` call per frame, whose returned nanos feeds
 * both `state.tick` and the shake-elapsed computation. The two values are guaranteed on the same
 * clock because they come from the same call.
 *
 * This composable is now ONLY the lifecycle guard: it places a `DisposableEffect(state)` that calls
 * `state.onLeave()` when GameScreen leaves composition (screen exit cancels in-flight vibration).
 * It emits no frame loop, no UI, and holds no remember-ed state.
 *
 * ── How to find the tick call ────────────────────────────────────────────────────────────────────
 *
 * `state.tick(frameTimeNanos)` is now called inside the `LaunchedEffect(Unit)` in `GameScreen.kt`.
 * Search for "state.tick" in that file. The rest of the pipeline (gesture classify → commit →
 * haptics → snapshot publish) is unchanged.
 *
 * ── Testability is unchanged ─────────────────────────────────────────────────────────────────────
 *
 * JVM tests drive `state.tick(nanos)` directly — no loop, no device. Nothing here changes that.
 */
@Composable
fun GameLoop(state: GameState) {
    // Stop any in-flight vibration when the screen leaves composition, even if cancellation
    // races the next tick. `DisposableEffect(state)` re-runs if `state` ever changes identity,
    // which for a `remember { GameState(...) }` is never — the key is stable for the lifetime
    // of the screen.
    // Verified: DisposableEffect cleanup semantics — developer.android.com/develop/ui/compose/side-effects
    // (page updated 2026-06-04).
    DisposableEffect(state) {
        onDispose { state.onLeave() }
    }
}
