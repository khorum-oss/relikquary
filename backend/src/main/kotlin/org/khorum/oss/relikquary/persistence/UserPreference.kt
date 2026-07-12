package org.khorum.oss.relikquary.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A single authenticated user's UI preferences (feature 019). Currently just the web theme — a named
 * preset plus an optional custom accent colour — stored as a small JSON string in [theme]. Keyed by
 * [username] (not a FK to [ManagedUser]) so it applies to config-defined users too. Written JavaBean-style
 * (no-arg constructor + mutable properties) like the other persisted entities, so no Kotlin JPA plugin is
 * needed.
 */
@Entity
@Table(name = "user_preference")
class UserPreference {

    @Id
    @Column(name = "username", length = NAME_LENGTH)
    var username: String = ""

    /** The theme choice as a compact JSON object, e.g. `{"preset":"vault-gold","accent":"#c9a227"}`. */
    @Column(name = "theme", length = THEME_LENGTH)
    var theme: String = ""

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH

    private companion object {
        const val NAME_LENGTH = 200
        const val THEME_LENGTH = 2000
    }
}
