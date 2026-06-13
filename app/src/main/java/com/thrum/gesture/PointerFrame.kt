package com.thrum.gesture

/**
 * An immutable snapshot of every finger on the glass at one display frame, stamped with the
 * frame time in nanoseconds.
 *
 * The UI layer builds one of these per `withFrameNanos` tick from the live Compose pointer stream
 * (the frame-clock nanos go straight into [timeNanos]); the classifier reads a rolling window of
 * them. Plain value — no Compose, no Android — so it crosses the JVM-test seam (ARCHITECTURE.md §6).
 *
 * `withFrameNanos`'s `frameTimeNanos: Long` is the source of [timeNanos]; signature verified current
 * 2026-06-13 (developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock,
 * RESEARCH-NATIVE.md §4). Time is carried as DATA on the frame, never read from a clock inside the
 * classifier — that keeps the classifier and the flourish detector deterministic and unit-testable
 * with no virtual-time machinery: a test simply authors frames with the timestamps it wants.
 *
 * @param fingers   Every finger present this frame. May be empty (all lifted). Order is not
 *                  significant; the classifier treats fingers as a set keyed by [Finger.id].
 * @param timeNanos Monotonic frame time in nanoseconds (from `withFrameNanos`). Strictly increasing
 *                  across a stream; used only as a relative duration source (flourish hold timing).
 */
data class PointerFrame(
    val fingers: List<Finger>,
    val timeNanos: Long,
) {
    /** Fingers currently touching (`pressed == true`). The "active hand" this frame. */
    val pressed: List<Finger> get() = fingers.filter { it.pressed }

    /** Count of fingers touching this frame. The gating quantity ([GestureSpec.minFingers]). */
    val pressedCount: Int get() = fingers.count { it.pressed }
}

/**
 * A fixed-capacity rolling buffer of [PointerFrame]s — the live gesture-in-progress window the
 * classifier inspects each tick.
 *
 * Append-and-evict: when full, the oldest frame is dropped. This bounds memory and work per frame
 * regardless of how long a finger lingers, and means the classifier always reasons over a recent
 * window rather than an unbounded history. The game loop drains the Compose pointer events into one
 * [PointerFrame] per tick, pushes it here, then hands [frames] to the classifier.
 *
 * Not thread-safe by design: it is touched only from the single frame-loop coroutine
 * (RESEARCH-NATIVE.md §4 — one `withFrameNanos` loop owns the per-frame state). Plain Kotlin,
 * JVM-testable.
 *
 * @param capacity Maximum frames retained. Sized to comfortably cover the longest expected gesture
 *                 at display refresh (default 120 ≈ 2 s at 60fps / 1 s at 120fps) — a first guess,
 *                 tuned on the thumb. Must be >= 2 (a feature needs a first and a last frame).
 */
class PointerBuffer(val capacity: Int = 120) {
    init { require(capacity >= 2) { "PointerBuffer capacity must be >= 2, got $capacity" } }

    private val ring = ArrayDeque<PointerFrame>(capacity)

    /** Append [frame], evicting the oldest if at [capacity]. */
    fun push(frame: PointerFrame) {
        if (ring.size == capacity) ring.removeFirst()
        ring.addLast(frame)
    }

    /**
     * The current window, oldest-first. A defensive copy — callers may not mutate the buffer.
     *
     * HOT-PATH NOTE (for C6 / GameLoop integration): this copies up to [capacity] frames per call,
     * so calling it every `withFrameNanos` tick is a steady per-frame allocation on the 60–120 Hz
     * loop. ARCHITECTURE.md §3 makes "no per-frame broad work" load-bearing. The defensive copy is the
     * correct DEFAULT for a value-returning API (the classifier and its JVM tests rely on the window
     * being an immutable snapshot they may hold), so the cost is paid at the CALL SITE, not here: C6
     * should classify only when a gesture may have just completed — cheaply gate on the flourish's
     * window-ends-empty precondition (a `last().pressedCount == 0` check on the live buffer) before
     * building the snapshot and invoking [GestureClassifier.classify]. Do not "optimise" this into
     * returning the live ring: that would hand callers a view that mutates under them next push.
     */
    fun frames(): List<PointerFrame> = ring.toList()

    /** Drop everything. Called after a commit fires, so the next gesture starts from a clean window. */
    fun clear() = ring.clear()

    /** Frames currently held. */
    val size: Int get() = ring.size

    /** True when no frames are buffered. */
    val isEmpty: Boolean get() = ring.isEmpty()
}
