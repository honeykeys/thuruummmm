package com.thrum.haptics

import com.thuruummm.physics.CollapseResult
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The big one: **THRUUMMMM** — the haptic of a building coming down. The GOAL of the toy
 * (DESIGN.md §"What it is"). Not a commit beat; the *reward*. A separate event from the
 * thur → rummmm heartbeat, an order of magnitude larger, and it priority-locks the motor so
 * rapid drumming cannot cut the crash short (see [ThuruummmHaptics.thruummm]).
 *
 * This object is the **magnitude → richness map**, and nothing else. Pure Kotlin, zero Android:
 * it consumes a physics [CollapseResult] and synthesises a [Haptic.Wave] whose feel scales with
 * how gloriously the structure fell. It is unit-testable on the JVM with no device — feed it a
 * hand-built CollapseResult, assert the wave grows with magnitude.
 *
 * ── Why a Wave, not a Composition of primitives ──────────────────────────────────────────────
 *
 * The collapse is a *sustained tumble ending in a slam* — the one feel the discrete primitives
 * cannot draw (RESEARCH-NATIVE.md §5: "sustained feel the primitives cannot draw (the crash
 * rumble)"). A hand-drawn (duration, amplitude) envelope is the right track. The existing
 * HapticLibrary.crash proves the shape; this generalises it so the *same* fall reads bigger when
 * the physics says it was bigger. createWaveform(timings, amplitudes, repeat = -1) plays the
 * envelope once. Verified: developer.android.com/reference/android/os/VibrationEffect (2026-06-13).
 *
 * ── What "richness" means, mapped from the physics ───────────────────────────────────────────
 *
 * PHYSICS.md / [CollapseResult.magnitude] already encodes the design law "reward complexity, not
 * size": magnitude = fell × rings × variety. A tall plain tower (big `fell`, 1 ring, 1 material)
 * scores low; a tangled structure (many rings, many materials) scores high — *for free*, because
 * rings and variety multiply. We do not re-derive that law here; we *honour its shape* by spending
 * the three components on three different felt dimensions, so a complex fall does not merely last
 * longer — it reads differently in the hand:
 *
 *   - **rings → number of tumble pulses.** Each cascade ring is one "domino wave"; we play one
 *     decaying buzz per ring (clamped), so a deep cascade literally tumbles longer in the thumb.
 *   - **fell count → body / loudness of the tumble.** More bricks falling = higher base amplitude
 *     across the tumble. This is the "size" dimension — present, but it only sets the tumble's
 *     loudness, never the structure of the feel. It is read directly from `fell` (on its own log
 *     curve), independent of the slam, so two falls of equal magnitude but different `fell` read
 *     differently in the body of the tumble.
 *   - **material variety → texture.** Variety raises the per-pulse amplitude *spread* (alternating
 *     harder/softer buzzes) so a mixed-material collapse feels jagged where a single-material one
 *     feels uniform. Perception guidance: scales that differ by a ratio ≥ ~1.4 are tellable apart
 *     (developer.android.com/develop/ui/views/haptics/custom-haptic-effects, 2026-06-13).
 *
 * The final **slam** — one long full-bodied buzz after the tumble — is always present (every
 * collapse ends in a landing) and its amplitude/length scale with overall magnitude (all three
 * factors), so the slam is where "how gloriously it fell" lands while the tumble body carries "how
 * much fell". That slam is the punctuation the player chases.
 *
 * All numbers are first-guess, tuned on the thumb. The *shape* (tumble pulses → slam, scaled by
 * the three components) is the design; the constants are knobs.
 */
object Thruummm {

    // ── Tuning knobs (felt, not gospel) ──────────────────────────────────────────────────────

    /** Tumble pulses are capped so even a vast cascade stays a *crash*, not an endless rattle. */
    private const val MAX_TUMBLE_PULSES = 7

    /** A single-ring collapse still tumbles a little before it slams — minimum drama. */
    private const val MIN_TUMBLE_PULSES = 2

    /** Amplitude ceiling for the android waveform track (0..255). */
    private const val AMP_MAX = 255

    /** Pulse amplitudes never drop below this — a tumble you can still feel at its quietest. */
    private const val AMP_FLOOR = 70

    /** Each tumble pulse is this long; the gaps between them shorten as the cascade accelerates. */
    private const val PULSE_MS = 42L

    /** The longest inter-pulse gap (early, slow tumble) and the shortest (late, fast). >=35ms keeps
     *  pulses individually discernible per the perception guidance above. */
    private const val GAP_MS_SLOW = 55L
    private const val GAP_MS_FAST = 35L

    /** The final slam: a long full-bodied buzz. Length grows with magnitude between these bounds. */
    private const val SLAM_MS_MIN = 220L
    private const val SLAM_MS_MAX = 520L

    /**
     * Map a physics [collapse] to its THRUUMMMM haptic.
     *
     * Never returns null — every collapse is felt. A degenerate collapse (a single brick, one
     * ring) still yields a small, honest THRUUMMMM rather than silence; the magnitude only sets
     * how *glorious* it is.
     */
    fun forCollapse(collapse: CollapseResult): Haptic.Wave =
        forComponents(
            fell = collapse.fell.size,
            rings = collapse.rings,
            variety = collapse.materials.size,
            magnitude = collapse.magnitude,
        )

