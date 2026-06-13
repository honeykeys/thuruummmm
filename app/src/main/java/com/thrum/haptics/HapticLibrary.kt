package com.thrum.haptics

/**
 * The five block signatures — the hypothesis the thumb will falsify — plus the crash.
 *
 * Designed per the perception research (RESEARCH.md §2): rhythm is the primary dimension; a
 * redundant second dimension (which primitive + its scale) carries the identity. Never two
 * subtly-different rhythms — that is the mush failure mode. Predicted hardest pair to tell
 * apart: ember vs gust (both light, both fast) — separated here by primitive character
 * (rise + spin vs a fading tick train), not by amplitude alone.
 *
 * These numbers are a starting point, not gospel. They are tuned by the thumb, on the phone.
 */
object HapticLibrary {

    /** tap — light, the drumming block. one clean, light pulse. */
    val pebble = haptic("pebble") {
        tick(scale = 0.55f)
    }

    /** hold — heavy. two deep slabs, ~150 ms apart. */
    val stone = haptic("stone") {
        thud(scale = 1.0f)
        thud(scale = 0.85f, delay = 150)
    }

    /** flick up — springy + weak. a rising flutter that wants to topple. */
    val ember = haptic("ember") {
        quickRise(scale = 0.7f)
        spin(scale = 1.0f, delay = 30)
    }

    /** flick down — sticky. a single settling swell that decays. */
    val glue = haptic("glue") {
        slowRise(scale = 0.8f)
        quickFall(scale = 0.7f)
    }

    /** flick side — airy. a light sweep fading to nothing. */
    val gust = haptic("gust") {
        tick(scale = 0.9f)
        tick(scale = 0.6f, delay = 45)
        tick(scale = 0.35f, delay = 45)
        tick(scale = 0.18f, delay = 45)
    }

    /** the reward — a tumble of decreasing buzzes ending in one full-amplitude thud. */
    val crash = wave(
        "crash",
        55L to 170, 35L to 0,
        50L to 150, 35L to 0,
        45L to 130, 35L to 0,
        40L to 110, 35L to 0,
        35L to 90, 300L to 255,
    )

    /** the five discoverable block signatures (no crash). */
    val blocks: List<Haptic> = listOf(pebble, stone, ember, glue, gust)

    /** everything the bench can play. */
    val all: List<Haptic> = blocks + crash
}
