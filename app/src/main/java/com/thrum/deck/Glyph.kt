package com.thrum.deck

/**
 * The glyph stamped on a placed brick — the visual identity the render reads.
 *
 * Glyphs are the player's vocabulary: they decode what a brick IS by recognising its mark
 * rather than reading a label. Every glyph maps 1:1 to a card; no two cards share one.
 * The render (FieldCanvas) switches on this enum to know which shape to draw.
 *
 * Naming convention: directional glyphs (ARROW_*) reflect the swipey direction that minted
 * the brick. Non-directional glyphs (SPIRAL, etc.) describe the shape the player will see.
 *
 * DESIGN (DESIGN.md): "never labelled" — the screen shows the glyph, never the card name.
 * The player earns the vocabulary by building.
 */
enum class Glyph {
    // --- fully implemented ---
    ARROW_CENTER,       // tappy: fingers-to-point; an inward-pointing arrangement of arrows
    SPIRAL,             // twisty: the rotation path; a clockwise spiral

    // --- swipey directions (one per card; the arrow points the swipe direction) ---
    ARROW_RIGHT,
    ARROW_DOWN_RIGHT,
    ARROW_DOWN,
    ARROW_DOWN_LEFT,
    ARROW_LEFT,
    ARROW_UP,
    ARROW_UP_RIGHT,

    // --- stub slots — Karl assigns these as the remaining cards are authored ---
    STUB_A,
    STUB_B,
    STUB_C,
    STUB_D,
    STUB_E,
    STUB_F,
    STUB_G,
    STUB_H,
}
