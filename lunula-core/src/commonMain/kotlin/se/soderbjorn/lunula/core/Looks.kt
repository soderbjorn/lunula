/* Looks.kt
 * Looks — named presets of the style settings — and the one resolution ladder
 * every reader of those settings walks.
 *
 * The style settings (corner roundness, spacing, selection, surfaces, chrome,
 * windows, active window, labels) are independent, and any combination of
 * them is a valid shell. A [Look] is a convenient combination with a name:
 * picking one in Appearance writes all of its values at once, after which each
 * setting can still be changed on its own, and the Look row then reads
 * "Custom". Nothing stores which look was picked; the row shows whichever look
 * the current values match ([Look.matches]).
 *
 * [Looks.Calm] is what the "original single Calm switch" of epic LNA-16 became once its
 * parts were split into separate settings. The legacy [ShellStyle] value is
 * kept as a shorthand for these presets (see [AppearanceShape.lookLayer]), so a
 * brand manifest's `defaultStyle: clean` or a page's `?style=clean` still
 * reaches every part of it.
 */
package se.soderbjorn.lunula.core

/**
 * A named combination of style settings.
 *
 * @property id          stable identifier, also the `data-dt-look` hook for tests.
 * @property name        the label on the Look row.
 * @property description one line for the row's hint.
 * @property shape       the values the look writes. Every per-mode field is set
 *   (navigation and the legacy shorthand never are), so applying a look
 *   replaces the whole style and matching is exact.
 * @see Looks
 */
data class Look(
    val id: String,
    val name: String,
    val description: String,
    val shape: AppearanceShape,
) {
    /**
     * Whether [resolved] is exactly this look: every field equal, with a corner
     * radius of `null` read as the stylesheet's default.
     *
     * Called by the Appearance settings to ring the look the current values
     * form, and by the web shell to decide the legacy `data-dt-style` stamp.
     *
     * @param resolved the style in effect.
     * @return `true` when nothing differs.
     */
    fun matches(resolved: ResolvedAppearance): Boolean =
        resolveAppearance(shape) == resolved.copy(cornerRadiusPx = resolved.cornerRadiusPx ?: DEFAULT_CORNER_RADIUS_PX)
}

/**
 * The corner radius the stylesheet paints when none is set (`--dt-corner-radius`'s
 * fallback in `lunula.css`). Read by [Look.matches] and [resolveAppearance], so a
 * look that states 18px matches a shell that states nothing.
 */
const val DEFAULT_CORNER_RADIUS_PX: Int = 18

/**
 * The built-in looks, in the order the Look row offers them.
 *
 * @see Look
 */
object Looks {
    /** The shell as it has always been drawn: every setting at its default. */
    val Classic: Look = Look(
        id = "classic",
        name = "Classic",
        description = "The original shell: cards on a canvas, accent title bars, uppercase labels.",
        shape = AppearanceShape(
            cornerRadiusPx = DEFAULT_CORNER_RADIUS_PX,
            uiDensity = UiDensity.Compact,
            selectionStyle = SelectionStyle.Default,
            surfaceStyle = SurfaceStyle.Default,
            chromeStyle = ChromeStyle.Default,
            windowLayout = WindowLayout.Default,
            focusStyle = FocusStyle.Default,
            labelCase = LabelCase.Default,
        ),
    )

    /**
     * The calm shell (epic LNA-16, first called "Clean"): one ground, one
     * rounded sheet of hairline-split windows, the active window shown by
     * tone, soft selection and surfaces, sentence-case labels, 14px corners and
     * Comfortable spacing. Designed for the Calm themes, though it works with
     * any.
     */
    val Calm: Look = Look(
        id = "calm",
        name = "Calm",
        description = "One sheet of windows on a quiet ground, the active window shown by tone. Pairs well with the Calm themes.",
        shape = AppearanceShape(
            cornerRadiusPx = 14,
            uiDensity = UiDensity.Comfortable,
            selectionStyle = SelectionStyle.Soft,
            surfaceStyle = SurfaceStyle.Soft,
            chromeStyle = ChromeStyle.Blended,
            windowLayout = WindowLayout.Sheet,
            focusStyle = FocusStyle.Tone,
            labelCase = LabelCase.Sentence,
        ),
    )

