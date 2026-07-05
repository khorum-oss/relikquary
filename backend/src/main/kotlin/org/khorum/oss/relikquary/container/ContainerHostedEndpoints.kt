package org.khorum.oss.relikquary.container

import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.observability.metrics.RepositoryMetrics
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * HTTP handling for a HOSTED container repository (feature 018, US2/US3): `docker push` (blob uploads +
 * manifest PUT) and `docker pull` (manifest/blob/tags GET), plus manifest DELETE. Orchestrates
 * [BlobUploadService], [ManifestService], and [TagService]; builds the wire responses via
 * [OciResponses]/[OciPaths]; and records publish/resolve outcomes on the shared [RepositoryMetrics]
 * (feature 010) so container traffic shows up in the same meters as Maven. The controller delegates its
 * HOSTED branches here.
 */
@Component
class ContainerHostedEndpoints(
    private val manifests: ManifestService,
    private val tags: TagService,
    private val blobUploads: BlobUploadService,
    private val storage: ContainerStorage,
    private val metrics: RepositoryMetrics,
) {

    /** GET/HEAD dispatch for a hosted repo. */
    fun read(repo: RepositoryProperties.Repo, ref: ImageReference): ResponseEntity<*> = when (ref.operation) {
        ContainerOperation.MANIFEST -> {
            val outcome = manifests.get(repo.name, ref.imageName, ref.reference)
            metrics.recordResolve(repo.name, if (outcome is ManifestOutcome.Found) "hit" else "miss")
            OciResponses.manifest(outcome)
        }
        ContainerOperation.BLOB -> blobResponse(repo.name, Digest.parse(ref.reference))
        ContainerOperation.TAGS_LIST ->
            OciResponses.tags(TagsOutcome.Found(OciPaths.tagsJson(ref.imageName, tags.list(repo.name, ref.imageName))))
        ContainerOperation.BLOB_UPLOAD -> uploadStatus(repo, ref)
    }

    /** POST/PATCH/PUT/DELETE dispatch for a hosted repo. */
    fun write(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> =
        when (ref.operation) {
            ContainerOperation.BLOB_UPLOAD -> upload(repo, ref, request)
            ContainerOperation.MANIFEST -> manifest(repo, ref, request)
            else -> OciResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED", "operation not supported")
        }

    private fun upload(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> =
        when (request.method.uppercase()) {
            "POST" -> startUpload(repo, ref, request)
            "PATCH" -> patchUpload(repo, ref, request)
            "PUT" -> finishUpload(repo, ref, request)
            else -> OciResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED", "unsupported upload method")
        }

    private fun manifest(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> =
        when (request.method.uppercase()) {
            "PUT" -> {
                val mediaType = request.contentType ?: OCI_MANIFEST_TYPE
                val digest = manifests.put(repo.name, ref.imageName, ref.reference, request.inputStream.readBytes(), mediaType)
                metrics.recordPublish(repo.name, "accepted")
                OciResponses.manifestCreated(OciPaths.manifestLocation(repo.name, ref.imageName, digest), digest)
            }
            "DELETE" ->
                if (manifests.delete(repo.name, ref.imageName, ref.reference)) OciResponses.accepted()
                else OciResponses.error(HttpStatus.NOT_FOUND, "MANIFEST_UNKNOWN", "manifest unknown")
            else -> OciResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED", "unsupported manifest method")
        }

    private fun startUpload(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> {
        request.getParameter("digest")?.let { param ->
            // Monolithic POST: the whole blob is in the body, verified against ?digest= immediately.
            val digest = Digest.parse(param)
            storage.writeBlobVerified(repo.name, digest, request.inputStream)
            metrics.recordPublish(repo.name, "accepted")
            return OciResponses.blobCreated(OciPaths.blobLocation(repo.name, ref.imageName, digest), digest)
        }
        val mount = request.getParameter("mount")
        if (mount != null && Digest.isDigest(mount) && storage.hasBlob(repo.name, Digest.parse(mount))) {
            val digest = Digest.parse(mount)
            return OciResponses.blobCreated(OciPaths.blobLocation(repo.name, ref.imageName, digest), digest)
        }
        val uuid = blobUploads.start(repo.name, ref.imageName)
        return OciResponses.uploadStarted(OciPaths.uploadLocation(repo.name, ref.imageName, uuid), uuid)
    }

    private fun patchUpload(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> {
        val row = blobUploads.session(ref.reference) ?: return uploadUnknown()
        val received = blobUploads.append(row, request.inputStream)
        val location = OciPaths.uploadLocation(repo.name, ref.imageName, ref.reference)
        return OciResponses.uploadProgress(location, ref.reference, received)
    }

    private fun finishUpload(repo: RepositoryProperties.Repo, ref: ImageReference, request: HttpServletRequest): ResponseEntity<*> {
        val param = request.getParameter("digest")
            ?: return OciResponses.error(HttpStatus.BAD_REQUEST, "DIGEST_INVALID", "missing digest on upload finalize")
        val digest = Digest.parse(param)
        val row = blobUploads.session(ref.reference) ?: return uploadUnknown()
        blobUploads.finalize(row, request.inputStream, digest)
        metrics.recordPublish(repo.name, "accepted")
        return OciResponses.blobCreated(OciPaths.blobLocation(repo.name, ref.imageName, digest), digest)
    }

    private fun blobResponse(repository: String, digest: Digest): ResponseEntity<*> {
        val stored = storage.readBlob(repository, digest)
        metrics.recordResolve(repository, if (stored != null) "hit" else "miss")
        return if (stored == null) OciResponses.blob(BlobOutcome.NotFound)
        else OciResponses.blob(BlobOutcome.Found(stored.stream, stored.sizeBytes, digest))
    }

    private fun uploadStatus(repo: RepositoryProperties.Repo, ref: ImageReference): ResponseEntity<*> {
        val row = blobUploads.session(ref.reference) ?: return uploadUnknown()
        val location = OciPaths.uploadLocation(repo.name, ref.imageName, ref.reference)
        return OciResponses.uploadProgress(location, ref.reference, row.bytesReceived)
    }

    private fun uploadUnknown(): ResponseEntity<*> =
        OciResponses.error(HttpStatus.NOT_FOUND, "BLOB_UPLOAD_UNKNOWN", "blob upload unknown")

    private companion object {
        const val OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
    }
}
