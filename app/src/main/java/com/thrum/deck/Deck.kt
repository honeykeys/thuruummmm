package com.thrum.deck

import com.thuruummm.physics.Material
import com.thrum.haptics.Haptic
import com.thrum.haptics.haptic

/**
 * THE REGISTRY. The entire gesture vocabulary lives here.
 *
 * Adding a gesture = adding one [Thuruummm] object to [CARDS]. Nothing else in the codebase
 * changes. Karl drops a new entry here; the GestureClassifier, PhysicsEngine, HapticEngine,
 * and FieldCanvas all pick it up automatically via the card's facets.
 *
 * INVARIANT enforced by property tests (see deck/ test suite):
 *   - every [Thuruummm.id] is unique
 *   - every [Thuruummm.glyph] is unique
 *   - every [Material] satisfies Material.kt's own init checks
 *   - [byId] covers every card (no orphan)
 *
 * ── Deck structure ──────────────────────────────────────────────────────────────────────────
 *
 * Per GESTURES.md, the minting deck is 9 cards across 3 recognizer families:
 *
 *   gather family     (1 card):  tappy
 *   rotate+contract   (1 card):  twisty
 *   translate family  (7 cards): swipey ×7 (one per direction, minus up-left; see GESTURES.md)
 *
 * tappy and twisty are FULLY SEEDED — gesture, material, and haptic are first-guess values
 * ready for tuning on the thumb. The 7 swipeys are STUBS — gesture specs are locked;
 * material and haptic carry placeholder values that Karl replaces once the physics engine
 * tells us what points in the material space are interesting.
 *
 * STUB CARDS are marked with "// STUB" comments. They compile and classify correctly;
 * their material numbers are placeholders, not design decisions.
 *
 * ── How to author a card ───────────────────────────────────────────────────────────────────
 *
 *   1. Add a Thuruummm(...) entry here.
 *   2. Add the glyph to [Glyph.kt] if it is new.
 *   3. Run the deck property tests: ./gradlew testDebugUnitTest --tests "*.DeckTest"
 *   4. Nothing else changes.
 *
 * ── Material number legend ─────────────────────────────────────────────────────────────────
 *
 *   weight          — load this brick adds; higher = heavier; tuned so tall towers buckle
 *   strength        — load it bears; higher = more durable; harder gesture → higher strength
 *   cantilever      — sideways span (cells); 0 = no overhang
 *   shatterThreshold— fall distance (cells) before it shatters; lower = more fragile
 *   brittleness     — shove on shatter 0..1; 0 = crumbles silently; 1 = explodes
 *
 * Numbers are FIRST GUESSES from PHYSICS.md §"Starter materials". Tuned once the engine runs.
 */
object Deck {

    val CARDS: List<Thuruummm> = listOf(

        // ── gather family ──────────────────────────────────────────────────────────────────

        /**
         * tappy — 5 fingers gather to a point, then flourish.
         * The cheapest, most-used brick. Light, low strength, no reach. Pure filler.
         * Haptic: one crisp tick — light, cheap, the drumming brick.
         * Material ref: "pebble" row, PHYSICS.md §"Starter materials".
         */
        Thuruummm(
            id       = "tappy",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Gather(maxSpreadRatio = 0.6f),
                tolerance  = 0.20f,
            ),
            material = Material(
                weight           = 1.0,
                strength         = 3.0,
                cantilever       = 0,
                shatterThreshold = 2,
                brittleness      = 0.2,
            ),
            rummmm = haptic("tappy-rummmm") {
                // Light, crisp — confirms the brick landed, carries no ambition.
                // Primitive.TICK (id=7) is API 30; minSdk=31 so always available.
                // Verified: developer.android.com/reference/android/os/VibrationEffect.Composition (2026-06-13)
                tick(scale = 0.55f)
            },
            glyph    = Glyph.ARROW_CENTER,
        ),

        // ── rotate+contract family ─────────────────────────────────────────────────────────