    /**
     * The pure core, exposed for unit tests that build component values directly without
     * constructing a whole [CollapseResult]/[Grid]. Same map [forCollapse] uses.
     *
     * @param fell      bricks that fell (>= 1 for a real collapse)
     * @param rings     cascade rings (>= 1)
     * @param variety   distinct materials involved (>= 1)
     * @param magnitude fell × rings × variety (passed in so the map and the physics agree on it)
     */
    fun forComponents(fell: Int, rings: Int, variety: Int, magnitude: Double): Haptic.Wave {
        // --- overall loudness, 0..1, from total magnitude on a log curve ---
        // log so the first few bricks add a lot of feel and a 200-brick tower does not peg the
        // motor instantly; tangled mid-size collapses already feel huge, which is the point.
        val intensity = (ln(1.0 + max(0.0, magnitude)) / ln(1.0 + MAGNITUDE_SOFT_CAP))
            .coerceIn(0.0, 1.0)

        // --- rings → how many tumble pulses ---
        val pulses = rings.coerceIn(MIN_TUMBLE_PULSES, MAX_TUMBLE_PULSES)

        // --- variety → texture: how much harder/softer alternating pulses swing ---
        // 1 material = flat (no swing); more variety = wider jagged swing, capped.
        val textureSwing = (min(variety, 4) - 1) * 0.12  // 0.0, 0.12, 0.24, 0.36

        // --- fell count → body: base amplitude of the TUMBLE, on its own log curve ---
        // The KDoc design law: "fell count → body / loudness of the tumble" — this is the SIZE
        // dimension, and it must actually read `fell`, not be a second alias of magnitude. We drive
        // the tumble body from `fell` alone (a tall plain tower of many bricks tumbles *heavy* even
        // though its magnitude is modest), and reserve overall `intensity` (= magnitude, all three
        // factors) for the slam below — so the tumble carries "how much fell" and the slam carries
        // "how gloriously". Two falls of equal magnitude but different `fell` now feel different in
        // the body of the tumble, exactly as the doc promises. Log-scaled so the first bricks add the
        // most body and a vast tower does not peg the tumble before the slam even lands.
        val body = (ln(1.0 + max(0, fell)) / ln(1.0 + FELL_SOFT_CAP)).coerceIn(0.0, 1.0)
        val tumbleBaseAmp = lerp(AMP_FLOOR.toDouble(), AMP_MAX * 0.72, body)

        val timings = ArrayList<Long>(pulses * 2 + 2)
        val amps = ArrayList<Int>(pulses * 2 + 2)

        // The tumble: `pulses` decaying buzzes, each followed by a tightening gap. Earlier pulses
        // are louder (the first dominoes hit hardest); a variety swing makes alternate pulses
        // jagged so a mixed-material fall reads textured, not smooth.
        for (i in 0 until pulses) {
            val decay = 1.0 - (i.toDouble() / pulses) * 0.55          // 1.0 → ~0.45 across the tumble
            val swing = if (i % 2 == 0) 1.0 + textureSwing else 1.0 - textureSwing
            val amp = (tumbleBaseAmp * decay * swing)
                .roundToInt()
                .coerceIn(AMP_FLOOR, AMP_MAX)

            val gap = lerpLong(GAP_MS_SLOW, GAP_MS_FAST, i.toDouble() / max(1, pulses - 1))

            timings += PULSE_MS; amps += amp        // the buzz
            timings += gap;      amps += 0          // the gap (amplitude 0 = motor off)
        }

        // The slam: one long full-bodied buzz — the landing every collapse ends on. Its length and
        // amplitude scale with overall intensity; even the smallest collapse gets a real slam.
        val slamMs = lerpLong(SLAM_MS_MIN, SLAM_MS_MAX, intensity)
        val slamAmp = lerp(AMP_MAX * 0.80, AMP_MAX.toDouble(), intensity)
            .roundToInt()
            .coerceIn(AMP_FLOOR, AMP_MAX)
        timings += slamMs; amps += slamAmp

        return Haptic.Wave(
            label = "thruummm",
            timings = timings.toLongArray(),
            amplitudes = amps.toIntArray(),
        )
    }

    /** Magnitude beyond which slam intensity is essentially saturated.
     *
     *  Sized to the magnitudes the physics *actually* emits, not a guess. magnitude = fell × rings ×
     *  variety; the bench's own "vast" sample is 40 × 7 × 3 = 840, and a large build can clear a
     *  thousand. A cap of 120 (the prior value) pegged intensity at ~1.0 by a 5×4×3≈60 tangle, so the
     *  entire top of the range — the gloriously-large collapses the toy is built to reward — flattened
     *  into one feel, defeating DESIGN's "tangled ambition rings like a bell". 1200 keeps the slam
     *  growing across the real upper range (ln-curve: a 60-tangle reads ~0.58, an 840-vast ~0.95) and
     *  still saturates only the genuinely apocalyptic. A knob, now set to the right order of magnitude.
     *
     *  Tune on the thumb against the physics' true magnitude distribution. */
    private const val MAGNITUDE_SOFT_CAP = 1200.0

    /** Brick count beyond which the tumble *body* is essentially saturated (the [body] factor's log
     *  cap). Smaller than [MAGNITUDE_SOFT_CAP] because this is one factor, not the product: a tumble
     *  of ~80 falling bricks already feels maximally heavy in the hand; more bricks ring through the
     *  slam (via magnitude), not a louder tumble. A knob, tuned on the thumb. */
    private const val FELL_SOFT_CAP = 80.0

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)
    private fun lerpLong(a: Long, b: Long, t: Double): Long =
        (a + (b - a) * t.coerceIn(0.0, 1.0)).roundToInt().toLong()
}
