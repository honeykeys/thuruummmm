package com.thrum.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The geometric summary of a gesture-in-progress — the three measurements the classifier matches
 * against every card's [com.thrum.deck.Movement] spec, plus the finger count for gating.
 *
 * The whole recognition model collapses to these three numbers (GESTURES.md, GestureSpec.kt):
 *
 *   net centroid translation  ⇒ Translate (swipey)
 *   rotation WITH contraction  ⇒ RotateContract (twisty)
 *   pure contraction (gather)  ⇒ Gather (tappy)
 *
 * Pure value, computed once per classify() call from the pointer window. No Android, no Compose.
 *
 * @param fingerCount      Distinct fingers that were pressed across the measured span. The gate:
 *                         DESIGN requires 4–5; a single finger / two pinkies must not register.
 * @param centroidDriftPx  |net displacement| of the finger-centroid from the first measured frame
 *                         to the last, in pixels. Large ⇒ the whole hand moved (Translate).
 * @param driftDirectionRad Bearing of that net displacement, radians, screen coords (y-down):
 *                         0 = +x (right), π/2 = down, π = left, 3π/2 = up. Matches GestureSpec's
 *                         `directionRad`. Undefined-but-harmless (returns 0) when drift ≈ 0.
 * @param spreadChange     final mean-radius / initial mean-radius. < 1 ⇒ fingers converged
 *                         (Gather / RotateContract); ≈ 1 ⇒ spread held (Translate); > 1 ⇒ expanded.
 *                         Defined as 1.0 when the initial spread is degenerate (all fingers coincident).
 * @param rotationRad      Signed net rotation of the finger cloud about its (moving) centroid,
 *                         radians. + = clockwise in screen coords (y-down), − = counter-clockwise.
 *                         Mean over all fingers tracked through both endpoints. ~0 for Gather/Translate.
 */
data class GestureFeatures(
    val fingerCount: Int,
    val centroidDriftPx: Float,
    val driftDirectionRad: Float,
    val spreadChange: Float,
    val rotationRad: Float,
)

/**
 * Extracts [GestureFeatures] from a pointer window.
 *
 * Design notes (the load-bearing correctness choices):
 *
 *  - **Endpoints, not noise.** Drift / spread / rotation are measured between a STABLE-START frame
 *    and a STABLE-END frame, not the literal first/last pushed frames. The start frame is the first
 *    frame reaching full finger count (the hand has landed); the end frame is the last frame that
 *    still holds that count (before fingers begin lifting for the flourish). This makes the features
 *    immune to the ragged touch-down / lift-off transients that would otherwise smear every gesture.
 *
 *  - **Track by id.** Rotation and spread are per-finger quantities; a finger is matched start↔end by
 *    its [Finger.id]. Only fingers present in BOTH endpoint frames contribute, so a finger that joined
 *    late or left early never injects a phantom angle. (Compose guarantees a stable `PointerId` for the
 *    life of a touch — verified 2026-06-13, PointerInputChange docs.)
 *
 *  - **Rotation about the moving centroid.** Each tracked finger's bearing is taken relative to the
 *    centroid OF ITS OWN frame, so a pure translation (centroid and all fingers move together) yields
 *    ~0 rotation. Per-finger angular deltas are wrapped to (−π, π] and averaged — the signed mean is
 *    the cloud's net spin, robust to one noisy finger.
 *
 *  - **Stateless & pure.** No clock reads, no mutation; deterministic for a given window. Time is used
 *    only by [Flourish], never here.
 */
object GestureFeatureExtractor {