        /**
         * twisty — 5 fingers rotate about their centre while spiralling in, then flourish.
         * The cantilever brick: heavier than pebble, spans sideways, the builder's friend.
         * Haptic: a spin — you feel the rotation that minted it.
         * Material ref: "wood" row, PHYSICS.md §"Starter materials".
         */
        Thuruummm(
            id       = "twisty",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.RotateContract(
                    minRotationRad = 0.5f,   // ~28°
                    maxSpreadRatio = 0.8f,
                    clockwise      = true,   // CW only; CCW = a different card if Karl wants one
                ),
                tolerance  = 0.15f,
            ),
            material = Material(
                weight           = 1.5,
                strength         = 5.0,
                cantilever       = 2,
                shatterThreshold = 3,
                brittleness      = 0.1,
            ),
            rummmm = haptic("twisty-rummmm") {
                // A spin you can feel — the cantilever brick's character.
                // Primitive.SPIN (id=3) is API 31; minSdk=31 so always available.
                // Verified: developer.android.com/reference/android/os/VibrationEffect.Composition (2026-06-13)
                spin(scale = 0.70f)
                tick(scale = 0.35f, delay = 40) // a trailing crisp note, settling
            },
            glyph    = Glyph.SPIRAL,
        ),

        // ── translate family (swipey ×7) ──────────────────────────────────────────────────
        //
        // All 7 share the same recognizer family (Movement.Translate); they differ only by
        // [directionRad]. The classifier measures the swipe angle and finds the nearest card.
        //
        // STUB: gesture specs are locked per GESTURES.md. Material and haptic are PLACEHOLDERS.
        // Karl replaces these once the physics engine identifies interesting points in material space.
        // The 7 directions per GESTURES.md §"The 7 swipey directions": up, down, left, right,
        // up-right, down-right, down-left (up-left omitted — confirm with Karl).
        //
        // Placeholder material: slightly stronger than tappy, no special stats.
        // Placeholder haptic: a quick-rise into a click — generic "landed" feel.

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-right",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.RIGHT,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB — placeholder, replace with physics-informed values
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-right-rummmm"),   // STUB — placeholder haptic
            glyph    = Glyph.ARROW_RIGHT,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-down-right",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.DOWN_RIGHT,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-down-right-rummmm"),  // STUB
            glyph    = Glyph.ARROW_DOWN_RIGHT,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-down",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.DOWN,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-down-rummmm"),  // STUB
            glyph    = Glyph.ARROW_DOWN,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-down-left",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.DOWN_LEFT,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-down-left-rummmm"),  // STUB
            glyph    = Glyph.ARROW_DOWN_LEFT,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-left",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.LEFT,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-left-rummmm"),  // STUB
            glyph    = Glyph.ARROW_LEFT,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-up",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.UP,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-up-rummmm"),  // STUB
            glyph    = Glyph.ARROW_UP,
        ),

        // STUB ─────────────────────────────────────────────────────────────────────────────
        Thuruummm(
            id       = "swipey-up-right",
            gesture  = GestureSpec(
                minFingers = 4,
                movement   = Movement.Translate(
                    minDriftPx        = 80f,
                    directionRad      = Dir.UP_RIGHT,
                    maxDirectionErrorRad = 0.4f,
                ),
                tolerance  = 0.15f,
            ),
            material = Material(  // STUB
                weight = 1.2, strength = 3.5, cantilever = 0, shatterThreshold = 2, brittleness = 0.3,
            ),
            rummmm = swipeyRummmm("swipey-up-right-rummmm"),  // STUB
            glyph    = Glyph.ARROW_UP_RIGHT,
        ),

    )

    // ── O(1) lookups consumed by the rest of the system ───────────────────────────────────

    /** Keyed by [Thuruummm.id]. The GestureClassifier resolves a Recognized to an id; the
     *  game loop pulls the full card for physics, haptics, and render. */
    val byId: Map<String, Thuruummm> = CARDS.associateBy { it.id }

    // ── Private authoring helpers ──────────────────────────────────────────────────────────

    /** Placeholder rummmm for stub swipey cards: a quick-rise into a click — generic "landed".
     *  Replace with a distinctive composed haptic when Karl assigns the final material. */
    private fun swipeyRummmm(label: String): Haptic = haptic(label) {
        // Primitive.QUICK_RISE (id=4) is API 30; Primitive.CLICK (id=1) is API 30.
        // minSdk=31 so both are always available.
        // Verified: developer.android.com/reference/android/os/VibrationEffect.Composition (2026-06-13)
        quickRise(scale = 0.55f)
        click(scale = 0.70f, delay = 30)
    }
}
