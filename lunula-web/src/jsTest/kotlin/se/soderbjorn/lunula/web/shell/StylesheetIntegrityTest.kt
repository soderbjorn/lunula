/* StylesheetIntegrityTest.kt
 * Structural sanity for the shipped `lunula.css`, checked through the browser's
 * own parser after `injectLunulaStyles()`.
 *
 * This exists because of a real failure, and the failure mode is the point: an
 * edit left a paragraph of prose *after* a comment's closing marker, followed
 * by a stray `*​/`. Everything still compiled, every Kotlin test still passed,
 * and the stylesheet still loaded — the parser simply discarded the two rules
 * that followed, so an entire user setting silently did nothing. Nothing in the
 * build could see it, because nothing in the build reads the CSS.
 *
 * So these assertions do not check *values* (that is the designer's business
 * and it changes) — they check that rules the toolkit's Kotlin depends on
 * actually survived parsing. Every selector named here is one that some
 * `apply*` function writes an attribute for: if the rule is gone, the setting
 * is inert.
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.css.CSSStyleSheet
import se.soderbjorn.lunula.web.injectLunulaStyles
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every rule the parser accepted, as selector text. */
private fun parsedSelectors(): List<String> {
    injectLunulaStyles()
    val sheets = document.styleSheets
    val out = mutableListOf<String>()
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
        for (j in 0 until rules.length) {
            val text = rules.item(j)?.cssText ?: continue
            out += text.substringBefore('{').trim()
        }
    }
    return out
}

class StylesheetIntegrityTest {

    @Test
    fun theDensityRulesSurviveParsing() {
        // Written by applyUiDensity(); without them the Spacing setting is a
        // no-op that still looks wired from Kotlin's side.
        val selectors = parsedSelectors()
        for (density in listOf("comfortable", "spacious")) {
            assertTrue(
                selectors.any { it.contains("data-dt-density=\"$density\"") },
                "no rule for density '$density' survived parsing — check for an " +
                    "unterminated or doubly-terminated comment above it",
            )
        }
    }

    @Test
    fun theFillSelectionRulesSurviveParsing() {
        // Written by applySelectionStyle(). The three surfaces below are the
        // whole of what Fill means; losing any one leaves a half-applied
        // selection language, which is worse than not offering it.
        val fill = parsedSelectors().filter { it.contains("data-dt-selection=\"fill\"") }
        assertTrue(fill.isNotEmpty(), "no Fill selection rules survived parsing")
        for (surface in listOf(".dt-pane-header", ".dt-tab", ".dt-sidebar-row")) {
            assertTrue(
                fill.any { it.contains(surface) },
                "Fill selection has no rule for $surface — survived: $fill",
            )
        }
    }

