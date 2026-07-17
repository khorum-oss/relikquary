package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Feature 021 (US2 optional): the gold-standard real-client check — a genuine `docker` build/push/pull
 * round-trip through the hosted registry. Gated on Docker-daemon availability, so it runs where a daemon
 * exists and is SKIPPED (never failed) where it does not (e.g. CI or sandboxes without a daemon), keeping
 * the core round-trip suite hermetic. Uses a `FROM scratch` image so no source registry is contacted.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-container-it.yml"],
)
class ContainerDockerClientIT {

    @LocalServerPort
    var port: Int = 0

    companion object {
        const val EXIT_OK = 0
        const val CMD_TIMEOUT_SECONDS = 120L

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    @Test
    fun `a real docker client pushes and pulls an image through the hosted registry`(@TempDir ctx: Path) {
        assumeTrue(dockerAvailable(), "no Docker daemon available — skipping the real-client round-trip")

        // A minimal image with no source registry: FROM scratch + a tiny file.
        Files.writeString(ctx.resolve("payload"), "relikquary-021")
        Files.writeString(ctx.resolve("Dockerfile"), "FROM scratch\nCOPY payload /payload\n")
        val ref = "127.0.0.1:$port/apps/dockerclient:it"

        assertEquals(EXIT_OK, run("docker", "build", "-t", ref, ctx.toString()), "docker build")
        assertEquals(EXIT_OK, run("docker", "push", ref), "docker push to the hosted registry")
        // Remove the local copy so the pull actually fetches from our registry.
        run("docker", "rmi", "-f", ref)
        assertEquals(EXIT_OK, run("docker", "pull", ref), "docker pull back from the hosted registry")

        run("docker", "rmi", "-f", ref)
    }

    private fun dockerAvailable(): Boolean =
        try {
            run("docker", "info") == EXIT_OK
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    /** Runs a command, returns its exit code (or a non-zero sentinel on timeout). Output is discarded. */
    private fun run(vararg command: String): Int {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }
}
