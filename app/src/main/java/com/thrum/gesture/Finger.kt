package com.thrum.gesture

/**
 * One pointer's state at one instant — the atom of the gesture pipeline's input.
 *
 * This is a PLAIN Kotlin value with zero Android / Compose dependency. That is the load-bearing
 * choice of the whole `gesture/` package: the classifier consumes only [Finger] / [PointerFrame],
 * so it builds and unit-tests on the JVM (`./gradlew testDebugUnitTest`) against hand-authored
 * synthetic streams, no device, no emulator, no Compose runtime. (ARCHITECTURE.md §6.)
 *
 * The UI layer (ui/, C5) is the ONLY code that touches Compose pointer types. It adapts each
 * `androidx.compose.ui.input.pointer.PointerInputChange` into a [Finger] like so:
 *
 *   Finger(
 *       id      = change.id.value,        // PointerId.value: Long — stable per finger across a stroke
 *       x       = change.position.x,      // Offset.x: Float — pixels, origin top-left
 *       y       = change.position.y,      // Offset.y: Float
 *       pressed = change.pressed,         // Boolean — finger is down this event
 *   )
 *
 * PointerInputChange field set verified current 2026-06-13 against
 * developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/PointerInputChange
 * (id: PointerId, position: Offset, pressed: Boolean, uptimeMillis: Long, pressure: Float).
 *
 * @param id       Stable identifier for this finger across the gesture. Maps from Compose's
 *                 `PointerId.value` (Long). Distinct fingers have distinct ids within a frame.
 * @param x        Horizontal position in pixels (screen / element space, origin top-left).
 * @param y        Vertical position in pixels (y increases downward — screen coordinates).
 * @param pressed  True while the finger is touching. A finger that has lifted reports `pressed = false`.
 */
data class Finger(
    val id: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
)
