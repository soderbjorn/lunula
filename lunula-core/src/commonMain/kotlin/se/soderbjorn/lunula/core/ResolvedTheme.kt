/* ResolvedTheme.kt
 * The flat, ready-to-render form of a [Theme]: the 32 semantic tokens as
 * ARGB [Long]s, plus a handful of structural aliases (cursor, selection,
 * titlebar …) that map directly onto existing tokens — by direct reference,
 * never by colour maths. Every renderer (web CSS vars, Android Compose, iOS
 * SwiftUI) consumes this single struct.
 *
 * The 9 chrome/canvas tokens are NOT nullable here even though they are
 * optional on [Theme]: [Theme.resolve] has already applied each one's
 * fallback, so a renderer reads a concrete colour and never repeats the
 * fallback logic.
 */
package se.soderbjorn.lunula.core

/**
 * A theme resolved to ARGB [Long] values (`0xAARRGGBB`).
 *
 * Produced by [Theme.resolve]. The 28 stored tokens plus the 4 derived
 * translucent ones (accentSoft / glow / addBg / chromeAccentSoft) are exposed
 * directly; the extra UI needs that aren't their own token (cursor, selection)
 * are exposed as aliases that reference one of the tokens — a structural
 * assignment, not a derived colour. The syntax palette has 8 dedicated slots.
 * Window traffic-light dots are OS-semantic fixed colours, not theme tokens.
 *
 * Renderers should paint the title bar, tab bar and sidebar from the `chrome*`
 * tokens, the area behind and between panes from [canvas], and pane content
 * from [bg]/[surface]/… — the three zones a split theme separates.
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
    /** Area behind and between the panes. Falls back to [bg] in [Theme.resolve]. */
    val canvas: Long,
    /** Title bar + tab bar + sidebar fill. Falls back to [bg]. */
    val chromeBg: Long,
    /** Sidebar rows and chrome body text. Falls back to [text]. */
    val chromeText: Long,
    /** Chrome icons, counts and metadata. Falls back to [textDim]. */
    val chromeTextDim: Long,
    /** App name, active tab, active sidebar item. Falls back to [textBright]. */
    val chromeTextBright: Long,
    /** Chrome dividers and frame. Falls back to [border]. */
    val chromeBorder: Long,
    /** Chrome section headers and active accent. Falls back to [accent]. */
    val chromeAccent: Long,
    /** Active-item tint in the chrome — [chromeAccent] at 16%. */
    val chromeAccentSoft: Long,
    /** Sidebar usage-meter track. Falls back to [surfaceAlt]. */
    val chromeTrack: Long,
) {
    // ----- Structural aliases (direct token references; no colour maths) -----

    /** Terminal cursor — uses the [accent] token. */
    val cursor: Long get() = accent
    /** Terminal selection background — uses the [accentSoft] token. */
    val selectionBg: Long get() = accentSoft
    /** Terminal selection text — uses the [bg] token. */
    val selectionText: Long get() = bg
    /** Window title-bar fill — uses the [chromeBg] token. */
    val titlebar: Long get() = chromeBg
    /** Window title text — uses the [chromeTextBright] token. */
    val titleText: Long get() = chromeTextBright
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
