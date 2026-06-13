package com.thrum.haptics

/**
 * The thin play surface the game-side facade ([ThuruummmHaptics]) depends on — the seam that lets
 * the firing order and the crash-wins-the-motor coalescing be unit-tested on the JVM with a fake,
 * no device, no Android Context (ARCHITECTURE.md §6, the haptics test row: "inject a fake").
 *
 * The production implementation is [HapticEngine] — it already exposes exactly this surface
 * (probe-once [capabilities], [play] with the `priority`/cancel-first contract, [cancel]); this
 * interface simply *names* that surface so a `FakeHapticSink` in a test can record calls instead
 * of driving a real motor. No behaviour is duplicated; the engine remains the one device layer.
 */
interface HapticSink {
    /** What this device's single motor admits — probed once. */
    val capabilities: Capabilities

    /** Play a haptic. [priority] = true cancels whatever is buzzing first (crash wins the motor). */
    fun play(haptic: Haptic, priority: Boolean = false)

    /** Stop the current vibration. */
    fun cancel()
}