    /** Every built-in look, in Look-row order. */
    val all: List<Look> = listOf(Classic, Calm)

    /**
     * The look a legacy [ShellStyle] value stands for.
     *
     * Called by [AppearanceShape.lookLayer].
     *
     * @param style the legacy shorthand.
     * @return [Calm] for [ShellStyle.Calm], otherwise [Classic].
     */
    fun forShellStyle(style: ShellStyle): Look = when (style) {
        ShellStyle.Classic -> Classic
        ShellStyle.Calm -> Calm
    }
}

/**
 * Every style setting with its value decided: what the shell actually paints in
 * one appearance mode.
 *
 * Produced by [resolveAppearance]. [cornerRadiusPx] stays nullable because
 * `null` is a real instruction to the web layer (clear the override, let the
 * stylesheet's default apply); [Look.matches] reads it as
 * [DEFAULT_CORNER_RADIUS_PX].
 */
data class ResolvedAppearance(
    val cornerRadiusPx: Int?,
    val uiDensity: UiDensity,
    val selectionStyle: SelectionStyle,
    val surfaceStyle: SurfaceStyle,
    val chromeStyle: ChromeStyle,
    val windowLayout: WindowLayout,
    val focusStyle: FocusStyle,
    val labelCase: LabelCase,
) {
    /**
     * These values as a fully-populated shape, for seeding a mode the user has
     * not edited yet: the Appearance settings start an edit from what is
     * painted, so changing one setting leaves the others where they were.
     *
     * @return a shape with every per-mode field set.
     */
    fun toShape(): AppearanceShape = AppearanceShape(
        cornerRadiusPx = cornerRadiusPx,
        uiDensity = uiDensity,
        selectionStyle = selectionStyle,
        surfaceStyle = surfaceStyle,
        chromeStyle = chromeStyle,
        windowLayout = windowLayout,
        focusStyle = focusStyle,
        labelCase = labelCase,
    )
}

/**
 * Resolves the style settings through [layers], highest precedence first: each
 * field takes the first non-null value, else the toolkit default.
 *
 * Callers build the ladder as user → user's legacy shorthand → app/brand
 * default → app's legacy shorthand (see [appearanceLadder]), so a field the
 * user never touched shows the brand's choice and a field the brand never
 * named shows the toolkit's.
 *
 * @param layers the shapes to consult, highest precedence first.
 * @return the decided values.
 * @see appearanceLadder
 */
fun resolveAppearance(vararg layers: AppearanceShape): ResolvedAppearance {
    fun <T> pick(get: (AppearanceShape) -> T?): T? = layers.firstNotNullOfOrNull(get)
    return ResolvedAppearance(
        cornerRadiusPx = pick { it.cornerRadiusPx },
        uiDensity = pick { it.uiDensity } ?: UiDensity.Compact,
        selectionStyle = pick { it.selectionStyle } ?: SelectionStyle.Default,
        surfaceStyle = pick { it.surfaceStyle } ?: SurfaceStyle.Default,
        chromeStyle = pick { it.chromeStyle } ?: ChromeStyle.Default,
        windowLayout = pick { it.windowLayout } ?: WindowLayout.Default,
        focusStyle = pick { it.focusStyle } ?: FocusStyle.Default,
        labelCase = pick { it.labelCase } ?: LabelCase.Default,
    )
}

/**
 * The precedence ladder for one appearance mode: the user's own values, the
 * look their legacy shorthand names, the app/brand default, then the look the
 * app's legacy shorthand names.
 *
 * Called by the web shell's apply pass and by the Appearance settings, so both
 * agree on what is painted.
 *
 * @param user       the user's values for the active mode.
 * @param appDefault the app's (or brand's) default shape, shared by both modes.
 * @return the layers to hand to [resolveAppearance].
 */
fun appearanceLadder(user: AppearanceShape, appDefault: AppearanceShape): Array<AppearanceShape> =
    arrayOf(user, user.lookLayer(), appDefault, appDefault.lookLayer())
