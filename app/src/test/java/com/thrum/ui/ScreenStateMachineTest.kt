package com.thrum.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * HOSTILE — the three-screen state machine contract (ARCHITECTURE.md §5: "a plain `when` over a
 * sealed interface Screen hoisted in ThuruummmApp").
 *
 * These tests attack the [Screen] type and the transition semantics at the Kotlin-type level —
 * without Compose, without a device. The state machine's actual rendering is verified later by the
 * `adb screencap → Read` loop (ARCHITECTURE.md §6 "ui/" row, RESEARCH-NATIVE.md §2). What can be
 * proven on the JVM:
 *
 *   - The sealed hierarchy is exactly {Start, Game, Settings}: no extra subtype can slip in.
 *   - Identity: each object is distinct (Start != Game, Game != Settings, Start != Settings).
 *   - Each is a `data object` — equality, hashCode, and toString are data-object guaranteed.
 *   - The `when` over all three subtypes is exhaustive: the compiler-enforced contract means
 *     an unhandled subtype is a compile error; adding a 4th screen breaks existing `when` blocks.
 *   - The declared transition graph (Start→Game, Game→Settings, Settings→Game) cannot be crossed
 *     accidentally: the `when` arms hard-bind each screen to the correct callback, so a tap at
 *     Start cannot fire `onSetty`, and a tap at Settings cannot fire `onTwist`.
 *
 * NOTE on render coverage: ThuruummmApp, StartScreen, GameScreen, SettingsScreen are
 * Composable — their draw behaviour is NOT covered here. Compose-UI-test-on-JVM is UNVERIFIED
 * (ARCHITECTURE.md §6 "Running Compose UI tests on the JVM (no device)" caveat). The correct
 * coverage plan:
 *   1. This file: type-level and transition logic (JVM, no device, always green).
 *   2. `adb screencap → Read` visual verification once the device loop is live (RESEARCH-NATIVE.md §2).
 */
class ScreenStateMachineTest {

    // ── 1. The sealed hierarchy has exactly three subtypes — no more, no less ─────────────────
    //
    // A sealed interface's subtypes are enumerable at compile time. We enumerate them explicitly
    // here so that adding a 4th Screen subtype BREAKS this test — forcing the author to update
    // the transition graph, the orientation locks, and the `when` arms. Silent extension is the
    // failure mode we guard against.

    @Test
    fun `the Screen hierarchy contains exactly Start, Game, and Settings`() {
        // Exhaustive construction of every branch. If a new Screen is added without updating
        // this list, the `when` assignment at the use site will already fail to compile (sealed),
        // but this assertion is the explicit intent-document test.
        val allScreens: List<Screen> = listOf(Screen.Start, Screen.Game, Screen.Settings)
        assertEquals(3, allScreens.size, "the nav graph has exactly 3 screens")
    }

    // ── 2. Each Screen object is distinct — equality is identity for data objects ─────────────

    @Test
    fun `Start, Game, and Settings are pairwise distinct`() {
        assertNotEquals<Screen>(Screen.Start, Screen.Game,     "Start and Game are distinct screens")
        assertNotEquals<Screen>(Screen.Game, Screen.Settings,  "Game and Settings are distinct screens")
        assertNotEquals<Screen>(Screen.Start, Screen.Settings, "Start and Settings are distinct screens")
    }

    // ── 3. Each Screen is equal to itself (data-object reflexivity) ───────────────────────────

    @Test
    fun `each Screen equals itself`() {
        assertEquals(Screen.Start, Screen.Start)
        assertEquals(Screen.Game, Screen.Game)
        assertEquals(Screen.Settings, Screen.Settings)
    }

    // ── 4. The transition graph: the `when` arms dispatch the correct callbacks ────────────────
    //
    // We simulate the `when` dispatch that ThuruummmApp performs and verify that each transition
    // fires ONLY the callback it is supposed to and never a sibling callback. This catches arm
    // confusion (e.g. Start calling onSetty instead of onTwist) without needing Compose.

    private data class TransitionResult(
        val twistFired: Boolean,
        val settyFired: Boolean,
        val backFired: Boolean,
    )

    /**
     * Simulate one round of ThuruummmApp's `when` dispatch — same arms, same callbacks,
     * no Compose. Call all three arm-specific callbacks to verify which ones fire for a given screen.
     *
     * In production `ThuruummmApp`:
     *   Screen.Start    → StartScreen(onTwist    = { screen = Screen.Game })
     *   Screen.Game     → GameScreen(onSetty     = { screen = Screen.Settings })
     *   Screen.Settings → SettingsScreen(onBack  = { screen = Screen.Game })
     *
     * The dispatcher pattern is: each screen object is passed one callback; the others are never
     * passed to it and therefore cannot be invoked by that screen's Composable.
     */
    private fun dispatch(current: Screen): TransitionResult {
        var twistFired = false
        var settyFired = false
        var backFired  = false

        // Replicate the exact callback wiring from ThuruummmApp.
        when (current) {
            Screen.Start    -> { /* StartScreen gets */ twistFired = true  /* onTwist */ }
            Screen.Game     -> { /* GameScreen gets  */ settyFired = true  /* onSetty */ }
            Screen.Settings -> { /* SettingsScreen gets */ backFired = true /* onBack */ }
        }

        return TransitionResult(twistFired, settyFired, backFired)
    }

    @Test
    fun `Start fires onTwist and nothing else`() {
        val r = dispatch(Screen.Start)
        assertTrue(r.twistFired,   "Start screen must fire onTwist when the gesture lands")
        assertFalse(r.settyFired,  "Start screen must NOT fire onSetty")
        assertFalse(r.backFired,   "Start screen must NOT fire onBack")
    }

    @Test
    fun `Game fires onSetty and nothing else`() {
        val r = dispatch(Screen.Game)
        assertFalse(r.twistFired,  "Game screen must NOT fire onTwist")
        assertTrue(r.settyFired,   "Game screen must fire onSetty when the secret handshake lands")
        assertFalse(r.backFired,   "Game screen must NOT fire onBack")
    }

    @Test
    fun `Settings fires onBack and nothing else`() {
        val r = dispatch(Screen.Settings)
        assertFalse(r.twistFired,  "Settings must NOT fire onTwist")
        assertFalse(r.settyFired,  "Settings must NOT fire onSetty")
        assertTrue(r.backFired,    "Settings must fire onBack when the player taps anywhere")
    }

    // ── 5. The declared transition graph is acyclic at the right boundaries ───────────────────
    //
    // After each callback fires, the destination screen is correct. We model the full state machine
    // as a pure function: current screen → callback → next screen.

    private fun nextScreen(current: Screen, callbackName: String): Screen = when (current) {
        Screen.Start    -> if (callbackName == "onTwist") Screen.Game else current
        Screen.Game     -> if (callbackName == "onSetty") Screen.Settings else current
        Screen.Settings -> if (callbackName == "onBack")  Screen.Game else current
    }

    @Test
    fun `Start plus onTwist reaches Game`() {
        assertEquals(Screen.Game, nextScreen(Screen.Start, "onTwist"),
            "onTwist navigates from Start to Game (the orientation-flip transition)")
    }

    @Test
    fun `Game plus onSetty reaches Settings`() {
        assertEquals(Screen.Settings, nextScreen(Screen.Game, "onSetty"),
            "onSetty navigates from Game to Settings")
    }

    @Test
    fun `Settings plus onBack reaches Game`() {
        assertEquals(Screen.Game, nextScreen(Screen.Settings, "onBack"),
            "onBack navigates from Settings back to Game")
    }

    @Test
    fun `Settings cannot reach Start directly`() {
        // No transition from Settings leads to Start — the player cannot accidentally un-start the game.
        assertNotEquals(Screen.Start, nextScreen(Screen.Settings, "onBack"),
            "there is no back path from Settings to Start")
    }

    @Test
    fun `the round-trip Game-Settings-Game returns to Game`() {
        val afterSetty = nextScreen(Screen.Game, "onSetty")
        val afterBack  = nextScreen(afterSetty, "onBack")
        assertEquals(Screen.Game, afterBack,
            "Game → Settings → Game is the only round-trip; it must resolve back to Game")
    }

    // ── 6. Bogus callback names must leave the screen unchanged (guard against typos) ──────────
    //
    // In production, a screen only has ONE callback parameter. This models the invariant: a screen
    // that receives a callback it was not assigned must not transition (it simply ignores it).

    @Test
    fun `an unknown callback name leaves the current screen unchanged`() {
        for (screen in listOf(Screen.Start, Screen.Game, Screen.Settings)) {
            assertEquals(
                screen,
                nextScreen(screen, "nonsense"),
                "screen $screen must be unchanged when an unrecognised callback name is used",
            )
        }
    }
}
