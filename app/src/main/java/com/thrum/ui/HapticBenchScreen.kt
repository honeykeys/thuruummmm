package com.thrum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thrum.haptics.Capabilities
import com.thrum.haptics.Haptic
import com.thrum.haptics.HapticEngine
import com.thrum.haptics.HapticLibrary
import com.thrum.haptics.Primitive

/**
 * The haptic bench — a dev tool, not the toy. Its job is verification: surface the device's
 * capability list, and let the thumb play each [Haptic] on demand. Labels are allowed here
 * precisely because this is where we *name* the patterns; the no-legend invariant governs the
 * toy, not the bench. The blind toggle is the legibility test: play a random block, name it,
 * then reveal.
 */
@Composable
fun HapticBenchScreen() {
    val context = LocalContext.current
    val engine = remember { HapticEngine(context.applicationContext) }
    val caps = engine.capabilities

    var blind by remember { mutableStateOf(false) }
    var lastBlind by remember { mutableStateOf<Haptic?>(null) }
    var revealed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("thrum · haptic bench", style = MaterialTheme.typography.titleLarge)

        CapabilityCard(caps)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "blind test",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = blind, onCheckedChange = { blind = it; revealed = false })
        }

        if (blind) {
            Button(
                onClick = {
                    val pick = HapticLibrary.blocks.random()
                    lastBlind = pick
                    revealed = false
                    engine.play(pick)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("play a random block") }
            Button(
                onClick = { lastBlind?.let { engine.play(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("again") }
            Button(
                onClick = { revealed = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (revealed) "it was: ${lastBlind?.label ?: "—"}" else "reveal") }
        } else {
            HapticLibrary.all.forEach { h ->
                Button(
                    onClick = { engine.play(h, priority = h.label == "crash") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(h.label) }
            }
        }
    }
}

@Composable
private fun CapabilityCard(caps: Capabilities) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("device-level list", style = MaterialTheme.typography.labelLarge)
        Text("motor: ${if (caps.hasVibrator) "yes" else "NONE — emulator or no actuator"}")
        Text("amplitude control: ${if (caps.hasAmplitudeControl) "yes" else "no"}")
        Primitive.entries.forEach { p ->
            val ok = caps.isSupported(p)
            val dur = caps.durationsMs[p] ?: 0
            Text("${if (ok) "●" else "○"}  ${p.name.lowercase()}  —  ${if (ok) "$dur ms" else "unsupported"}")
        }
    }
}
