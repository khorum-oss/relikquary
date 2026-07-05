package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * S3-backend parity for the Gradle module feature (feature 011, FR-010/SC-007), verified against
 * adobe/s3mock as an external process with the app running on `backend=s3`. The `.module` is stored on
 * S3, the `/module` browse endpoint parses it identically to filesystem, and release immutability holds —
 * the same behaviour the filesystem tests assert.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.storage.backend=s3", "relikquary.security.enabled=false"],
)
class ModuleS3ParityTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    private fun put(path: String, body: ByteArray): Int =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    private fun getString(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `module browse parses identically on the S3 backend`() {
        assertEquals(201, put("/releases/com/acme/widget/1.2.3/widget-1.2.3.module", WELL_FORMED.toByteArray()))

        val contents = mapper.readTree(getString("/api/repositories/releases/contents/com/acme/widget/1.2.3").body())
        assertEquals("widget", contents.path("coordinate").path("artifact").asText())
        assertTrue(contents.path("module").path("path").asText().endsWith("widget-1.2.3.module"))

        val module = getString("/api/repositories/releases/module/com/acme/widget/1.2.3/widget-1.2.3.module")
        assertEquals(200, module.statusCode())
        val body = mapper.readTree(module.body())
        assertTrue(body.path("parseable").asBoolean())
        assertEquals("apiElements", body.path("variants").get(0).path("name").asText())
    }

    @Test
    fun `release module immutability holds on the S3 backend`() {
        val path = "/releases/com/acme/immutable/1.0/immutable-1.0.module"
        assertEquals(201, put(path, WELL_FORMED.toByteArray()))
        assertEquals(409, put(path, WELL_FORMED.toByteArray()))
    }

    companion object {
        private const val BUCKET = "relikquary"
        private val httpPort = freePort()
        private lateinit var process: Process

        @BeforeAll
        @JvmStatic
        fun startMock() {
            val jar = System.getProperty("relikquary.s3mockJar") ?: error("relikquary.s3mockJar not set")
            process = ProcessBuilder("java", "-jar", jar, "--server.port=${freePort()}", "--http.port=$httpPort")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:$httpPort"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build().use { awaitBucket(it) }
        }

        @AfterAll
        @JvmStatic
        fun stopMock() {
            if (::process.isInitialized) {
                process.destroy()
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.s3.endpoint") { "http://127.0.0.1:$httpPort" }
            registry.add("relikquary.storage.s3.region") { "us-east-1" }
            registry.add("relikquary.storage.s3.bucket") { BUCKET }
            registry.add("relikquary.storage.s3.access-key") { "foo" }
            registry.add("relikquary.storage.s3.secret-key") { "bar" }
            registry.add("relikquary.storage.s3.path-style-access") { true }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Wait until s3mock is actually serving S3, not merely accepting TCP. s3mock (a Spring Boot app)
         * accepts connections on its port before its context finishes booting; a request landing in that
         * window is answered with `Connection reset`. So the readiness gate is the real operation we need
         * next — bucket creation — retried while transient SDK errors mean "not ready yet". A bucket that
         * already exists (a retry after a reset that succeeded server-side) also counts as ready. If the
         * process dies (e.g. a bad jar), we fail fast instead of waiting out the whole deadline.
         */
        @Suppress("SwallowedException")
        private fun awaitBucket(client: S3Client) {
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos()
            var lastError: Exception? = null
            while (System.nanoTime() < deadline) {
                check(process.isAlive) { "s3mock process exited before becoming ready" }
                try {
                    client.createBucket { b -> b.bucket(BUCKET) }
                    return
                } catch (e: BucketAlreadyOwnedByYouException) {
                    return
                } catch (e: BucketAlreadyExistsException) {
                    return
                } catch (e: SdkException) {
                    lastError = e
                    Thread.sleep(250)
                }
            }
            throw IllegalStateException("s3mock did not become ready on port $httpPort", lastError)
        }

        private val WELL_FORMED = """
            {
              "formatVersion": "1.1",
              "component": { "group": "com.acme", "module": "widget", "version": "1.2.3" },
              "variants": [
                { "name": "apiElements", "attributes": { "org.gradle.usage": "java-api" },
                  "capabilities": [ { "group": "com.acme", "name": "widget-extra", "version": "1.2.3" } ],
                  "files": [ { "name": "widget-1.2.3.jar", "url": "widget-1.2.3.jar", "size": 12, "sha256": "abc" } ] }
              ]
            }
        """.trimIndent()
    }
}
