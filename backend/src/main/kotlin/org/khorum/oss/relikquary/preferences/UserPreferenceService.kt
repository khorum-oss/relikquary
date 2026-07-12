package org.khorum.oss.relikquary.preferences

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.persistence.UserPreference
import org.khorum.oss.relikquary.persistence.UserPreferenceRepository
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Reads and writes a user's persisted UI theme (feature 019). The theme is stored as a compact JSON
 * string in the `user_preference` row keyed by username; a row that fails to parse (e.g. hand-edited or
 * written by a newer client) is treated as "no preference" rather than surfaced as an error, so the UI
 * simply falls back to its default.
 */
@Service
class UserPreferenceService(private val preferences: UserPreferenceRepository) {

    // Kotlin module: ThemePreference is a Kotlin data class, so deserialization needs constructor binding.
    private val objectMapper = ObjectMapper().registerKotlinModule()

    /** The user's saved theme, or null if none is stored (or the stored value is unreadable). */
    fun getTheme(username: String): ThemePreference? {
        val row = preferences.findById(username).orElse(null) ?: return null
        return runCatching { objectMapper.readValue(row.theme, ThemePreference::class.java) }
            .onFailure { logger.warn { "Discarding unreadable theme for '$username': ${it.message}" } }
            .getOrNull()
    }

    /** Upserts the (already-validated) [theme] for [username] and returns it. */
    fun saveTheme(username: String, theme: ThemePreference): ThemePreference {
        val row = preferences.findById(username).orElseGet { UserPreference().apply { this.username = username } }
        row.theme = objectMapper.writeValueAsString(theme)
        row.updatedAt = Instant.now()
        preferences.save(row)
        return theme
    }
}