    /**
     * Compute features over [frames] (oldest-first). Returns null only if there is not enough signal
     * to measure at all — fewer than two usable endpoint frames, or no finger tracked through both.
     * A null here means "no classifiable gesture yet," NOT a rejection of finger count (count is
     * reported in the returned features so the classifier owns the 4–5 gate).
     */
    fun extract(frames: List<PointerFrame>): GestureFeatures? {
        if (frames.size < 2) return null

        // Peak simultaneous finger count anywhere in the window — the gesture's "how many hands".
        val peakCount = frames.maxOf { it.pressedCount }
        if (peakCount < 1) return null

        // START = first frame at peak count (hand fully landed); END = last frame at peak count
        // (before lift-off begins). Measuring between these strips the touch-down/lift-off transients.
        val startIdx = frames.indexOfFirst { it.pressedCount == peakCount }
        var endIdx = frames.indexOfLast { it.pressedCount == peakCount }

        // FINGERS-LIFT transition (not a transient): the hand is at full count from the very FIRST frame
        // and then a finger leaves — peak occurs once, at index 0, but there is a later frame still
        // holding fingers. That later frame is a real endpoint, so measure ACROSS the transition and
        // count only the fingers present through BOTH endpoints (the intersection), never the union. This
        // is distinct from the lone-peak transient below (a late/interior finger brush), which has no
        // earlier-or-equal full-count anchor to measure from and stays static by design.
        if (startIdx == endIdx && startIdx == 0) {
            val lastPressedIdx = frames.indexOfLast { it.pressedCount > 0 }
            if (lastPressedIdx > startIdx) endIdx = lastPressedIdx
        }

        if (startIdx == endIdx) {
            // Hand was at peak for a single frame only — no motion to measure, but the count is real
            // (e.g. a clean gather-and-lift). Report a static feature set so the classifier can still
            // match a Gather/static card on count alone if its thresholds allow.
            //
            // KNOWN LIMITATION (pinned, intentional — AdversarialFeatureCornerTest authors two tests
            // around this exact branch): if the PEAK finger count is reached on only one frame during a
            // FAST moving gesture (e.g. a late 5th finger brushes the glass on the final tight frame of
            // a 4-finger gather), the motion measured at the dominant lower count is discarded and the
            // gesture reads static. Upstream this is low-probability: the default flourish requires a
            // STILL TAIL at full count before the lift, which forces multiple peak frames, so a real
            // committed gesture rarely lands here. Selecting endpoints by the DOMINANT sustained count
            // (rather than the raw peak) would fix it but changes behaviour the corner tests pin as a
            // documented tradeoff — that is a deck-author/Karl retune, not a silent classifier change.
            val only = frames[startIdx]
            return GestureFeatures(
                fingerCount = distinctPressedIds(only),
                centroidDriftPx = 0f,
                driftDirectionRad = 0f,
                spreadChange = 1f,
                rotationRad = 0f,
            )
        }

        val start = frames[startIdx]
        val end = frames[endIdx]

        val startFingers = start.pressed.associateBy { it.id }
        val endFingers = end.pressed.associateBy { it.id }
        val tracked = startFingers.keys intersect endFingers.keys
        if (tracked.isEmpty()) return null

        val startCentroid = centroid(startFingers.values)
        val endCentroid = centroid(endFingers.values)

        // ── centroid drift (Translate signal) ──
        val dx = endCentroid.first - startCentroid.first
        val dy = endCentroid.second - startCentroid.second
        val drift = hypot(dx, dy)
        val driftDir = if (drift > 1e-3f) normalizeAngle(atan2(dy, dx)) else 0f

        // ── spread change (Gather / contraction signal) ──
        val startSpread = meanRadius(startFingers.values, startCentroid)
        val endSpread = meanRadius(endFingers.values, endCentroid)
        val spreadChange = if (startSpread > 1e-3f) endSpread / startSpread else 1f

        // ── rotation about the moving centroid (RotateContract signal) ──
        // For each finger tracked through both endpoints, the bearing relative to ITS frame's centroid,
        // then the wrapped delta. The signed mean is the cloud's net spin. Using each frame's own
        // centroid is what makes a pure translation read as ~0 rotation.
        var rotationSum = 0f
        var rotationN = 0
        for (id in tracked) {
            val s = startFingers.getValue(id)
            val e = endFingers.getValue(id)
            val aStart = atan2(s.y - startCentroid.second, s.x - startCentroid.first)
            val aEnd = atan2(e.y - endCentroid.second, e.x - endCentroid.first)
            // Skip a finger sitting essentially on the centroid at either endpoint — its bearing is
            // numerically meaningless and would add pure noise to the mean.
            val rS = hypot(s.x - startCentroid.first, s.y - startCentroid.second)
            val rE = hypot(e.x - endCentroid.first, e.y - endCentroid.second)
            if (rS < 1e-3f || rE < 1e-3f) continue
            rotationSum += wrapToPi(aEnd - aStart)
            rotationN++
        }
        val rotation = if (rotationN > 0) rotationSum / rotationN else 0f

        return GestureFeatures(
            fingerCount = tracked.size,
            centroidDriftPx = drift,
            driftDirectionRad = driftDir,
            spreadChange = spreadChange,
            rotationRad = rotation,
        )
    }

    private fun distinctPressedIds(frame: PointerFrame): Int =
        frame.pressed.map { it.id }.toSet().size

    /** Arithmetic mean of finger positions. Caller guarantees the collection is non-empty. */
    private fun centroid(fingers: Collection<Finger>): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (f in fingers) { sx += f.x; sy += f.y }
        val n = fingers.size
        return (sx / n) to (sy / n)
    }

    /** Mean distance of fingers from a centre — the cloud's "spread" / radius. */
    private fun meanRadius(fingers: Collection<Finger>, centre: Pair<Float, Float>): Float {
        if (fingers.isEmpty()) return 0f
        var sum = 0f
        for (f in fingers) sum += hypot(f.x - centre.first, f.y - centre.second)
        return sum / fingers.size
    }

    /** Wrap an angular delta into (−π, π] so a small spin never reads as a near-full turn. */
    private fun wrapToPi(a: Float): Float {
        var x = a
        val twoPi = (2 * PI).toFloat()
        while (x > PI) x -= twoPi
        while (x <= -PI) x += twoPi
        return x
    }

    /** Normalize a bearing into [0, 2π). */
    private fun normalizeAngle(a: Float): Float {
        val twoPi = (2 * PI).toFloat()
        var x = a % twoPi
        if (x < 0f) x += twoPi
        return x
    }
}
