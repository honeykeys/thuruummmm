package com.thrum.gesture

import com.thrum.deck.Deck
import com.thrum.deck.GestureSpec
import com.thrum.deck.Movement
import com.thrum.deck.Thuruummm
import com.thuruummm.physics.Cell
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Turns a live multi-finger pointer stream into a recognised [Thuruummm] — or nothing.
 *
 * The pipeline's recognition stage (ARCHITECTURE.md §2/§4):
 *
 *   raw frames ─► [Flourish] gate ─► [GestureFeatureExtractor] ─► score vs every card.gesture ─► [Recognized]
 *
 * LOAD-BEARING DESIGN RULE (Karl): this classifier hard-codes NO gesture. It scores the measured
 * features against each card's [GestureSpec] by dispatching on the [Movement] sealed type. Adding a
 * card to [Deck.CARDS] changes nothing here — the new card is scored by the same geometry branches.
 * The ONLY exception is [Movement.Custom], the documented escape hatch (GestureSpec.kt): a custom card
 * needs a matching recogniser registered in [customRecognizers], one extra edit by deliberate design.
 *
 * Two contracts, never more (DESIGN.md, Thuruummm.kt):
 *   STRUCTURAL — a gesture must be well-formed (≥ minFingers fingers), classifiable, committed by the
 *                uniform flourish. Those are the only universals.
 *   NO VALUE   — the classifier never asserts what a card's shape, direction, or stats "should" be.
 *                It measures, then matches whatever the deck declares.
 *
 * Finger gate: every card carries [GestureSpec.minFingers] (DESIGN: 4–5). A stream whose tracked
 * finger count is below a card's minimum cannot match THAT card; a 1- or 2-finger stream matches no
 * card and returns null — single/two-finger input does not register.
 *
 * Pure Kotlin, JVM-testable against synthetic [PointerFrame] streams (ARCHITECTURE.md §6). No Android,
 * no Compose, no coroutines: time enters only as [PointerFrame.timeNanos] data, so a test authors the
 * exact timeline it wants and asserts deterministically.
 *
 * @param cards            The deck to recognise against. Defaults to [Deck.CARDS]; injectable so a
 *                         hostile test can drive a bespoke deck, and so the StartScreen can pass a
 *                         single-card deck (just "twisty") for the twist-to-start recogniser.
 * @param flourish         The commit strategy. Defaults to [Flourish.forDeck], which derives its
 *                         structural finger floor from THIS deck (one source of truth — see below);
 *                         swappable to retune the finish form (DESIGN open question) without touching
 *                         this class or the deck.
 * @param customRecognizers Dispatch table for [Movement.Custom] cards, keyed by `recognizerId`. Empty
 *                         by default — the three geometric families cover the whole current deck.
 *
 * ONE finger floor, not two. The DESIGN 4–5-finger floor ("a single finger does not register") is a
 * CARD/spec concern: it lives in each card's [GestureSpec.minFingers] and is enforced at the per-card
 * gate in [classify]. The flourish must also know a "full count reached" threshold to tell a together-
 * lift from a ragged trickle — but that threshold is DERIVED from the deck (the minimum minFingers any
 * card requires), not independently hardcoded. So retuning the deck's floor, or swapping the flourish
 * form, cannot desync two copies of the number: there is exactly one, owned by the deck.
 */
