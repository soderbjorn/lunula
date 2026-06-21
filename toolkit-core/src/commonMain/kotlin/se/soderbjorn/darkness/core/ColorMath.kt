/**
 * Pure colour *format* utilities operating on ARGB [Long] values.
 *
 * Every colour is encoded as `0xAARRGGBB` where each channel occupies one
 * byte. These helpers convert between hex-string and ARGB representations and
 * classify a colour as light/dark by relative luminance.
 *
 * The old colour *calculator* (channel mixing, alpha application, WCAG
 * contrast floors, palette derivation from a seed) has been removed along with
 * the seed-based theme system: themes now define every token explicitly, so no
 * derivation is performed at render time.
 *
 * @see Theme
 * @see ResolvedTheme
 */
package se.soderbjorn.darkness.core

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
