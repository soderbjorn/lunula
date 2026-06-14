/**
 * Encircled-glyph mapping used to mark each pane with a small at-a-glance
 * identifier in its titlebar and sidebar entry.
 *
 * The toolkit chose Unicode glyphs (`①..⑨`, `Ⓐ..Ⓩ`) rather than
 * CSS-drawn circles so the marker stays a single character at all sizes and
 * inherits the user's theme typography.
 *
 * The actual badge DOM is built by the toolkit's web renderers
 * (`PaneHeader.kt`, `SidebarSection.kt`); only the pure glyph mapping lives
 * here so it can be unit-tested on the JVM and reused by any future
 * non-DOM consumer (Compose previews, etc.).
 *
 * @see PaneSlotAssigner for the sticky-slot logic that produces these
 *   indices.
 */
package se.soderbjorn.darkness.web.util

/**
 * Returns the encircled glyph for a 1-based [index], or `null` when the
 * index falls outside the renderable range. Mapping:
 *
 * - `1..9`   → `①..⑨` (Unicode `U+2460..U+2468`)
 * - `10..35` → `Ⓐ..Ⓩ` (Unicode `U+24B6..U+24CF`)
 * - everything else → `null` (callers should skip rendering the badge so
 *   the header degrades gracefully to its un-indexed look)
 *
 * The 35-slot ceiling matches [PaneSlotAssigner]'s sticky range; if that
 * range ever grows, extend this mapping in lockstep.
 *
 * @param index 1-based pane slot.
 * @return single-character glyph string, or `null` for out-of-range indices.
 */
fun encircledIndexGlyph(index: Int): String? = when (index) {
    in 1..9 -> ('①' + (index - 1)).toString()
    in 10..35 -> ('Ⓐ' + (index - 10)).toString()
    else -> null
}
