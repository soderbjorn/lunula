/*
 * SelectionStyleDefaultTest.kt (jsTest)
 *
 * Pins the toolkit's default selection language to [SelectionStyle.Default]
 * (Fill) at the one seam every consumer paints through, `applySelectionStyle`.
 *
 * Worth a test because the default is expressed asymmetrically and that is easy
 * to "tidy" back into a bug: the stylesheet paints Tint in its base rules and
 * keys only the fill overrides off `data-dt-selection`, so "unset" has to be
 * resolved in Kotlin *before* the attribute is stamped. The obvious-looking
 * implementation — clear the attribute when nothing is chosen — silently paints
 * Tint no matter what the constant says.
 *
 * The three cases below are the whole contract: unset follows the default, and
 * each explicit pick is honoured whether or not it agrees with it. The Tint case
 * doubles as the guard on the asymmetry, since it is the one value that is
 * expressed by the attribute's *absence*.
 *
 * @see se.soderbjorn.lunula.web.applySelectionStyle
 * @see se.soderbjorn.lunula.core.SelectionStyle.Default
 */
package se.soderbjorn.lunula.web.shell

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.web.applySelectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A detached element to stamp, so the shared `documentElement` — which a shell
 * mounted by a sibling test may also be writing to — can't decide the result.
 *
 * @return a fresh element carrying no selection attribute.
 */
private fun target(): HTMLElement = document.createElement("div") as HTMLElement

/** The stamped attribute, or `null` when the applier cleared/never set it. */
private fun HTMLElement.selectionAttr(): String? = getAttribute("data-dt-selection")

class SelectionStyleDefaultTest {

    /** `null` means "nobody chose", and must resolve to the toolkit default. */
    @Test
    fun unsetPaintsTheDefault() {
        assertEquals(
            SelectionStyle.Fill,
            SelectionStyle.Default,
            "the toolkit default is Fill; moving it is a deliberate, app-visible choice",
        )
        val el = target()
        applySelectionStyle(el, null)
        assertEquals(
            "fill",
            el.selectionAttr(),
            "an unset selection style must paint SelectionStyle.Default",
        )
    }

    /** An explicit Fill is stamped even where it agrees with the default. */
    @Test
    fun explicitFillIsStamped() {
        val el = target()
        applySelectionStyle(el, SelectionStyle.Fill)
        assertEquals("fill", el.selectionAttr())
    }

    /**
     * An explicit Tint clears the attribute — the stylesheet's base rules are
     * the tint language, so absence *is* the value here.
     */
    @Test
    fun explicitTintClearsTheAttribute() {
        val el = target()
        applySelectionStyle(el, SelectionStyle.Fill)
        applySelectionStyle(el, SelectionStyle.Tint)
        assertEquals(null, el.selectionAttr(), "Tint is expressed by the attribute's absence")
    }
}
