/* ResolvedTheme.kt
 * The flat, ready-to-render form of a [Theme]: the 23 semantic tokens as
 * opaque ARGB [Long]s, plus a handful of structural aliases (cursor,
 * selection, titlebar …) that map directly onto existing tokens — by direct
 * reference, never by colour maths. Every renderer (web CSS vars, Android
 * Compose, iOS SwiftUI) consumes this single struct.
 */
package se.soderbjorn.darkness.core

/**
 * A theme resolved to ARGB [Long] values (`0xAARRGGBB`).
 *
 * Produced by [Theme.resolve]. The 20 stored tokens plus the 3 derived
 * translucent ones (accentSoft / glow / addBg) are exposed directly; the
 * extra UI needs that aren't their own token (cursor, selection, chrome) are
 * exposed as aliases that reference one of the tokens — a structural
 * assignment, not a derived colour. The syntax palette has 8 dedicated slots.
 * Window traffic-light dots are OS-semantic fixed colours, not theme tokens.
 *
 * @see Theme
 * @see ThemeSnapshotV2
 */
data class ResolvedTheme(
    val bg: Long,
    val surface: Long,
    val surfaceAlt: Long,
    val border: Long,
    val text: Long,
    val textDim: Long,
    val textBright: Long,
    val accent: Long,
    val accentSoft: Long,
    val glow: Long,
    val warn: Long,
    val danger: Long,
    val add: Long,
    val addBg: Long,
    val addText: Long,
    val synKeyword: Long,
    val synString: Long,
    val synNumber: Long,
    val synComment: Long,
    val synFunction: Long,
    val synType: Long,
    val synOperator: Long,
    val synConstant: Long,
) {
    // ----- Structural aliases (direct token references; no colour maths) -----

    /** Terminal cursor — uses the [accent] token. */
    val cursor: Long get() = accent
    /** Terminal selection background — uses the [accentSoft] token. */
    val selectionBg: Long get() = accentSoft
    /** Terminal selection text — uses the [bg] token. */
    val selectionText: Long get() = bg
    /** Window title-bar fill — uses the [surfaceAlt] token. */
    val titlebar: Long get() = surfaceAlt
    /** Window title text — uses the [textBright] token. */
    val titleText: Long get() = textBright
    /** Window/chrome border — uses the [border] token. */
    val chromeBorder: Long get() = border
    /** Diff-removed line foreground — uses the [danger] token. */
    val removeText: Long get() = danger
    /** Diff-removed line background — uses the [surfaceAlt] token. */
    val removeBg: Long get() = surfaceAlt

    companion object {
        /** Fixed macOS traffic-light close dot (OS-semantic, theme-independent). */
        const val WINDOW_CLOSE_DOT: Long = 0xFFFF5F57
        /** Fixed macOS traffic-light minimise dot. */
        const val WINDOW_MIN_DOT: Long = 0xFFFEBC2E
        /** Fixed macOS traffic-light zoom dot. */
        const val WINDOW_MAX_DOT: Long = 0xFF28C840
    }
}
