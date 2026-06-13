package com.thrum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The Settings screen — reached only via the "setty" gesture (the secret handshake).
 *
 * Orientation: landscape (the player is already holding the phone landscape from GameScreen;
 * keeping landscape avoids a jarring rotation).
 *
 * ── Hackathon scope ─────────────────────────────────────────────────────────────────────────
 *
 * This is a minimal stub: it confirms to the player that the setty gesture landed (the "secret
 * handshake" haptic has already fired by the time the state machine switches screens) and
 * provides a tap-to-return path. Post-demo: sound toggle, haptic intensity, grid size, reset.
 * Those are Karl's call — the structure exists, the content is a stub.
 *
 * ── Navigation ──────────────────────────────────────────────────────────────────────────────
 *
 * [onBack] returns to GameScreen. In the hackathon build: tap anywhere. The full
 * setty-detect-on-settings path (running a minimal classifier here) is deferred post-demo.
 *
 * @param onBack Called to return to GameScreen.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    // Orientation is now locked at the ThuruummmApp root (P3c fix). No LockOrientation call here.
    // ThuruummmApp maps Screen.Settings → SCREEN_ORIENTATION_LANDSCAPE, same as Game, so no
    // orientation change fires on the Game↔Settings transition.

    // The whole screen is a tap target — any touch returns to Game.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Consume the first pointer event and return to Game.
                    awaitPointerEvent()
                    onBack()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text  = "thuuruummm",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF7EC8E3),
            )
            Text(
                text  = "settings",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.60f),
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text  = "tap anywhere to return",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.35f),
            )
        }
    }
}
