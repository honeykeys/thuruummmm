package com.thrum.ui

/**
 * CONTRACT NOTE — Compose UI surface coverage for com.thrum.ui.
 *
 * This file documents what IS and IS NOT covered by the JVM unit tests in this package,
 * and what MUST be verified by the device screencap loop (RESEARCH-NATIVE.md §2).
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════
 *
 * COVERED BY JVM UNIT TESTS (no device, no Compose runtime):
 *
 *   ScreenStateMachineTest.kt
 *     - Sealed Screen hierarchy exactly {Start, Game, Settings}.
 *     - Each Screen is pairwise distinct (correct data-object identity).
 *     - The `when` dispatch binds each screen to the correct callback (no arm confusion).
 *     - Transition graph: Start→Game, Game→Settings, Settings→Game; Settings→Start impossible.
 *     - Round-trip Game→Settings→Game resolves back to Game.
 *     - Unknown callback names leave the current screen unchanged.
 *
 *   StartTwistDetectorTest.kt
 *     - Sub-threshold angular travel does not commit.
 *     - Threshold boundary: abs(totalAngle) >= MIN_ROTATE_RAD fires; below does not.
 *     - 1- and 2-finger inputs never enter TRACKING.
 *     - onTwist fires at most once; the committed flag suppresses repeated firings.
 *     - 2→3 finger ramp enters TRACKING only when the 3rd finger lands.
 *     - Forward/backward oscillation that net-cancels does not commit.
 *     - A sub-threshold gesture fully resets state for the next attempt.
 *     - Counter-clockwise rotation (negative totalAngle) also commits.
 *     - A lift before any touch is a no-op.
 *
 *   SelectyDirResolutionTest.kt
 *     - All four quadrant cases (RIGHT, LEFT, DOWN, UP).
 *     - Octant boundaries (|dx|==|dy|): horizontal axis wins (>= rule).
 *     - Screen centre (dx=0, dy=0): resolves to RIGHT (dx >= 0 tie-break).
 *     - On-axis taps (pure horizontal or vertical).
 *     - Sub-pixel fractional positions.
 *     - Non-standard screen dimensions (portrait, large).
 *     - Screen-edge extremes (x=0, y=H, corners).
 *
 *   ComputeShakeTest.kt
 *     - Offset.Zero at exactly SHAKE_DURATION_MS.
 *     - Offset.Zero past the duration boundary.
 *     - Non-zero offset at early elapsed times for non-trivial magnitude.
 *     - Amplitude cap at SHAKE_AMPLITUDE_MAX_PX enforced for extreme magnitudes.
 *     - Amplitude is proportional to magnitude below the cap.
 *     - Amplitude decays monotonically over time (halfway point < initial).
 *     - Zero magnitude → Offset.Zero throughout.
 *     - x and y components differ (different frequency factors).
 *     - Ring count and fell count do not affect amplitude.
 *
 *   RenderSnapshotMappingTest.kt
 *     - RenderSnapshot.EMPTY shape and value contract.
 *     - One placement → exactly one BrickView with correct (cell, glyph, material).
 *     - Pan deltas accumulate exactly in panCellX/panCellY.
 *     - collapse is null on all frames except the trigger frame.
 *     - stress = 1.0 on empty field; < 1.0 under load.
 *     - BrickView.id stable across idle ticks.
 *     - hapticsAvailable = false on every snapshot from a no-motor device.
 *     - targetCell in snapshot immediately reflects selectAdjacent result.
 *     - CollapseView.magnitude, rings, fellIds are faithfully forwarded.
 *
 *   GameScreenRoutingTest.kt
 *     - selecty resolves a clean tap to the correct SlotDir and moves the slot.
 *     - selecty rejects drifty taps (drift >= SELECTY_MAX_DRIFT_PX).
 *     - selecty rejects long holds (hold >= SELECTY_MAX_HOLD_MS).
 *     - navvy below the noise floor is silently ignored.
 *     - navvy exactly at the noise floor is also ignored (strict > check).
 *     - navvy accumulates px/cellPx in cell space.
 *     - navvy y-flips before calling panBy.
 *     - A multi-finger slide without a flourish never fires the classifier.
 *     - selecty and navvy never fire the haptic engine.
 *     - A committed gesture fires at least 2 haptic beats.
 *
 *   FieldCanvasCoordinateTest.kt
 *     - Cell(0,0) maps to horizontal centre and floor of canvas.
 *     - Positive/negative cell.x maps correctly left/right of centre.
 *     - Higher cell.y maps to a smaller (higher) screen y.
 *     - panCellX shifts the field left (camera right → world left).
 *     - panCellY shifts bricks down on screen (camera up → world down).
 *     - Proportional mapping holds for large cell coordinates (no clamping).
 *     - Doubling cellPx doubles all screen distances.
 *     - The mapping is injective (no two distinct cells collide at the same pixel).
 *     - Ground-row cells at different x share the same screen y (flat floor).
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════
 *
 * REQUIRES DEVICE SCREENCAP VERIFICATION (RESEARCH-NATIVE.md §2: adb screencap → Read):
 *
 *   Compose rendering (ALL of the following require the Android runtime):
 *   - ThuruummmApp composes and displays the correct screen for each Screen state.
 *   - StartScreen renders three finger-blob hint circles at rest.
 *   - StartScreen renders live blobs tracking actual finger positions when >= 3 fingers down.
 *   - StartScreen renders the twist arc when >= 3 fingers are pressed.
 *   - GameScreen renders bricks at the pixel coordinates the coordinate formula produces.
 *   - GameScreen renders the correct glyph on each brick (glyph shapes, not just presence).
 *   - GameScreen renders the target-cell highlight at the working slot.
 *   - GameScreen renders the stress tremor overlay when stress < STRESS_TREMBLE_THRESHOLD (0.25).
 *   - GameScreen renders the visual haptic fallback (white glow) on a no-motor collapse frame.
 *   - SettingsScreen renders "thuuruummm" title and "settings" subtitle in the correct colours.
 *   - Orientation lock: StartScreen forces portrait, GameScreen/SettingsScreen force landscape.
 *   - The Start→Game transition (the orientation flip) happens without Activity recreation.
 *   - LockOrientation restores the prior orientation on disposal (Settings→Game → portrait restored?).
 *   - The screen-shake animation: the field translates according to computeShake output.
 *   - FieldCanvas draw glyph shapes: ARROW_CENTER draws four converging arrows; SPIRAL draws arc+dot.
 *   - Directional glyphs (ARROW_RIGHT, ARROW_DOWN, etc.) rotate the arrow shape correctly.
 *   - materialColor palette: low-strength bricks are cool slate, high-strength are warm amber.
 *   - The camera pan (navvy) scrolls the field without moving bricks in engine space.
 *
 *   Haptic verification (requires real device with vibration motor — RESEARCH-NATIVE.md §2):
 *   - The thur haptic fires the instant the flourish commits (before physics).
 *   - The rummmm haptic fires immediately after physics.place() returns.
 *   - The THRUUMMMM haptic fires on a collapse and locks the motor.
 *   - Commit beats during the THRUUMMMM lock window are coalesced away (not felt).
 *   - LockOrientation API: Activity.requestedOrientation is set/restored correctly —
 *     UNVERIFIED against a single official doc (ARCHITECTURE.md §5 verification ledger).
 *
 *   Gesture input on-device:
 *   - The "twist to start" gesture (rotate + lift) fires onTwist and transitions to Game.
 *   - The setty gesture transitions to Settings.
 *   - The navvy gesture (whole-hand slide) pans the field without committing a brick.
 *   - The selecty gesture (single tap) advances the target highlight.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════
 *
 * WHY NO Compose-UI-test-on-JVM (Robolectric):
 *
 * ARCHITECTURE.md §6 is explicit: "Running Compose UI tests on the JVM (no device)" via Robolectric
 * is UNVERIFIED — the official Compose-Robolectric page returned 404 during research, and the
 * community sources are unverified corroborations. Adding Robolectric to the build risks a
 * non-trivial toolchain integration failure that the hackathon schedule cannot absorb. The
 * documented MVP fallback ("keep ui/ thin enough that its only real test is the screen-switch state
 * machine — pure Kotlin, trivially JVM-testable") is what we implement here.
 *
 * The Compose-render correctness evidence is the `adb screencap → Read` loop — the agent's verified
 * eyes. One pass of `./gradlew installDebug && adb exec-out screencap -p > /tmp/thrum.png && Read`
 * covers the entire render surface in one visual check.
 */
@Suppress("unused")
object OrientationAndComposeContractNote
