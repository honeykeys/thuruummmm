package com.thrum.game

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.thrum.deck.Deck
import com.thrum.deck.Thuruummm
import com.thrum.gesture.Finger
import com.thrum.gesture.GestureClassifier
import com.thrum.gesture.PointerBuffer
import com.thrum.gesture.PointerFrame
import com.thrum.gesture.Recognized
import com.thrum.haptics.ThuruummmHaptics
import com.thuruummm.physics.Cell
import com.thuruummm.physics.PhysicsEngine
import com.thuruummm.physics.Placement
import com.thuruummm.physics.StepResult

/**
 * The orchestrator state holder — the spine of the toy (ARCHITECTURE.md §3, C6).
 *
 * It owns every moving part of one play session and wires the pipeline
 *
 *   raw fingers → [PointerBuffer] → [GestureClassifier] → [Thuruummm] → [PhysicsEngine].place
 *               → [ThuruummmHaptics] (thur · rummmm · THRUUMMMM) → a [RenderSnapshot] read once/frame
 *
 * and it owns the build-field navigation the classifier deliberately does NOT: selecty (pick the
 * working slot) and navvy (pan the view).
 *
 * ── Why a plain @Stable class and NOT an AndroidX ViewModel (the load-bearing decision) ───────────
 *
 * The official criterion: a `ViewModel` earns its place when you "need access to business logic and
 * need the UI state to persist as long as a screen may be navigated to, even across `Activity`
 * recreation"; "for shorter-lived UI state and UI logic, a plain class whose lifecycle is dependent
 * solely on the UI should suffice." thuuruummm is exactly the second case: a per-frame, game-loop-
 * driven toy with NO data layer, NO business logic to survive a config change, and a fixed orientation
 * per screen (the manifest's `configChanges` keeps the Activity alive across the start→game flip —
 * RESEARCH-NATIVE.md §6 — so there is nothing to survive). A `ViewModel` would route per-frame state
 * through survives-recreation machinery the toy never uses and buys nothing.
 * Source: developer.android.com/topic/architecture/ui-layer/stateholders
 * ("Choose between a ViewModel and plain class for a state holder") — verified 2026-06-13.
 *
 * So this is `remember { GameState(...) }`-ed at the GameScreen (its lifecycle is the screen's), and
 * `@Stable` tells Compose the public surface obeys the stability contract: the one observable read,
 * [snapshot], is backed by `mutableStateOf` and notifies on change; everything else the Canvas reads
 * goes THROUGH that single State. Verified current 2026-06-13:
 * developer.android.com/develop/ui/compose/performance/stability (Stable contract),
 * developer.android.com/develop/ui/compose/state (state hoisting + UDF foundation).
 *
 * ── Recomposition discipline (the rule that keeps 60fps) ──────────────────────────────────────────
 *
 *  - The physics world is PLAIN Kotlin inside [engine] — never Compose state. The pointer buffer and
 *    navigation scalars are plain fields too. Nothing the loop touches mid-tick is observable.
 *  - Exactly ONE thing is Compose state: [_snapshot] (a `mutableStateOf<RenderSnapshot>`). The loop
 *    mutates plain state all tick, then writes ONE finished [RenderSnapshot] at the end. The Canvas
 *    reads [snapshot] inside its draw lambda, so only the DRAW phase re-runs per frame.
 *    (ARCHITECTURE.md §3; RESEARCH-NATIVE.md §4.)
 *
 * ── Threading contract ────────────────────────────────────────────────────────────────────────────
 *
 * LOOP-OWNED, with TWO published deposit points. The buffer, engine, classifier, and haptics facade
 * are touched only from the one `withFrameNanos` game-loop coroutine ([tick] drains [pendingFingers]
 * each frame). Input arrives by PUBLISH, never by shared mutation under the loop:
 *
 *  - [onFingers] — the raw-finger deposit from ui/'s `pointerInput`. A single reference assignment of
 *    an immutable list into a `@Volatile` slot; the loop reads-and-replaces it next tick.
 *  - [selectAdjacent] / [panBy] — the navigation intents (selecty/navvy). ui/ resolves a tap/drag to
 *    an intent and these record it. Their target/pan writes land in `@Volatile` slots ([targetCell],
 *    [panCellX]/[panCellY]) for the SAME reason: the resolved intent originates on the pointer-input
 *    dispatcher (C5 wiring), so it must publish to the loop, not mutate shared state mid-tick. Each is
 *    an idempotent single-field write plus a snapshot republish; a write racing a [tick] read sees one
 *    or the other whole value, never a torn one (a panBy delta concurrent with a read is at worst a
 *    one-frame-late pan, self-correcting next frame — never corruption). Do NOT call [reset] / [tick]
 *    concurrently with each other; those ARE loop-only (reset is the screen-restart path).
 *
 * The previous KDoc claimed every method but [onFingers] was loop-only; that was inconsistent with the
 * C5 wiring where selecty/navvy originate off the frame clock. The fields they touch are now `@Volatile`
 * so the published-deposit guarantee holds for all three input points, not just [onFingers].
 *
 * @param engine     The physics engine — stateful, owns the [com.thuruummm.physics.Grid]. Injectable
 *                   so a JVM test drives [GameState] with a real engine and asserts placement effects.
 * @param classifier Recognises a committed gesture against the deck. Injectable for tests / for the
 *                   Start screen's single-card twist recogniser.
 * @param haptics    The play-side two-beat + THRUUMMMM facade. Injectable as a [ThuruummmHaptics] over
 *                   a fake sink so the firing order is JVM-testable with no motor (ARCHITECTURE.md §6).
 * @param cardsById  Deck lookup by id; defaults to [Deck.byId]. Only used for diagnostics/logging —
 *                   the classifier already returns the full card, so the loop needs no second lookup.
 */
