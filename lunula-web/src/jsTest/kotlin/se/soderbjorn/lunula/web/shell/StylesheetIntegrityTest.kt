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
        // Gated by applySurfaceStyle(): Depth is the absence of
        // data-dt-surface="flat". Losing these rules would make the default
        // silently flat — a regression nothing else would notice.
        val depth = parsedSelectors().filter { it.contains("data-dt-surface=\"flat\"") }
        for (surface in listOf(".dt-pane", ".dt-pane-header", ".dt-app-frame", ".dt-sidebar-left", ".dt-tab")) {
            assertTrue(
                depth.any { it.contains(surface) },
                "Depth surface style has no rule for $surface — survived: $depth",
            )
        }
    }

    @Test
    fun theCleanShellRulesSurviveParsing() {
        // Gated by applyShellStyle(): Clean is data-dt-style="clean" on :root.
        // Every surface the Clean block restyles is listed, because losing any
        // one leaves a half-Clean shell (say, square sheet with Classic
        // titlebars) that still looks wired from Kotlin's side.
        val clean = parsedSelectors().filter { it.contains("data-dt-style=\"clean\"") }
        assertTrue(clean.isNotEmpty(), "no Clean shell rules survived parsing")
        for (surface in listOf(
            ".dt-app-frame", ".dt-app-frame-main", ".dt-pane-root", ".dt-pane", ".dt-pane-focused",
            ".dt-pane-floating", ".dt-pane-header", ".dt-pane-title", ".dt-pane-action",
            ".dt-pane-action-separator", ".dt-tab", ".dt-pane-dock", ".dt-pane-dock-item",
            ".dt-sidebar", ".dt-topbar",
        )) {
            assertTrue(
                clean.any { it.contains(surface) },
                "Clean shell style has no rule for $surface — survived: $clean",
            )
        }
    }

    @Test
    fun theCleanSidebarTreeRulesSurviveParsing() {
        // LNA-21: the sidebar tree under Clean. The selectors below are the
        // ones whose loss would quietly bring back a Classic piece (uppercase
        // headers, the Fill pill on the active row, Classic's active-row inset)
        // inside an otherwise Clean sidebar.
        val clean = parsedSelectors().filter { it.contains("data-dt-style=\"clean\"") }
        for (selector in listOf(
            ".dt-sidebar-section-header",
            ".dt-sidebar-section.active-tab > .dt-sidebar-section-header",
            ".dt-sidebar-section-chevron",
            ".dt-sidebar-section.active-tab .dt-sidebar-row",
            ".dt-sidebar-row.dt-active",
            "[data-dt-selection] .dt-sidebar-row.dt-active",
            ".dt-sidebar-row-icon",
            ".dt-sidebar-footer",
        )) {
            assertTrue(
                clean.any { it.contains(selector) },
                "Clean sidebar tree has no rule for $selector — survived: $clean",
            )
        }
    }

    @Test
    fun theCleanTopBarRulesSurviveParsing() {
        // LNA-23: the top bar under Clean. Losing any of these brings back a
        // Classic piece (30px square buttons, the accent chip on an open
        // button, the 1px hairline) inside an otherwise Clean top bar.
        val clean = parsedSelectors().filter { it.contains("data-dt-style=\"clean\"") }
        for (selector in listOf(
            ".dt-topbar-trailing",
            ".dt-topbar-icon-button",
            ".dt-topbar-icon-button > svg",
            ".dt-topbar-icon-button:hover",
            ".dt-topbar-icon-button.dt-active",
            ".dt-topbar-icon-button[aria-expanded=\"true\"]",
        )) {
            assertTrue(
                clean.any { it.contains(selector) },
                "Clean top bar has no rule for $selector — survived: $clean",
            )
        }
    }
}