class GestureClassifier(
    private val cards: List<Thuruummm> = Deck.CARDS,
    private val flourish: Flourish = Flourish.forDeck(cards),
    private val customRecognizers: Map<String, CustomRecognizer> = emptyMap(),
) {

    /**
     * A purpose-built recogniser for a [Movement.Custom] card. Scores the features (and may inspect
     * the raw frames) against one bespoke choreography, returning a match in [0, 1].
     */
    fun interface CustomRecognizer {
        fun score(features: GestureFeatures, frames: List<PointerFrame>): Float
    }

    /**
     * Inspect the current window. Returns a [Recognized] iff the uniform flourish has fired AND some
     * card's spec matched within its tolerance; otherwise null (gesture still in progress, malformed,
     * sub-flourish, too few fingers, or no card matched).
     *
     * @param frames       The rolling window (oldest-first) — e.g. `PointerBuffer.frames()`.
     * @param targetCell   The working slot the placement commits to, from the game layer (selecty).
     *                     The classifier does not own board state; the cell is supplied, not computed.
     */
    fun classify(frames: List<PointerFrame>, targetCell: Cell): Recognized? {
        // 1. The uniform flourish gates EVERY card. Nothing commits until the finish reads.
        if (!flourish.committed(frames)) return null

        // 2. Measure the gesture geometry once.
        val features = GestureFeatureExtractor.extract(frames) ?: return null

        // 3. Score every card; keep the best that clears BOTH its finger gate and its tolerance gate.
        //
        // Selection is by a DETERMINISTIC ordering, not by iteration order (a tie must not be decided
        // by where a card sits in Deck.CARDS). The winner is the card that:
        //   (a) clears its per-card finger gate (features.fingerCount >= minFingers), AND
        //   (b) clears its per-card tolerance gate (score >= 1 - tolerance), AND
        //   (c) ranks first by (score desc, then proximity tie-break asc).
        // The proximity tie-break is the "find the NEAREST card" behaviour the Deck promises for the
        // swipey family (Deck.kt): when two cards score identically (e.g. a swipe equidistant from two
        // bearings at an octant boundary), the geometrically nearer card wins — never list order.
        var best: Thuruummm? = null
        var bestScore = 0f
        var bestTieBreak = Float.MAX_VALUE   // lower is nearer; compared only on a score tie
        for (card in cards) {
            if (features.fingerCount < card.gesture.minFingers) continue   // 4–5 finger gate, per card
            val score = scoreMovement(features, frames, card.gesture)
            // tolerance is slack: a card matches when score >= (1 - tolerance). Looser tolerance ⇒
            // lower bar.
            val gate = 1f - card.gesture.tolerance
            if (score < gate) continue
            val tieBreak = proximity(features, card.gesture)
            // Strictly-better score wins outright; on an (approximate) tie, the nearer card wins. The
            // epsilon keeps float jitter on two genuinely-equal scores from flipping the winner.
            val betterScore = score > bestScore + SCORE_TIE_EPS
            val tiedButNearer = score > bestScore - SCORE_TIE_EPS && tieBreak < bestTieBreak
            if (best == null || betterScore || tiedButNearer) {
                best = card
                bestScore = score
                bestTieBreak = tieBreak
            }
        }

        val winner = best ?: return null
        return Recognized(card = winner, targetCell = targetCell, features = features, score = bestScore)
    }

    /**
     * The score-tie tie-break key: lower = a better geometric fit, used ONLY to break a score tie so
     * selection never depends on Deck.CARDS order. For a directional [Movement.Translate] it is the
     * angular error to the card's bearing (the "nearest swipey" the Deck promises). For every other
     * family the score already fully orders the cards, so the key is constant (0) — ties there fall
     * through to a stable first-iterated winner, which is the documented behaviour for byte-identical
     * specs (AdversarialSpecDistinctionTest).
     */
    private fun proximity(f: GestureFeatures, spec: GestureSpec): Float =
        (spec.movement as? Movement.Translate)?.directionRad
            ?.let { angularError(f.driftDirectionRad, it) } ?: 0f

    /**
     * Score [features] against one card's [Movement] — the geometry match, in [0, 1].
     *
     * Dispatches on the sealed [Movement] type: each family scores only the dimensions it cares about
     * and is penalised for signal in the dimensions it should NOT see (a Gather with a big drift is a
     * poor gather). That cross-penalty is what keeps the three families from poaching each other's
     * streams — a swipe never reads as a gather, a twist never reads as a swipe.
     *
     * Adding a new [Movement] subtype means adding one branch here — but NOT touching any existing
     * card, deck entry, or other branch. The sealed `when` is exhaustive; the compiler enforces that a
     * new subtype is handled.
     */
    private fun scoreMovement(
        features: GestureFeatures,
        frames: List<PointerFrame>,
        spec: GestureSpec,
    ): Float = when (val m = spec.movement) {
        is Movement.Gather -> scoreGather(features, m)
        is Movement.RotateContract -> scoreRotateContract(features, m)
        is Movement.Translate -> scoreTranslate(features, m)
        is Movement.Custom -> customRecognizers[m.recognizerId]?.score(features, frames) ?: 0f
    }

    // ── family scorers ───────────────────────────────────────────────────────────────────────────
    //
    // Each returns 1.0 for a textbook instance of its family and decays toward 0 as the measurement
    // departs. The decays are deliberately simple, monotone, and clamped to [0,1] — first-guess
    // shapes, tuned on the thumb (the numbers are not design decisions). A "wrong-family" signal
    // (e.g. drift on a Gather) multiplies the score down so families stay separable.

    /**
     * Gather: fingers converge to a point — OR are placed and held. Wants spreadChange at/below the
     * card's [Movement.Gather.maxSpreadRatio] (a true contraction) but ALSO accepts a held cloud that
     * never expands; penalised for any centroid drift or net rotation (a gather sits still, does not spin).
     */
    private fun scoreGather(f: GestureFeatures, m: Movement.Gather): Float {
        // Contraction: a gather is a NON-EXPANDING still cloud. Full credit for any spreadChange at/below
        // 1.0 — that covers both a hard contraction (≤ maxSpreadRatio) AND a deliberate still HOLD
        // (spreadChange ≈ 1.0: fingers placed and held, no convergence needed). Only a genuine EXPANSION
        // (spreadChange > 1.0, the opposite of a gather) decays to 0 across a thin band. The DeliberateHold
        // flourish commits a motionless 5-finger hold through this branch — the drift/spin penalties below
        // keep a moving or spinning hand from poaching the gather, so "still" is the real gate, not
        // "must converge". (maxSpreadRatio remains the card's authored "how tight" intent; it is ≤ 1.0 for
        // every gather card, so a contraction that clears it is already at full credit here.)
        val contraction = invLerpFalling(f.spreadChange, 1f, 1f + EXPAND_BAND)
        val driftPenalty = driftStillness(f.centroidDriftPx)
        val spinPenalty = spinStillness(f.rotationRad)
        return clamp01(contraction * driftPenalty * spinPenalty)
    }

    /**
     * RotateContract: the cloud spins while shrinking. Wants |rotation| above [minRotationRad] AND
     * spreadChange below [maxSpreadRatio]; direction-locked if [clockwise] is set. Penalised for
     * large centroid drift (a twist stays roughly in place).
     */
    private fun scoreRotateContract(f: GestureFeatures, m: Movement.RotateContract): Float {
        val mag = abs(f.rotationRad)
        // Direction gate, deadbanded: in screen coords (y-down) positive rotation is clockwise. Apply
        // the lock ONLY once the spin clears STILL_ROT_RAD — below the deadband the sign is just float
        // jitter on an undecided rotation (a near-zero positive blip on a CCW-intended twist would
        // otherwise read as CW and slip past the lock). Matches how spinStillness treats sub-deadband
        // rotation as noise. Below the deadband the rotation factor is ~0 anyway, so a sub-deadband
        // gesture cannot score regardless of handedness.
        m.clockwise?.let { wantCw ->
            if (mag >= STILL_ROT_RAD && (f.rotationRad > 0f) != wantCw) return 0f
        }
        // Full credit once |rotation| reaches the threshold; partial (linear) below it. Already clamped
        // to [0,1], so a vigorous spin past the threshold is not over-rewarded — it saturates at 1.0.
        val rotation = invLerpRising(mag, 0f, m.minRotationRad)
        val contraction = invLerpFalling(f.spreadChange, m.maxSpreadRatio, 1.2f)
        val driftPenalty = driftStillness(f.centroidDriftPx)
        return clamp01(rotation * contraction * driftPenalty)
    }

    /**
     * Translate: the whole hand slides one way. Wants centroidDrift above [minDriftPx] with spread and
     * rotation roughly constant; if [directionRad] is set, the measured bearing must fall within
     * [maxDirectionErrorRad]. Penalised for net rotation or strong spread change (those are other families).
     *
     * Direction is a HARD GATE, not a quality factor (the load-bearing fix). Folding direction into the
     * multiplicative score and then gating the product at `1 - tolerance` would require the direction
     * term alone to clear the gate — for the shipping swipeys (maxDirectionErrorRad=0.4, tolerance=0.15)
     * that is `err <= 0.4 * 0.15 = 0.06 rad ≈ 3.4°`, an unplayable hand-precision demand that DESIGN.md
     * ("playable by feel… occasional glances") forbids and that opens a dead zone mid-octant between
     * every adjacent swipey. Instead: a swipe within `maxDirectionErrorRad` of the card's bearing is
     * ACCEPTED for that card (full octant of slack — the seven 45°-spaced swipeys each own ±~23°), and
     * the SCORE measures only translate QUALITY (drift, spread held, no spin). When two adjacent swipeys
     * both accept a boundary swipe, `proximity()` (nearest bearing) breaks the tie — the Deck's promised
     * "measures the swipe angle and finds the nearest card" behaviour.
     */
    private fun scoreTranslate(f: GestureFeatures, m: Movement.Translate): Float {
        // Hard direction gate: outside the card's bearing window, this is not that card's swipe at all.
        m.directionRad?.let { target ->
            if (angularError(f.driftDirectionRad, target) > m.maxDirectionErrorRad) return 0f
        }
        val drift = invLerpRising(f.centroidDriftPx, m.minDriftPx * 0.5f, m.minDriftPx)
        val spreadPenalty = spreadConstancy(f.spreadChange)
        val spinPenalty = spinStillness(f.rotationRad)
        return clamp01(drift * spreadPenalty * spinPenalty)
    }

    // ── scoring primitives (pure math, clamped, monotone) ──────────────────────────────────────────

    /** Rising ramp: 0 at/below [lo], 1 at/above [hi], linear between. [hi] must exceed [lo]. */
    private fun invLerpRising(x: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) (if (x >= hi) 1f else 0f) else clamp01((x - lo) / (hi - lo))

    /** Falling ramp: 1 at/below [lo], 0 at/above [hi], linear between. [hi] must exceed [lo]. */
    private fun invLerpFalling(x: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) (if (x <= lo) 1f else 0f) else clamp01((hi - x) / (hi - lo))

    /** Stillness factor for centroid drift: 1 when planted, decaying toward 0 as the hand wanders.
     *  STILL_DRIFT_PX is the "a gather/twist may wobble this much" budget — first guess, tuned on thumb. */
    private fun driftStillness(driftPx: Float): Float =
        invLerpFalling(driftPx, STILL_DRIFT_PX, WANDER_DRIFT_PX)

    /** Stillness factor for rotation: 1 when unspun, decaying as net rotation grows. */
    private fun spinStillness(rotationRad: Float): Float =
        invLerpFalling(abs(rotationRad), STILL_ROT_RAD, SPUN_ROT_RAD)

    /** Constancy factor for spread on a Translate: 1 when spread held, decaying as it changes either way. */
    private fun spreadConstancy(spreadChange: Float): Float =
        invLerpFalling(abs(spreadChange - 1f), SPREAD_HOLD_BAND, SPREAD_BREAK_BAND)

    /** Smallest absolute angle between two bearings, in [0, π]. */
    private fun angularError(a: Float, b: Float): Float {
        val twoPi = (2 * PI).toFloat()
        var d = abs(a - b) % twoPi
        if (d > PI) d = twoPi - d
        return d
    }

    private fun clamp01(x: Float): Float = min(1f, maxOf(0f, x))

    private companion object {
        // Wobble budgets that separate "planted" from "moving". Pixels at ~160dpi; first-guess,
        // tuned on the thumb (these are NOT design decisions, only recognition slack).
        const val STILL_DRIFT_PX = 24f     // a gather/twist may drift up to here with no penalty
        const val WANDER_DRIFT_PX = 90f    // past here, it is a translation, not a still gesture
        const val STILL_ROT_RAD = 0.20f    // ~11°: incidental wobble allowed before counting as a spin
        const val SPUN_ROT_RAD = 0.70f     // ~40°: clearly a rotation by here
        const val SPREAD_HOLD_BAND = 0.20f // ±20% spread change still reads as "held"
        const val SPREAD_BREAK_BAND = 0.55f// past ±55% it is a gather/expand, not a translate

        // Gather contraction decay: full credit at/below spreadChange 1.0 (held or tighter), falling to
        // 0 by 1.0 + this band. A thin band so a true EXPANSION (the opposite of a gather) scores ~0
        // immediately, while a motionless HOLD at exactly 1.0 keeps full gather credit (the DeliberateHold
        // commit path). Recognition slack, not a design number — tuned on the thumb.
        const val EXPAND_BAND = 0.02f

        // Two scores within this band are treated as a TIE and resolved by proximity() (nearest card),
        // not by Deck.CARDS order. Small enough that genuinely-different fits never collide; large
        // enough to absorb float jitter on two arithmetically-equal scores.
        const val SCORE_TIE_EPS = 1e-4f
    }
}
