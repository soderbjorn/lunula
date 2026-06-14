/**
 * Tiny HTML helpers for callers that build markup strings by hand.
 *
 * Kept intentionally minimal: just enough to let consumers safely interpolate
 * user-supplied strings into snippets like `<strong>${escapeHtml(name)}</strong>`
 * before passing them to [showConfirmDialog] with `messageIsHtml = true`.
 */
package se.soderbjorn.darkness.web

/**
 * Escapes the five HTML special characters so [s] can be safely embedded inside
 * an HTML attribute value or element body.
 *
 * Callers are typically code paths that build small markup snippets (e.g. the
 * "Delete <b>${name}</b>?" message passed to [showConfirmDialog] with
 * `messageIsHtml = true`) and need to defend against names that contain `<`,
 * `&`, quotes, etc.
 *
 * @param s the raw string to escape
 * @return the same string with `&`, `<`, `>`, `"` and `'` replaced by their
 *   HTML entity equivalents
 * @see showConfirmDialog
 */
fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
