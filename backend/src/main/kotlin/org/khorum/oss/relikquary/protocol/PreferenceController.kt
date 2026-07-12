package org.khorum.oss.relikquary.protocol

import org.khorum.oss.relikquary.preferences.InvalidThemeException
import org.khorum.oss.relikquary.preferences.ThemePreference
import org.khorum.oss.relikquary.preferences.UserPreferenceService
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The current user's theme, `null` when they have not chosen one (feature 019). */
data class ThemeResponse(val theme: ThemePreference?)

/** Incoming theme choice; validated into a [ThemePreference] before persisting (feature 019). */
data class ThemeRequest(val preset: String?, val accent: String?)

/**
 * The signed-in user's own preferences (feature 019) — currently just the web theme. Scoped to the
 * authenticated principal (resolved from the security context, exactly as tokens record their owner), so
 * it needs no id in the path and can never read or write another user's choice. Distinct from the admin
 * surface under `/api/admin`: any authenticated user may manage their own theme, no PUBLISH role required.
 */
@RestController
@RequestMapping("/api/me")
class PreferenceController(private val preferences: UserPreferenceService) {

    @GetMapping("/preferences")
    fun get(): ThemeResponse = ThemeResponse(preferences.getTheme(currentUsername()))

    @PutMapping("/preferences")
    fun put(@RequestBody body: ThemeRequest): ThemeResponse {
        val theme = ThemePreference.of(body.preset, body.accent)
        return ThemeResponse(preferences.saveTheme(currentUsername(), theme))
    }

    /** The authenticated principal's name, or `401` when the request is anonymous. */
    private fun currentUsername(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || isAnonymous(authentication)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required")
        }
        return authentication.name?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required")
    }

    private fun isAnonymous(authentication: org.springframework.security.core.Authentication): Boolean =
        authentication.authorities.any { it.authority == "ROLE_ANONYMOUS" }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidThemeException::class)
    fun handleInvalidTheme(e: InvalidThemeException): Map<String, String?> = mapOf("error" to e.message)
}
