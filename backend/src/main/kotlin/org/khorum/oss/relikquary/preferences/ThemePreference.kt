package org.khorum.oss.relikquary.preferences

/** Thrown when a submitted theme preference is malformed (⇒ HTTP 400). */
class InvalidThemeException(message: String) : RuntimeException(message)

/**
 * A user's web theme choice (feature 019): a named [preset] palette plus an optional custom [accent]
 * colour that overrides the preset's accent. Validated on the way in so only well-formed values are
 * persisted and later applied to the UI's `--rq-*` design tokens.
 */
data class ThemePreference(
    val preset: String,
    /** A `#rrggbb` hex colour, or null to use the preset's own accent. */
    val accent: String?,
) {
    companion object {
        /** The named presets the UI ships; kept in sync with the frontend theme store. */
        val PRESETS = setOf("vault-gold", "emerald", "crimson", "slate")
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

        /** Validates and normalizes, throwing [InvalidThemeException] on any malformed field. */
        fun of(preset: String?, accent: String?): ThemePreference {
            val chosen = preset?.trim()?.lowercase().orEmpty()
            if (chosen !in PRESETS) {
                throw InvalidThemeException("unknown theme preset: '$preset' (expected one of $PRESETS)")
            }
            val normalizedAccent = accent?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
            if (normalizedAccent != null && !HEX_COLOR.matches(normalizedAccent)) {
                throw InvalidThemeException("accent must be a #rrggbb hex colour, got: '$accent'")
            }
            return ThemePreference(chosen, normalizedAccent)
        }
    }
}
