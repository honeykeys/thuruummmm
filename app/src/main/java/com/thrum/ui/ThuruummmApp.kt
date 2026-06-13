package com.thrum.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The three-screen state machine — the navigation root for thuuruummm.
 *
 * This is a plain `when` over a sealed interface, NOT Navigation Compose. Three screens, no deep
 * links, no back-stack semantics beyond "Settings returns to Game" — Navigation Compose's
 * dependency, kotlinx-serialization plugin, and type-safe-route ceremony buy nothing here.
 * (ARCHITECTURE.md §5: "scoped judgment… for a 3-screen toy with no navigation arguments the
 * state-machine is simpler and the official pattern is over-built.")
 *
 * Screen transitions:
 *   - Start  → Game: the "twist to start" gesture fires [onTwist].
 *   - Game   → Settings: [onSetty] (the setty gesture — the "secret handshake").
 *   - Settings → Game: [onBack] (any back action).
 *
 * Each screen enforces its own orientation via [LockOrientation]. The orientation flip start→game
 * is load-bearing for the UX: the player physically rotates the phone as the game begins
 * (DESIGN.md: "landscape screen, both hands, multi-finger gestures"). The Activity does NOT
 * recreate on this flip because the manifest declares
 * `android:configChanges="orientation|screenSize|keyboardHidden"` (RESEARCH-NATIVE.md §6) — which
 * is also why the plain [GameState] state holder (not a ViewModel) loses nothing across the
 * transition.
 */
@Composable
fun ThuruummmApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Start) }

    // ── Single orientation lock for all three screens (P3c fix) ────────────────────────────────
    //
    // Previously each screen called LockOrientation independently. On a screen switch, the leaving
    // screen's DisposableEffect restored the PREVIOUS orientation at nearly the same instant the
    // entering screen's DisposableEffect applied the NEW one — a visible restore→set thrash.
    //
    // Fix: one LockOrientation call at the root, keyed on `screen`. The DisposableEffect key is
    // the orientation INT (not the screen object), so it only re-runs when the integer value
    // actually changes (Start→Game: portrait→landscape fires one clean set; Game→Settings:
    // landscape→landscape is a no-op because the key does not change). No per-screen restore
    // races because there is only one DisposableEffect.
    //
    // Each screen's own LockOrientation call is removed — they now simply compose without locking.
    val orientation = when (screen) {
        Screen.Start    -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Screen.Game     -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Screen.Settings -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    LockOrientation(orientation)

    when (screen) {
        Screen.Start    -> StartScreen(onTwist = { screen = Screen.Game })
        Screen.Game     -> GameScreen(onSetty = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Game })
    }
}

/**
 * The three screens. Sealed so the `when` is exhaustive and the compiler enforces completeness.
 * No navigation arguments flow between screens — each screen is self-contained.
 */
sealed interface Screen {
    data object Start    : Screen
    data object Game     : Screen
    data object Settings : Screen
}

/**
 * Lock the Activity orientation to [orientation] for the lifetime of this composable.
 *
 * On entry: applies [orientation] via [android.app.Activity.requestedOrientation].
 * On disposal (the composable leaves the tree): restores the previous orientation so the app is
 * not permanently locked if the composable is removed for any reason other than a deliberate
 * screen switch.
 *
 * ── API provenance ────────────────────────────────────────────────────────────────────────────
 *
 * [LocalActivity] — `androidx.activity.compose.LocalActivity` (package: androidx.activity.compose).
 * A `ProvidableCompositionLocal<Activity?>` introduced in androidx.activity 1.10.0-alpha03
 * (release notes: "New LocalActivity composition local... removes the need for developers to get
 * an Activity from LocalContext"). Available with BOM 2026.06.00 which brings activity-compose 1.11+.
 * Verified 2026-06-13: https://developer.android.com/jetpack/androidx/releases/activity and
 * https://developer.android.com/reference/kotlin/androidx/activity/compose/package-summary.
 *
 * `Activity.requestedOrientation` (the setter) — the current mechanism for runtime orientation
 * override, per developer.android.com/develop/ui/compose/quick-guides/content/restrict-app-orientation-on-phones
 * (verified 2026-06-13, page published 2026-01-16). The official page shows exactly
 * `activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT` in a Compose context.
 *
 * [DisposableEffect] — the correct Compose side-effect for operations that require cleanup. Its
 * [orientation] key means the effect re-runs if the requested orientation changes (e.g., a screen
 * switch changes from portrait to landscape). Verified current: developer.android.com/develop/ui/compose/side-effects
 * (page updated 2026-06-04).
 *
 * `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT` = 1 (android.content.pm.ActivityInfo, API 1+).
 * `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` = 0 (android.content.pm.ActivityInfo, API 1+).
 * `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED` = -1 — restored on disposal.
 *
 * @param orientation One of `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`,
 *                    `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`, etc.
 */
@Composable
fun LockOrientation(orientation: Int) {
    // LocalActivity lives in androidx.activity.compose — not LocalContext. The Activity is nullable
    // because Compose does not guarantee a non-null Activity in all environments (e.g., tests,
    // Wear). Guard the null explicitly so the composable is test-safe.
    val activity = LocalActivity.current
    DisposableEffect(orientation) {
        val previousOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = orientation
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }
}
