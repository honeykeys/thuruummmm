package com.thrum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import com.thrum.ui.HapticBenchScreen

/**
 * Single-activity Compose entry. For the haptic bench this hosts only the test screen; the toy
 * itself (Canvas + withFrameNanos game loop + pointerInput multitouch) comes in a later brick.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    HapticBenchScreen()
                }
            }
        }
    }
}