@Stable
class GameState(
    private val engine: PhysicsEngine = PhysicsEngine(),
    private val classifier: GestureClassifier = GestureClassifier(),
    private val haptics: ThuruummmHaptics,
    private val cardsById: Map<String, Thuruummm> = Deck.byId,
    /**
     * Play-side placement telemetry sink. Default no-op so game/ stays android-free and JVM-pure (the
     * ARCHITECTURE seam: logic in game/, the android edge in ui/). Production wiring in [GameScreen]
     * passes `{ android.util.Log.d("THRUM", it) }`; unit tests leave it as the no-op, so a commit/lift
     * tick never reaches an unmocked `android.jar` method. Watch on device with `adb logcat -s THRUM`.
     */
    private val log: (String) -> Unit = {},
) {

    // ── The single observable: the frame's draw model ────────────────────────────────────────────

    /**
     * The one Compose state in the whole holder. Private and mutable here (the loop is the only
     * writer); exposed downward only as a read-only [State] via [snapshot], so the Canvas can read but
     * never write. Backed by `mutableStateOf` so a swap notifies the draw lambda; structural equality
     * is the default policy, which is correct for an `@Immutable` value (a no-op swap to an equal
     * snapshot does not re-draw).
     */
    private val _snapshot: MutableState<RenderSnapshot> = mutableStateOf(RenderSnapshot.EMPTY)

    /** The ONE state the Canvas observes. Read `snapshot.value` inside the draw lambda; loop-written only. */
    val snapshot: State<RenderSnapshot> get() = _snapshot

    // ── Plain (non-Compose) session state the loop owns ──────────────────────────────────────────

    /** The live gesture-in-progress window. Plain Kotlin; touched only by the loop (gesture/PointerFrame.kt). */
    private val buffer = PointerBuffer()

    /**
     * The working slot — where the next committed brick lands (selecty). Engine space (y-up). Starts
     * at the first ground cell so the very first gesture has somewhere to land with no prior brick.
     * Updated by [selectAdjacent] (a published navigation intent) and by [commit]'s auto-advance;
     * consumed by [tick] as the classifier's targetCell. `@Volatile`: [selectAdjacent] may publish from
     * the pointer-input dispatcher while [tick] reads from the loop (threading contract above).
     */
    @Volatile
    private var targetCell: Cell = Cell(0, 0)

    /**
     * Pan offset in cells (navvy). The snapshot forwards them; the Canvas applies them. `@Volatile` for
     * the same published-deposit reason as [targetCell]: [panBy] may run on the pointer-input dispatcher
     * while [tick] reads these to build the snapshot (threading contract above). A read-modify-write
     * (`+=`) racing a loop read is at worst one frame late — self-correcting, never torn.
     */
    @Volatile
    private var panCellX: Float = 0f
    @Volatile
    private var panCellY: Float = 0f

    /**
     * The previous tick's pressed-finger count — the basis of the cheap classify GATE in [tick]. Seeded
     * to 0 (no fingers before the first frame) so the first finger-down is seen as a transition. Plain
     * field, loop-only (written and read solely inside [tick]).
     */
    private var lastPressedCount: Int = 0

    /**
     * Consecutive ticks the hand has been entirely off the glass. Used to clear the rolling [buffer]
     * after a sustained absence so a NEW gesture never appends onto a stale, abandoned tail (a fumble
     * that lifted without a valid flourish would otherwise leave frames the next gesture's feature
     * extractor could anchor on). Loop-only.
     */
    private var emptyFrameRun: Int = 0

    /**
     * The most recent batch of raw fingers handed up by `pointerInput`, awaiting the next tick. The
     * loop reads-and-clears this each frame to build one [PointerFrame]. A single immutable-list slot
     * (latest-wins within a frame): Compose can deliver several pointer events between two display
     * frames; the loop only needs the freshest finger set per frame, so older intra-frame batches are
     * naturally coalesced. @Volatile for safe publication across the pointerInput dispatcher → loop
     * hand-off (see the threading contract above).
     */
    @Volatile
    private var pendingFingers: List<Finger> = emptyList()

    /**
     * Per-brick glyph, keyed by the physics brick id. The render needs each placed brick's glyph, but
     * the engine is deck-ignorant (a [com.thuruummm.physics.Brick] carries only [Material]) and several
     * cards may share ONE Material — the 7 swipey stubs all do — so inverting Material→Glyph collapses
     * them to a single arrow. We instead record the MINTING card's glyph against the brick id at [commit],
     * where the card is known, and read it back in [buildSnapshot]. Ids are monotonic and preserved
     * through a fall, so a brick keeps its glyph even after it tumbles; entries are pruned when a brick
     * collapses away. Loop-owned (written in [commit]/[reset] from the tick; read in [buildSnapshot]).
     */
    private val glyphById: MutableMap<Int, com.thrum.deck.Glyph> = mutableMapOf()

    /** Whether this device can buzz — probed once from the haptics facade; forwarded to the snapshot. */
    private val hapticsAvailable: Boolean = haptics.capabilities.hasVibrator

    // ── Input deposit (called from the Compose pointerInput callback) ─────────────────────────────

    /**
     * Deposit the current finger set for the loop to consume on its next tick. Called by ui/'s
     * `pointerInput` adapter (C5) on each pointer event, AFTER it has mapped Compose
     * `PointerInputChange`s to plain [Finger]s (the adapter is the only Compose-pointer-aware code —
     * gesture/Finger.kt documents the exact field mapping). This method does NO classification and
     * touches no engine/buffer state — it only publishes the latest fingers. The loop owns the
     * buffer and the timeline; input merely arrives here.
     */
    fun onFingers(fingers: List<Finger>) {
        pendingFingers = fingers
    }

    // ── The per-tick entry the GameLoop calls (the pipeline; see GameLoop.kt) ─────────────────────

    /**
     * Advance one frame. Called by [GameLoop] inside `withFrameNanos` with that frame's nanos.
     *
     *  1. Snapshot the pending fingers into one [PointerFrame] stamped with [frameTimeNanos] and push
     *     it onto the rolling [buffer] (time-as-data — gesture/ reads it, never a clock).
     *  2. Cheaply gate the expensive classify (see [shouldClassify]): attempt recognition only on a
     *     tick where a commit could PLAUSIBLY land — the hand is on the glass (any hold-form flourish
     *     could fire) or a pressed-count transition just occurred (the all-lift edge). A steady hand-off
     *     idle run does NOT classify, so the per-frame `buffer.frames()` defensive-copy + classify cost
     *     the PointerBuffer KDoc warns about is paid only at a gesture boundary, never every idle frame.
     *     The gate encodes NO single flourish form (the coupling fix): it lets the [classifier]'s own
     *     [com.thrum.gesture.Flourish] strategy decide — all-lift, deliberate-hold, or any future form.
     *  3. On a [Recognized], run [commit] — the thur → place → rummmm → THRUUMMMM pipeline.
     *  4. Rebuild the [RenderSnapshot] from the engine's current grid + nav + (this-frame) collapse,
     *     and publish it as the single observable write.
     *
     * Returns the [Recognized] that fired this tick (or null) — handy for tests and for the loop to
     * decide motion side-effects; the snapshot write is the real output.
     */
    fun tick(frameTimeNanos: Long): Recognized? {
        // (1) build this frame's pointer snapshot and push it.
        val frame = PointerFrame(fingers = pendingFingers, timeNanos = frameTimeNanos)
        buffer.push(frame)
        val pressed = frame.pressedCount

        // (2) cheap gate before the expensive classify. The gate is form-AGNOSTIC: it decides only
        // *whether a commit is plausible this tick*, never *what the finish looks like* — that is the
        // classifier's Flourish strategy's job. We classify when the hand is down (a hold flourish could
        // commit now) OR when the pressed count just changed (a finger landed/lifted — the all-lift
        // form's commit edge is exactly the down→0 transition). A steady all-off idle run is neither, so
        // it never pays the defensive-copy + classify cost (gesture/PointerFrame.kt HOT-PATH NOTE).
        var fired: Recognized? = null
        var step: StepResult? = null
        if (shouldClassify(pressed) && buffer.size >= 2) {
            val recognized = classifier.classify(buffer.frames(), targetCell)
            if (recognized != null) {
                step = commit(recognized)
                fired = recognized
                log(
                    "PLACED ${recognized.card.id} @(${recognized.targetCell.x},${recognized.targetCell.y}) " +
                        "score=${recognized.score} collapse=${step.collapse != null}",
                )
                buffer.clear() // a committed gesture is consumed; the next gesture starts clean.
            }
        }

        // Lift-edge telemetry (on-thumb tuning aid). When the hand fully leaves the glass WITHOUT a
        // commit, log the peak finger count the window reached. This single line separates the two
        // failure modes that look identical from the couch: "the finger floor was never reached"
        // (peak < 4 — Karl needs more fingers / better tracking) vs "a good lift the flourish gate
        // still rejected" (peak >= 4 — the gate needs tuning). Cheap: fires only on the lift edge.
        // The logger is INJECTED (default no-op) so game/ stays android-free and JVM-pure: ui/ wires the
        // real android.util.Log at the edge, and unit tests never touch the unmocked android.jar.
        if (pressed == 0 && lastPressedCount > 0 && fired == null) {
            val peak = buffer.frames().maxOfOrNull { it.pressedCount } ?: 0
            log("lift · no place · peakFingers=$peak (floor=4) frames=${buffer.size}")
        }

        // (3) housekeeping for the gate + the buffer's hygiene. Track the pressed run so a SUSTAINED
        // hand-off (a fumble that lifted with no valid flourish, then a pause) drops the stale tail:
        // otherwise the next gesture would append onto abandoned frames and the feature extractor's
        // "first frame at peak count" could anchor on the prior pose, smearing the new gesture's
        // measured drift/spread. We clear only after the absence is clearly sustained (not on the very
        // first empty frame), so an all-lift flourish — which commits ON the first empty frame, in step
        // (2) above, BEFORE this housekeeping — is never robbed of its window.
        if (pressed == 0) {
            emptyFrameRun++
            if (fired == null && emptyFrameRun >= STALE_BUFFER_CLEAR_FRAMES) buffer.clear()
        } else {
            emptyFrameRun = 0
        }
        lastPressedCount = pressed

        // (4) publish the frame's draw model. Built every tick: even with no commit, stress can drift
        // and the Canvas needs a fresh value to drive the tremor; the cost is one immutable rebuild.
        _snapshot.value = buildSnapshot(step)
        return fired
    }

    /**
     * The classify GATE (ARCHITECTURE.md §3 "no per-frame broad work"): is a commit plausible this tick?
     *
     *  - hand on the glass (`pressed > 0`) → yes: a deliberate-hold flourish commits WHILE pressed, and
     *    an in-progress gesture's window grows here. The work is bounded — a gesture lasts a brief burst
     *    of frames, not the idle stretches between gestures.
     *  - a pressed-count CHANGE (`pressed != lastPressedCount`) → yes: a finger just landed or lifted.
     *    The all-lift flourish's commit moment is precisely the last-pressed → all-gone (`→ 0`)
     *    transition; this fires classify on that single edge, not on the flat idle run after it.
     *  - otherwise (steady `pressed == 0`, unchanged) → no: the hand is off the glass and nothing moved.
     *    No flourish form can newly commit on an unchanged empty window, so we skip the classify and its
     *    `buffer.frames()` allocation entirely. This is the idle-frame allocation fix.
     */
    private fun shouldClassify(pressed: Int): Boolean = pressed > 0 || pressed != lastPressedCount

    // ── The commit pipeline (ARCHITECTURE.md §2/§4; firing order is load-bearing) ─────────────────

    /**
     * Fire one committed placement. The order is the heartbeat the player navigates by, and it is the
     * contract the haptics facade + its FakeVibratorOrderTest pin:
     *
     *   1. thur()                  — beat 1, the INSTANT the flourish read, BEFORE physics. Uniform.
     *   2. engine.place(...)       — physics consumes ONLY the card's Material (deck-ignorant engine).
     *   3. rummmm(card.rummmm)     — beat 2, immediately after place returns. This card's character.
     *   4. if collapse: thruummm() — the reward, motor-priority-locked so a later tap cannot cut it.
     *
     * On a collapse, [selectAdjacent]-style retargeting is intentionally NOT auto-run — the player
     * re-picks their slot; the engine's grid is already stabilised by place(). We DO defensively
     * keep [targetCell] valid for the next gesture (see end of method).
     *
     * @return the [StepResult] so [tick] can forward the collapse to the snapshot. Pure pass-through.
     */
    private fun commit(r: Recognized): StepResult {
        haptics.thur()                                                   // 1 — beat one, pre-physics
        val step = engine.place(Placement(r.card.material, r.targetCell)) // 2 — physics (Material only)
        haptics.rummmm(r.card.rummmm)                                    // 3 — beat two, card character
        step.collapse?.let { haptics.thruummm(it) }                      // 4 — the reward, priority-locked

        // Label the brick that just landed with its minting card's glyph (keyed by the stable brick id —
        // the join the deck-ignorant engine cannot give us, and the fix for the 7 swipeys collapsing to
        // one arrow). Only if it survived at the target cell; a placement that immediately collapsed away
        // leaves nothing to label and falls back harmlessly in buildSnapshot.
        step.grid.at(r.targetCell)?.let { glyphById[it.id] = r.card.glyph }
        // Prune labels for bricks the collapse consumed so the map cannot grow without bound.
        step.collapse?.fell?.forEach { glyphById.remove(it.id) }

        // Keep a sane working slot for the next gesture: if the brick we just placed is still standing
        // (no collapse consumed it), advance the target to a slot adjacent to it so the build grows
        // outward (DESIGN §"Build model": build outward from existing bricks). If it collapsed away,
        // fall back to the lowest free ground slot so play can always continue.
        targetCell = nextDefaultTarget(r.targetCell, placedSurvives = step.grid.occupied(r.targetCell))
        return step
    }

    // ── Navigation the classifier does NOT own (selecty + navvy) ──────────────────────────────────

    /**
     * selecty — pick the working slot (DESIGN §"Utility gestures": "a simple tap — selects the next
     * adjacent slot"). The slot must be a FREE cell ADJACENT to an existing brick (DESIGN §"Build
     * model": only slots adjacent to placed bricks are interactable) — or, on an empty field, the
     * ground origin so the first brick has a home.
     *
     * @param direction which neighbour of the current target to move to (the tap location / cycle
     *                  resolves to one of these in ui/; game/ takes the resolved intent, not pixels —
     *                  the classifier-owns-recognition, game-owns-board-state split, mirrored here).
     *                  We accept the requested cell only if it is a legal build slot; otherwise the
     *                  target is unchanged (a tap into the void is a no-op, not an illegal target).
     * @return the resulting target cell (so a test/ui can confirm the move took).
     */
    fun selectAdjacent(direction: SlotDir): Cell {
        val candidate = when (direction) {
            SlotDir.UP -> targetCell.above()
            SlotDir.DOWN -> targetCell.below()
            SlotDir.LEFT -> targetCell.left()
            SlotDir.RIGHT -> targetCell.right()
        }
        if (isBuildableSlot(candidate)) targetCell = candidate
        // Publish so the highlight moves on the glance even with no placement this frame.
        _snapshot.value = buildSnapshot(step = null)
        return targetCell
    }

    /**
     * selecty — pick the working slot DIRECTLY by the cell the finger long-pressed (Karl: "select the
     * grid slot next to any current block"). This is the ABSOLUTE counterpart to [selectAdjacent]'s
     * relative nudge: ui/ inverts the press position to a grid cell and hands it here. The cell is
     * accepted only if it is a legal build slot — a FREE cell on the ground (y==0) or adjacent to a
     * placed brick. A press onto an occupied cell or into the void is a no-op (the slot is unchanged),
     * never an illegal target. game/ owns the board rule; ui/ owns the screen→cell mapping.
     *
     * @param cell the grid cell the press maps to.
     * @return the resulting target cell (so a test/ui can confirm whether the press took).
     */
    fun selectCell(cell: Cell): Cell {
        val buildable = isBuildableSlot(cell)
        log("selecty press @(${cell.x},${cell.y}) buildable=$buildable")
        if (buildable) {
            targetCell = cell
            _snapshot.value = buildSnapshot(step = null)
        }
        return targetCell
    }

    /**
     * navvy — pan the view (DESIGN §"Utility gestures": "a whole-hand slide that does not flourish —
     * pans the view across a large build"). Translation-WITHOUT-commit is navigation; the same slide
     * WITH a flourish would have been a placement (and the classifier would have caught it as a
     * swipey). The loop routes a non-committing whole-hand drift here.
     *
     * Accumulates a CELL-space pan delta (sub-cell, smooth). The Canvas subtracts the pan from each
     * brick's cell before mapping to pixels, so the world is fixed and the camera moves. game/ owns
     * the camera scalar; ui/ owns the px→cell scale that produced [dCellX]/[dCellY].
     */
    fun panBy(dCellX: Float, dCellY: Float) {
        panCellX += dCellX
        panCellY += dCellY
        _snapshot.value = buildSnapshot(step = null)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────────

    /** Reset to an empty field (the Start-screen restart path). Clears physics, buffer, nav, haptics. */
    fun reset() {
        engine.reset()
        buffer.clear()
        glyphById.clear()
        pendingFingers = emptyList()
        targetCell = Cell(0, 0)
        panCellX = 0f
        panCellY = 0f
        lastPressedCount = 0
        emptyFrameRun = 0
        haptics.cancel()
        _snapshot.value = RenderSnapshot.EMPTY
    }

    /** Stop any in-flight vibration (screen exit). The loop's coroutine cancellation handles the rest. */
    fun onLeave() {
        haptics.cancel()
    }

    // ── Snapshot construction (pure: engine grid + nav + this-frame collapse → draw model) ────────

    /**
     * Build the immutable [RenderSnapshot] from the engine's current grid and the navigation state.
     * [step] is non-null only on a tick that placed a brick; its collapse (if any) becomes the
     * one-frame [CollapseView] trigger the render latches its shake/tumble to.
     *
     * The glyph join lives here: the physics [com.thuruummm.physics.Brick] carries id+cell+material
     * but NOT a glyph (the engine is deck-ignorant). We recover the glyph by matching the brick's
     * Material back to the card that mints it. This is the ONE place game/ bridges physics-space and
     * deck-space for the render.
     */
    private fun buildSnapshot(step: StepResult?): RenderSnapshot {
        val grid = engine.grid
        val bricks = grid.bricks.values.map { b ->
            BrickView(
                id = b.id,
                cell = b.cell,
                // Each brick's OWN minting glyph (recorded at commit by brick id); the Material→Glyph
                // inversion is only a fallback for any brick not minted through commit (never null).
                glyph = glyphById[b.id] ?: glyphFor(b.material),
                material = b.material,
            )
        }
        val collapseView = step?.collapse?.let { c ->
            CollapseView(
                magnitude = c.magnitude,
                rings = c.rings,
                fellIds = c.fell.map { it.id },
            )
        }
        return RenderSnapshot(
            bricks = bricks,
            targetCell = targetCell,
            panCellX = panCellX,
            panCellY = panCellY,
            stress = step?.stress ?: com.thuruummm.physics.Stress.margin(grid),
            collapse = collapseView,
            hapticsAvailable = hapticsAvailable,
        )
    }

    /**
     * Glyph for a placed brick, recovered by its [com.thuruummm.physics.Material]. The deck maps card →
     * material → glyph; the physics keeps only the material, so we invert the map. Built once (lazily)
     * from the deck — adding a card to [Deck.CARDS] extends it automatically, honouring the
     * "adding a card changes nothing else" invariant: the render glyph for a new material appears here
     * with no edit. If two cards ever share a Material (the deck property tests forbid distinct glyphs
     * on one material, but not distinct materials per glyph), the first card wins deterministically —
     * a documented, test-pinned tie-break, never silent corruption.
     */
    private fun glyphFor(material: com.thuruummm.physics.Material): com.thrum.deck.Glyph =
        glyphByMaterial[material] ?: com.thrum.deck.Glyph.ARROW_CENTER

    private val glyphByMaterial: Map<com.thuruummm.physics.Material, com.thrum.deck.Glyph> =
        // associateBy keeps the LAST on a key collision; we want the FIRST card to win, so build
        // explicitly, skipping a material already seen. Explicit map type so inference cannot drift.
        buildMap {
            for (card in cardsById.values) putIfAbsent(card.material, card.glyph)
        }

    // ── Build-slot rules (DESIGN §"Build model": build outward from adjacency) ────────────────────

    /**
     * A cell is a legal placement slot iff it is FREE and either on the ground (y == 0, always a legal
     * footing) or ADJACENT (4-neighbour) to an existing brick — "you build outward from existing
     * bricks; only slots adjacent to placed bricks are interactable" (DESIGN.md). 4-neighbour matches
     * the physics grid's adjacency (Cell.neighbours()).
     */
    private fun isBuildableSlot(cell: Cell): Boolean {
        val grid = engine.grid
        if (grid.occupied(cell)) return false
        if (cell.y < 0) return false                       // nothing below bedrock
        if (cell.y == 0) return true                       // ground is always a legal first footing
        return cell.neighbours().any { grid.occupied(it) }  // adjacent to a placed brick
    }

    /**
     * The default working slot after a placement. If the placed brick survives, prefer a free cell
     * directly above it (towers grow up) else any free legal neighbour, so the build keeps growing
     * outward without a selecty between every brick. If nothing survived at [placed] (it collapsed),
     * fall back to the lowest free ground cell so play can always continue.
     */
    private fun nextDefaultTarget(placed: Cell, placedSurvives: Boolean): Cell {
        val grid = engine.grid
        if (placedSurvives) {
            val above = placed.above()
            if (isBuildableSlot(above)) return above
            placed.neighbours().firstOrNull { isBuildableSlot(it) }?.let { return it }
        }
        // Fallback: lowest free cell on the ground row near x=0, scanning outward.
        var dx = 0
        while (dx <= MAX_GROUND_SCAN) {
            for (x in intArrayOf(-dx, dx)) {
                val c = Cell(x, 0)
                if (isBuildableSlot(c)) return c
            }
            dx++
        }
        return Cell(0, 0)
    }

    private companion object {
        /** How far along the ground row the default-target fallback scans for a free cell. */
        const val MAX_GROUND_SCAN = 64

        /**
         * Ticks the hand must be entirely off the glass before [tick] drops the rolling [buffer]'s
         * stale tail. ~0.5s at 60fps / ~0.25s at 120fps — long enough that it never races a real
         * flourish (which commits on the first empty frame), short enough that a fumbled, abandoned
         * gesture cannot bleed into the next one. First guess; tuned on the thumb with the buffer
         * capacity. Must be >= 1.
         */
        const val STALE_BUFFER_CLEAR_FRAMES = 32
    }
}

/** The four build-slot directions selecty can move the working slot. Resolved from a tap in ui/. */
enum class SlotDir { UP, DOWN, LEFT, RIGHT }
