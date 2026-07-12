package org.khorum.oss.relikquary.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [UserPreference] rows, keyed by username (feature 019). */
interface UserPreferenceRepository : JpaRepository<UserPreference, String>
