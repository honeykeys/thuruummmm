package com.thrum.gesture

import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-authored synthetic pointer streams for classifier tests — the JVM attack surface
 * (ARCHITECTURE.md §6: "Feed synthetic List<PointerFrame> into GestureClassifier").
 *
 * A real gesture is a sequence of frames; these builders generate the frame tracks for the canonical
 * choreographies (gather, twist, swipe) plus the flourish tail, so a test reads as the gesture it
 * describes rather than as a wall of coordinates. All pure Kotlin — no Android, no device.
 *
 * Convention: a built stream is a complete gesture ENDING in the default flourish (settle-then-lift),
 * unless a builder is explicitly told to omit it (`withFlourish = false`) to test sub-flourish gating.
 */
object SyntheticStream {

    const val FRAME_NS = 16_000_000L  // ~16ms — one frame at ~60fps

    /** A regular polygon of [count] fingers around ([cx],[cy]) at [radius], rotated by [phaseRad]. */
    fun ring(cx: Float, cy: Float, radius: Float, count: Int, phaseRad: Float = 0f): List<Finger> =
        (0 until count).map { i ->
            val a = phaseRad + (2.0 * Math.PI * i / count).toFloat()
            Finger(id = i.toLong(), x = cx + radius * cos(a), y = cy + radius * sin(a), pressed = true)
        }

    /** All [count] fingers lifted (pressed = false) at the same positions — the lift frame. */
    fun lifted(count: Int): List<Finger> =
        (0 until count).map { Finger(id = it.toLong(), x = 0f, y = 0f, pressed = false) }

    /**
     * Build a stream by interpolating finger rings between keyframes, then append a settle + lift
     * flourish tail. Each keyframe is (centroidX, centroidY, radius, phaseRad); [steps] frames are
     * emitted between consecutive keyframes.
     */
    fun stroke(
        count: Int,
        keys: List<FloatArray>,           // each: [cx, cy, radius, phaseRad]
        steps: Int = 6,
        withFlourish: Boolean = true,
        settleFrames: Int = 8,            // ~128ms still at 16ms/frame — clears the 90ms settle default
        startNs: Long = 0L,
    ): List<PointerFrame> {
        require(keys.size >= 1)
        val frames = mutableListOf<PointerFrame>()
        var t = startNs

        fun emit(fingers: List<Finger>) { frames += PointerFrame(fingers, t); t += FRAME_NS }

        // Interpolate through the keyframes.
        if (keys.size == 1) {
            val k = keys[0]
            repeat(steps) { emit(ring(k[0], k[1], k[2], count, k[3])) }
        } else {
            for (s in 0 until keys.size - 1) {
                val a = keys[s]; val b = keys[s + 1]
                repeat(steps) { i ->
                    val u = i / steps.toFloat()
                    emit(ring(lerp(a[0], b[0], u), lerp(a[1], b[1], u), lerp(a[2], b[2], u), count, lerp(a[3], b[3], u)))
                }
            }
            val last = keys.last()
            emit(ring(last[0], last[1], last[2], count, last[3]))
        }

        if (withFlourish) {
            // Settle: hold the final pose still for settleFrames (centroid speed = 0).
            val last = keys.last()
            repeat(settleFrames) { emit(ring(last[0], last[1], last[2], count, last[3])) }
            // Together-lift: all fingers gone on the very next frame (within the lift window).
            emit(lifted(count))
        }
        return frames
    }

    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
}
