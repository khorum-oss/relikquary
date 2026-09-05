package org.khorum.oss.relikquary.deploy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Offline guard for the deployment artifacts (feature 013). It never needs a container runtime: it
 * statically asserts the Dockerfiles, compose file, nginx template, and Kubernetes manifest hold their
 * critical invariants (non-root, JRE 21, probes wired to the actuator endpoints, persistence, auth on)
 * and — most importantly — that **no committed deploy artifact carries a real secret value**. The
 * Docker-dependent end-to-end check lives in `deploy/smoke.sh`, guarded on a runtime being present.
 */
class DeploymentArtifactsTest {

    private val deployDir = File(System.getProperty("relikquary.rootProjectDir"), "deploy")

    private fun read(relative: String): String {
        val file = File(deployDir, relative)
        assertTrue(file.isFile) { "missing deployment artifact: deploy/$relative" }
        return file.readText()
    }

    @Test
    fun `backend and combined images are non-root, JRE 21, with a readiness healthcheck`() {
        for (dockerfile in listOf("backend.Dockerfile", "combined.Dockerfile")) {
            val text = read(dockerfile)
            assertTrue(text.contains("eclipse-temurin:21-jre")) { "$dockerfile must run on a JRE 21 base" }
            assertTrue(text.contains("USER relikquary")) { "$dockerfile must run as a non-root user" }
            assertTrue(text.contains("HEALTHCHECK")) { "$dockerfile must define a HEALTHCHECK" }
            assertTrue(text.contains("/actuator/health/readiness")) {
                "$dockerfile HEALTHCHECK must hit the readiness probe"
            }
        }
    }

    @Test
    fun `frontend image is non-root nginx with a healthcheck`() {
        val text = read("frontend.Dockerfile")
        assertTrue(text.contains("nginx-unprivileged")) { "frontend image must use non-root nginx" }
        assertTrue(text.contains("HEALTHCHECK")) { "frontend image must define a HEALTHCHECK" }
    }

    @Test
    fun `nginx template serves the SPA and proxies API and repo paths to the backend`() {
        val text = read("nginx/default.conf.template")
        assertTrue(text.contains("\${RELIKQUARY_BACKEND}")) { "template must proxy to \${RELIKQUARY_BACKEND}" }
        assertTrue(text.contains("proxy_pass")) { "template must reverse-proxy to the backend" }
        assertTrue(text.contains("/index.html")) { "template must fall back to the SPA shell" }
        assertTrue(text.contains("dashboard|publish|users|settings")) {
            "template must serve the top-level SPA sections as app-shell fallbacks, not proxy them (feature 016)"
        }
    }

    @Test
    fun `nginx template lifts the body-size cap at server scope, not only in the proxy block`() {
        val text = read("nginx/default.conf.template")
        // Regression guard for HS-13. nginx matches `location /` before the `try_files ... @backend`
        // internal redirect, and enforces client_max_body_size there — so the directive must live at
        // SERVER scope. Confined to `location @backend` it is dead: nginx's 1 MB default still applies
        // and every large single-PUT upload (a Maven jar, an OCI blob) 413s before reaching the backend.
        // Directives only — a comment mentioning a location block must not count either way.
        val directives = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val serverScope = directives
            .dropWhile { !it.startsWith("server {") }
            .drop(1)
            .takeWhile { !it.startsWith("location ") }
        assertTrue(serverScope.any { it == "client_max_body_size 0;" }) {
            "client_max_body_size 0 must be set at server scope (before any location block), or large " +
                "uploads 413 in location / before reaching @backend — see HS-13"
        }
    }

    @Test
    fun `compose enables auth, persists storage, and healthchecks the backend`() {
        val text = read("docker-compose.yml")
        assertTrue(text.contains("RELIKQUARY_SECURITY_USERS_0_ROLES_0: PUBLISH")) {
            "compose must configure a PUBLISH user (auth on)"
        }
        assertTrue(text.contains("relikquary-store:/data")) { "compose must persist storage on a volume" }
        assertTrue(text.contains("/actuator/health/readiness")) { "compose must healthcheck readiness" }
    }

    @Test
    fun `kubernetes manifest has the core objects, probes, limits, and non-root context`() {
        val text = read("k8s/relikquary.yaml")
        for (kind in listOf("kind: Deployment", "kind: Service", "kind: ConfigMap", "kind: Secret", "kind: PersistentVolumeClaim")) {
            assertTrue(text.contains(kind)) { "k8s manifest must contain $kind" }
        }
        assertTrue(text.contains("path: /actuator/health/liveness")) { "manifest must wire the liveness probe" }
        assertTrue(text.contains("path: /actuator/health/readiness")) { "manifest must wire the readiness probe" }
        assertTrue(text.contains("limits:")) { "manifest must set resource limits" }
        assertTrue(text.contains("runAsNonRoot: true")) { "manifest must run as non-root" }
    }

    @Test
    fun `no committed deployment artifact carries a real secret value`() {
        // Match an UPPERCASE env-style credential key being assigned a value (config files, not prose).
        val assignment = Regex("""\b[A-Z0-9_]*(?:PASSWORD|SECRET_KEY|ACCESS_KEY)\b\s*[:=]\s*(\S.*)""")
        // A value is safe when it is not a committed literal: an env-var reference (`${VAR}`), a shell
        // command substitution (`$(...)`, e.g. create-items.sh generating a password at runtime), or the
        // explicit `changeme` marker. Anything else is a candidate real secret.
        val placeholder = Regex("""\$\{|\$\(|changeme""")
        // Config artifacts only — skip markdown docs and the (excluded) smoke script.
        val files = deployDir.walkTopDown()
            .filter { it.isFile && it.extension != "md" && it.name != "smoke.sh" }
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                if (line.trimStart().startsWith("#")) return@forEachIndexed
                val value = assignment.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                assertTrue(placeholder.containsMatchIn(value)) {
                    "possible committed secret in ${file.relativeTo(deployDir)} line ${index + 1}: $line"
                }
            }
        }
    }
}
