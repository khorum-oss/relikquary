package org.khorum.oss.relikquary.container.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for in-progress [BlobUpload] sessions (feature 018). */
interface BlobUploadRepository : JpaRepository<BlobUpload, String>
