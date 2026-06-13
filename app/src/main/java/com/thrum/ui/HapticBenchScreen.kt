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
import com.thrum.deck.Deck
import com.thrum.haptics.Capabilities
import com.thrum.haptics.Haptic
import com.thrum.haptics.HapticEngine
import com.thrum.haptics.HapticLibrary
import com.thrum.haptics.Primitive
import com.thrum.haptics.ThuruummmHaptics
import com.thuruummm.physics.Brick
import com.thuruummm.physics.Cell
import com.thuruummm.physics.CollapseResult
import com.thuruummm.physics.Grid
import com.thuruummm.physics.Material

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
    // The toy's play-side facade over the same engine — the two-beat commit + THRUUMMMM. Reuses
    // the one motor/probe; the bench plays through it to run the legibility gate on the real toy
    // feel, not just the raw library patterns.
    val toy = remember { ThuruummmHaptics(engine) }

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

        CommitBench(toy)
    }
}

/**
 * The toy's actual commit + collapse feel, played through [ThuruummmHaptics] (the same two-beat
 * and THRUUMMMM the game loop fires). Lets the thumb run the legibility gate on the real pipeline:
 * each card's *thur → rummmm* heartbeat, and a low/mid/high THRUUMMMM so the magnitude→richness
 * map can be felt to scale. Dev-only; the toy itself shows no labels.
 */
@Composable
private fun CommitBench(toy: ThuruummmHaptics) {
    Text("commit two-beat (thur → rummmm), per card", style = MaterialTheme.typography.labelLarge)
    Deck.CARDS.forEach { card ->
        Button(
            onClick = {
                // The game loop's firing order: beat 1 uniform, then this card's character beat.
                toy.thur()
                toy.rummmm(card.rummmm)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("${card.id}  ·  thur+rummmm") }
    }

    Text("THRUUMMMM — magnitude → richness", style = MaterialTheme.typography.labelLarge)
    // Synthetic collapses spanning the magnitude range, so the slam/tumble can be felt to scale.
    // (Pure data — no physics run needed to exercise the haptic map.)
    val pebble = Material(1.0, 3.0, 0, 2, 0.2)
    val wood = Material(1.5, 5.0, 2, 3, 0.1)
    val glass = Material(1.0, 2.0, 0, 1, 0.9)
    fun synthetic(fellCount: Int, rings: Int, mats: Set<Material>): CollapseResult {
        val matList = mats.toList()
        val fell = (0 until fellCount).map { Brick(it, matList[it % matList.size], Cell(it, 0)) }
        return CollapseResult(fell = fell, rings = rings, materials = mats, finalGrid = Grid())
    }
    val samples = listOf(
        "small  (1 brick · 1 ring)"        to synthetic(1, 1, setOf(pebble)),
        "tower  (12 bricks · 1 ring · 1)"  to synthetic(12, 1, setOf(pebble)),
        "tangle (6 bricks · 4 rings · 3)"  to synthetic(6, 4, setOf(pebble, wood, glass)),
        "vast   (40 bricks · 7 rings · 3)" to synthetic(40, 7, setOf(pebble, wood, glass)),
    )
    samples.forEach { (label, collapse) ->
        Button(
            onClick = { toy.thruummm(collapse) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("THRUUMMMM · $label") }
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
