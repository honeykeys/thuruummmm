package com.thrum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.thrum.ui.ThuruummmApp

/**
 * Single-activity Compose entry.
 *
 * Hosts [ThuruummmApp] — the 3-screen state machine (Start → Game → Settings).
 * Orientation per screen is enforced by [com.thrum.ui.LockOrientation] inside each screen
 * composable via [Activity.requestedOrientation]; the manifest declares
 * `android:configChanges="orientation|screenSize|keyboardHidden"` so the Activity is never
 * recreated on the portrait→landscape flip between Start and Game.
 *
 * The haptic bench ([com.thrum.ui.HapticBenchScreen]) is preserved in the source tree for
 * manual device testing of the haptic pipeline. To switch back, replace [ThuruummmApp] with
 * [com.thrum.ui.HapticBenchScreen] here — no other file changes needed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Explicit near-black background so the Canvas blobs/arcs draw on the intended
                // dark field, not on whatever darkColorScheme().surface happens to be (P3a fix).
                // 0x0A0A0F: near-black with a faint blue-violet undertone, matching the kintsugi
                // field aesthetic. Karl tunes the exact value on device.
                Surface(color = Color(0xFF0A0A0F)) {
                    ThuruummmApp()
                }
            }
        }
    }
}