    @Test
    fun everyFilledSurfaceDeclaresItsForeground() {
        // A filled surface MUST set `color`, not just `background`. Consumers
        // paint themselves from `currentColor` precisely so they don't have to
        // know which surface they landed on — lunamux's `.tt-status-dot` is one,
        // and when the pane header set only a background the dot kept the
        // content-zone foreground and all but disappeared on the accent field.
        //
        // Asserting on the rule text rather than on a rendered pixel keeps this
        // honest for a toolkit that cannot see its consumers: any future filled
        // surface that forgets its foreground fails here, before some app's
        // glyph quietly vanishes on it.
        injectLunulaStyles()
        val sheets = document.styleSheets
        val filled = mutableListOf<String>()
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
            val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
            for (j in 0 until rules.length) {
                val text = rules.item(j)?.cssText ?: continue
                if (!text.contains("data-dt-selection=\"fill\"")) continue
                // Only rules that paint a fill are in scope; the ones that
                // suppress outlines or veil a hover have no foreground to set.
                val body = text.substringAfter('{')
                val paintsAccentFill = body.contains("background: var(--t-accent") ||
                    body.contains("background: var(--t-chrome-accent")
                if (paintsAccentFill) filled += text
            }
        }
        assertTrue(filled.isNotEmpty(), "no accent-filled rules found — did the Fill rules parse?")
        for (rule in filled) {
            assertTrue(
                rule.substringAfter('{').contains("color:"),
                "a filled surface sets no foreground, so anything painting from " +
                    "currentColor will vanish on it: $rule",
            )
        }
    }

    @Test
    fun theCornerRadiusTokensAreDerivedFromTheUserSetting() {
        // applyCornerRadiusPx() writes `--dt-corner-radius` on :root, which is
        // useless unless `.dt-app-frame` actually reads it.
        injectLunulaStyles()
        val sheets = document.styleSheets
        var frameRule: String? = null
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
            val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
            for (j in 0 until rules.length) {
                val text = rules.item(j)?.cssText ?: continue
                if (text.substringBefore('{').trim() == ".dt-app-frame") frameRule = text
            }
        }
        assertTrue(frameRule != null, ".dt-app-frame rule did not survive parsing")
        assertTrue(
            frameRule.contains("--dt-corner-radius"),
            ".dt-app-frame must derive its radii from --dt-corner-radius",
        )
    }

    @Test
    fun theDepthSurfaceRulesSurviveParsing() {
        // Gated by applySurfaceStyle(): Depth is the ABSENCE of
        // data-dt-surface (Flat and Soft both stamp it). Losing these rules
        // would make the default silently flat — a regression nothing else
        // would notice.
        val depth = parsedSelectors().filter { it.contains(":not([data-dt-surface])") }
        for (surface in listOf(".dt-pane", ".dt-pane-header", ".dt-app-frame", ".dt-sidebar-left", ".dt-tab")) {
            assertTrue(
                depth.any { it.contains(surface) },
                "Depth surface style has no rule for $surface — survived: $depth",
            )
        }
        // And none is still gated the old way, which Soft would not switch off.
        val stale = parsedSelectors().filter { it.contains(":not([data-dt-surface=\"flat\"])") }
        assertTrue(stale.isEmpty(), "Depth rules still gated on Flat alone (Soft would keep them): $stale")
    }

    @Test
    fun noRuleReadsTheLegacyStyleStamp() {
        // The Calm look is drawn by the individual settings now, and the
        // legacy data-dt-style stamp is no longer written at all.
        val legacy = parsedSelectors().filter { it.contains("data-dt-style") }
        assertTrue(legacy.isEmpty(), "lunula.css still keys rules off data-dt-style: $legacy")
    }

    /**
     * Asserts that some parsed rule gated on [attr] mentions each of
     * [selectors]. Reads whole rule texts, so rules nested in an `@media`
     * block count too.
     *
     * @param setting   the setting's name, for failure messages.
     * @param attr      the attribute selector the setting's rules carry.
     * @param selectors the selectors that must each appear in one of them.
     */
    private fun assertRulesFor(setting: String, attr: String, selectors: List<String>) {
        val gated = parsedRuleTexts().filter { it.contains(attr) }
        assertTrue(gated.isNotEmpty(), "no $setting rules survived parsing")
        for (selector in selectors) {
            assertTrue(
                gated.any { it.contains(selector) },
                "$setting has no rule for $selector — survived: ${gated.map { it.substringBefore('{').trim() }}",
            )
        }
    }

    @Test
    fun theSheetWindowRulesSurviveParsing() {
        // applyWindowLayout(Sheet). Losing any one leaves a half sheet (say,
        // a square sheet with rounded cards inside it).
        assertRulesFor("Windows: Sheet", "data-dt-windows=\"sheet\"", listOf(
            ".dt-app-frame", ".dt-app-frame-main", ".dt-pane-root", ".dt-pane", ".dt-pane-floating",
            ".dt-pane-focused", ".dt-maximized", ".dt-pane-dock", ".dt-pane-dock-item",
        ))
    }

    @Test
    fun theBlendedChromeRulesSurviveParsing() {
        // applyChromeStyle(Blended): the ground, the chrome remap, the tabs,
        // the window titlebar's geometry and its ghost buttons.
        assertRulesFor("Chrome: Blended", "data-dt-chrome=\"blended\"", listOf(
            ".dt-app-frame", ".dt-app-frame-main", ".dt-topbar", ".dt-bottombar", ".dt-sidebar", ".dt-tabbar",
            ".dt-tab", ".dt-pane-header", ".dt-pane-action", ".dt-pane-action-separator",
        ))
    }

    @Test
    fun theBlendedSidebarTreeRulesSurviveParsing() {
        // LNA-21: the sidebar tree under Blended. The selectors below are the
        // ones whose loss would quietly bring back a themed piece (dim header
        // colours, Classic's active-row inset) inside an otherwise blended
        // sidebar.
        assertRulesFor("Blended sidebar tree", "data-dt-chrome=\"blended\"", listOf(
            ".dt-sidebar-section-header",
            ".dt-sidebar-section.active-tab > .dt-sidebar-section-header",
            ".dt-sidebar-section-chevron",
            ".dt-sidebar-section.active-tab .dt-sidebar-row",
            ".dt-sidebar-row.dt-active",
            ".dt-sidebar-row:not(.dt-active)",
            ".dt-sidebar-row-icon",
            ".dt-sidebar-footer",
        ))
    }

    @Test
    fun theBlendedTopBarRulesSurviveParsing() {
        // LNA-23: the top bar under Blended. Losing any of these brings back a
        // themed piece (30px square buttons, the accent chip on an open
        // button, the 1px hairline) inside an otherwise blended top bar.
        assertRulesFor("Blended top bar", "data-dt-chrome=\"blended\"", listOf(
            ".dt-topbar-trailing",
            ".dt-topbar-icon-button",
            ".dt-topbar-icon-button > svg",
            ".dt-topbar-icon-button:hover",
            ".dt-topbar-icon-button.dt-active",
            ".dt-topbar-icon-button[aria-expanded=\"true\"]",
        ))
    }

    @Test
    fun theToneFocusRulesSurviveParsing() {
        // applyFocusStyle(Tone): the fills by state, the ring, the content
        // slot, the titlebar and the out-of-flow action strip.
        assertRulesFor("Active window: Tone", "data-dt-focus=\"tone\"", listOf(
            ".dt-pane", ".dt-pane.dt-pane-focused", ".dt-maximized", ".dt-pane-floating::after",
            ".dt-pane-content", ".dt-pane-header", ".dt-pane-title", ".dt-pane-header-icon",
            ".dt-pane-action", ".dt-pane-header > .dt-pane-actions",
        ))
    }

    @Test
    fun theSentenceLabelRulesSurviveParsing() {
        assertRulesFor("Labels: Sentence", "data-dt-labels=\"sentence\"", listOf(
            ".dt-app-frame", ".dt-pane-title-input", ".dt-sidebar-section-header", ".dt-sidebar-header",
        ))
    }

    @Test
    fun theSoftSelectionRulesSurviveParsing() {
        assertRulesFor("Selection: Soft", "data-dt-selection=\"soft\"", listOf(
            ".dt-tab.dt-selected", ".dt-sidebar-row.dt-active", ".dt-pane.dt-pane-focused .dt-pane-header",
            ".dt-pane-title",
        ))
    }

    @Test
    fun theSoftSurfaceRulesSurviveParsing() {
        assertRulesFor("Surfaces: Soft", "data-dt-surface=\"soft\"", listOf(
            ".dt-modal", ".pane-modal", ".dt-name-prompt", ".dt-hotkeys-panel",
            ".dt-hover-menu", ".dt-pane-menu", ".dt-popover-menu", ".dt-scheme-menu", ".theme-manager-menu",
        ))
    }
}

/** Every top-level rule the parser accepted, as its whole text (an `@media` block with its nested rules). */
private fun parsedRuleTexts(): List<String> {
    injectLunulaStyles()
    val sheets = document.styleSheets
    val out = mutableListOf<String>()
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
        for (j in 0 until rules.length) out += rules.item(j)?.cssText ?: continue
    }
    return out
}
