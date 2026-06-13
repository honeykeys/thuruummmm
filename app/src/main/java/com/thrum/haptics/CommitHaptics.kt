package com.thrum.haptics

/**
 * The two-beat commit haptic, beat one: **thur**.
 *
 * DESIGN.md §"The flourish + the commit haptic": every placement reads by feel as
 * *flourish lands (thur) → brick mints (rummmm)*. The `thur` is the small, **uniform** haptic
 * fired the instant the flourish registers — the SAME for every gesture, every card. It confirms
 * one thing only: *the gesture landed*. The card's character lives in the second beat (`rummmm`),
 * authored per-card in the Deck; never here.
 *
 * It is authored once, in this one place, so the uniformity is structural — a single source of the
 * "done" feel the player learns and applies everywhere. The whole deck shares this exact pulse.
 *
 * It is deliberately the SMALL beat of the heartbeat: one light, crisp tick. Distinct from every
 * card's larger `rummmm`, and a different order of magnitude from the THRUUMMMM collapse. The
 * small-then-large shape (thur → rummmm) is the heartbeat of a placement; the THRUUMMMM is the
 * reward, a separate event entirely.
 *
 * Authored as a [Haptic.Composed] of one [Primitive.TICK] note:
 *  - TICK is "light crisp, for repetitive dynamic feedback" — exactly a confirmation pulse, and
 *    cheap enough not to step on the rummmm that follows ~tens of ms later.
 *  - TICK (id=7) is API 30; minSdk is 31, so the API floor is cleared. Hardware support is still
 *    gated at play time by [HapticEngine] (arePrimitivesSupported), which substitutes a near
 *    equivalent if a given motor lacks it — so the thur always fires *something*.
 *  - Verified: developer.android.com/reference/android/os/VibrationEffect.Composition (2026-06-13).
 *
 * Scale 0.35 sits below every card's first-beat scale in [com.thrum.deck.Deck] (tappy 0.55,
 * twisty spin 0.70), so beat one reads as the lighter of the two by amplitude as well as by
 * primitive. Tuned on the thumb, not gospel.
 */
object CommitHaptics {

    /** Beat one. Uniform across every card. The "the gesture landed" pulse. */
    val THUR: Haptic.Composed = haptic("thur") {
        tick(scale = 0.35f)
    }
}
