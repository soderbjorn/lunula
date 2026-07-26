/**
 * Pure colour *format* utilities operating on ARGB [Long] values.
 *
 * Every colour is encoded as `0xAARRGGBB` where each channel occupies one
 * byte. These helpers convert between hex-string and ARGB representations and
 * classify a colour as light/dark by relative luminance.
 *
 * The old colour *calculator* (palette derivation from a seed, hue rotation,
 * automatic contrast repair) is gone along with the seed-based theme system:
 * themes define every token explicitly, so no palette is invented at render
 * time.
 *
 * What remains here is deliberately narrow — the handful of operations the
 * token model itself is expressed in:
 *  - [withAlpha] for the four translucent tokens (accentSoft / glow / addBg /
 *    chromeAccentSoft), which the design states as "this colour at N%";
 *  - [contrastRatio] and [onColorFor], which serve the on-fill token pairs and
 *    the contrast lint that guards them.
 *
 * None of these invent a hue. Each one answers a question the theme author has
 * already framed ("this colour, dimmed against that backdrop"), which is why
 * they belong here and a seed calculator did not.
 *
 * @see Theme
 * @see ResolvedTheme
 */
package se.soderbjorn.lunula.core

/**
 * Parses a CSS hex colour string into an opaque ARGB [Long].
 *
 * Accepts `#RRGGBB` (6-digit) and `#RGB` (3-digit shorthand). The returned
 * value always has full alpha (`0xFF`).
 *
 * @param hex the hex colour string, e.g. `"#00ff9f"` or `"#0f9"`
 * @return the colour as `0xFFRRGGBB`
 */
fun hexToArgb(hex: String): Long {
    val h = hex.removePrefix("#")
    val expanded = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        else -> "000000"
    }
    return 0xFF000000L or expanded.lowercase().toLong(16)
}

/**
 * Formats an ARGB [Long] as a 7-character CSS hex string (`#rrggbb`).
 *
 * Alpha is silently dropped. Use [argbToCss] when alpha matters.
 *
 * @param argb the colour value (e.g. `0xFF00FF9F`)
 * @return the hex string, e.g. `"#00ff9f"`
 */
fun argbToHex(argb: Long): String {
    val r = ((argb shr 16) and 0xFF).toString(16).padStart(2, '0')
    val g = ((argb shr 8) and 0xFF).toString(16).padStart(2, '0')
    val b = (argb and 0xFF).toString(16).padStart(2, '0')
    return "#$r$g$b"
}

/**
 * Formats an ARGB [Long] as a CSS colour string, choosing the shortest
 * representation that preserves the colour.
 *
 * If the alpha channel is fully opaque (`0xFF`), the result is a 7-character
 * hex string (`#rrggbb`). Otherwise it is an `rgba(r,g,b,a)` string with the
 * alpha expressed as a decimal fraction.
 *
 * @param argb the colour value
 * @return a CSS-compatible colour string
 */
fun argbToCss(argb: Long): String {
    val a = ((argb shr 24) and 0xFF).toInt()
    if (a == 0xFF) return argbToHex(argb)
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val af = (a / 255.0 * 100).toInt() / 100.0
    return "rgba($r,$g,$b,$af)"
}

/**
 * Computes the relative luminance of an ARGB colour per the sRGB transfer
 * function. Alpha is ignored.
 *
 * @param color the ARGB colour value
 * @return luminance in `[0.0, 1.0]` where 0 is black and 1 is white
 */
fun luminance(color: Long): Double {
    fun lin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92
        else kotlin.math.exp(2.4 * kotlin.math.ln((s + 0.055) / 1.055))
    }
    val r = lin(((color shr 16) and 0xFF).toInt())
    val g = lin(((color shr 8) and 0xFF).toInt())
    val b = lin((color and 0xFF).toInt())
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Returns `true` if [color] is perceptually light (luminance > 0.5).
 *
 * @param color the ARGB colour value
 * @return whether the colour is light
 */
fun isColorLight(color: Long): Boolean = luminance(color) > 0.5

/**
 * Returns [color] with its alpha channel set to [alpha] (0.0..1.0), leaving the
 * RGB channels untouched. This is a single-channel transparency set, NOT a
 * colour mix — used to express the design's translucent tokens (e.g. the
 * accent-soft / glow / add-wash, which are the accent/add colour at a fixed
 * opacity). [argbToCss] renders the result as `rgba(...)`.
 *
 * @param color the source ARGB colour.
 * @param alpha the desired opacity, 0.0 (transparent) … 1.0 (opaque).
 * @return the colour with the new alpha channel.
 */
fun withAlpha(color: Long, alpha: Double): Long {
    val a = (alpha * 255).toLong().coerceIn(0, 255)
    return (a shl 24) or (color and 0x00FFFFFFL)
}

/**
 * The WCAG 2.x contrast ratio between two opaque colours, in `[1.0, 21.0]`.
 *
 * Order-independent. Alpha is ignored, so callers must pass colours that have
 * already been composited onto their backdrop.
 *
 * Consumed by the theme contrast lint in `commonTest`, which asserts that every
 * on-fill foreground clears the AA floor of 4.5:1 against the fill it is
 * declared for.
 *
 * @param a one ARGB colour.
 * @param b the other ARGB colour.
 * @return the contrast ratio, where 1.0 is identical and 21.0 is black/white.
 * @see luminance
 */
fun contrastRatio(a: Long, b: Long): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = if (la > lb) la else lb
    val lo = if (la > lb) lb else la
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * Picks the legible foreground for a solid [fill]: whichever of black or white
 * contrasts with it more.
 *
 * This is the fallback for the `…On` tokens — a theme that never declares one
 * still gets a readable label if a consumer paints its accent as a field. It is
 * a *legibility floor*, not a design choice: a theme that cares declares the
 * exact value, and an explicit token always wins (see [Theme.effectiveAccentOn]).
 *
 * Note this is NOT [isColorLight]. That asks "does this read as a light
 * colour?", which crosses over at luminance 0.5; this asks "which extreme is
 * further away?", which crosses over at ~0.179. The gap between the two is
 * wide and populated — a mid-bright cyan like `#4dc8f5` sits at 0.49, so the
 * lightness question answers "dark, use white" and lands on 1.9:1, while the
 * contrast question answers "black" and lands on 10.9:1.
 *
 * Because the two candidates are the extremes of the scale, the worst any fill
 * can do here is ~4.58:1 — at the crossover, where both are equally far. So
 * this fallback clears the AA floor for *every* possible fill, which is what
 * lets the contrast lint assert the on-fill pairing across all built-ins
 * instead of only the ones that opted in.
 *
 * @param fill the ARGB colour of the field the type sits on.
 * @return `#000000` or `#ffffff` as an ARGB [Long].
 * @see Theme.accentOn
 * @see contrastRatio
 */
fun onColorFor(fill: Long): Long {
    val black = 0xFF000000L
    val white = 0xFFFFFFFFL
    return if (contrastRatio(black, fill) >= contrastRatio(white, fill)) black else white
}
