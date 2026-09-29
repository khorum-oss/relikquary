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
 * round-trip through the hosted registry. Gated so it runs where it can and is SKIPPED (never failed)
 * where it cannot, keeping the core round-trip suite hermetic. Uses a `FROM scratch` image so no source
 * registry is contacted.
 *
 * **The precondition is reachability, not merely a daemon.** This test serves the registry from the JVM
 * on `127.0.0.1`, so it needs a daemon that shares *this host's* loopback. Docker Desktop on macOS and a
 * native Linux daemon do; a VM-backed daemon such as Colima does not — there `127.0.0.1` is the VM, and
 * the push dies with `connect: connection refused` against a port that is in fact listening. That is an
 * environment limitation rather than a registry defect, so it aborts as skipped and says why.
 *
 * Command output is captured and surfaced in failure messages. It used to be discarded, which made a
 * real failure here read only as `expected: <0> but was: <1>` — the cause (a missing credential helper)
 * was invisible until the command was re-run by hand.
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

        val build = exec("docker", "build", "-t", ref, ctx.toString())
        assertEquals(EXIT_OK, build.exit, "docker build\n${build.output}")

        val push = exec("docker", "push", ref)
        // A daemon that cannot route to this JVM's loopback (a VM-backed one, e.g. Colima) fails here
        // before the registry is ever consulted. Skip rather than report a registry defect that isn't one.
        assumeTrue(
            !push.unreachable(),
            "docker daemon cannot reach the test registry on 127.0.0.1:$port — " +
                "it does not share this host's loopback (VM-backed daemon?); skipping the round-trip",
        )
        assertEquals(EXIT_OK, push.exit, "docker push to the hosted registry\n${push.output}")

        // Remove the local copy so the pull actually fetches from our registry.
        exec("docker", "rmi", "-f", ref)
        val pull = exec("docker", "pull", ref)
        assertEquals(EXIT_OK, pull.exit, "docker pull back from the hosted registry\n${pull.output}")

        exec("docker", "rmi", "-f", ref)
    }

    /** A finished command: its exit code and combined stdout+stderr. */
    private inner class Executed(val exit: Int, val output: String) {
        /** Whether the daemon failed to reach our test server, rather than being rejected by it. */
        fun unreachable(): Boolean =
            output.contains("connection refused") || output.contains("no route to host")
    }

    private fun dockerAvailable(): Boolean =
        try {
            exec("docker", "info").exit == EXIT_OK
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    /**
     * Runs a command and returns its exit code with its combined output (a non-zero sentinel on timeout).
     * The stream is drained before waiting, so a chatty command cannot fill the pipe buffer and deadlock.
     */
    private fun exec(vararg command: String): Executed {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Executed(-1, output)
        }
        return Executed(process.exitValue(), output)
    }
}
