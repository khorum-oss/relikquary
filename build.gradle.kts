plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
}

group = "org.khorum.oss.relikquary"

dependencies {
    kover(project(":backend"))
}

sonar {
    properties {
        property("sonar.projectKey", "khorum-relikquary")
        property("sonar.organization", "khorum-oss")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${project(":backend").layout.buildDirectory.get()}/reports/kover/report.xml",
        )
    }
}

// Container image builds (feature 013). Thin wrappers over `docker build` so operators have one obvious
// entry point and can pick the split images vs. the combined single image. The build context is the repo
// root; each task fails with a clear message if the Docker CLI is absent. No new dependency is added.
val dockerCli = "docker"

// Resolve a CLI to an absolute path: the Gradle daemon's process-spawn PATH may not match an interactive
// shell's (e.g. it can miss /usr/local/bin), so a bare command name can fail with "error=2, No such file".
// Common tool dirs are appended as a fallback. Returns null when the tool genuinely isn't installed.
fun cliPath(cli: String): String? {
    val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar) +
        listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin")
    return dirs.asSequence()
        .flatMap { sequenceOf(File(it, cli), File(it, "$cli.exe")) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
}

fun Exec.dockerBuild(dockerfile: String, tag: String) {
    group = "deployment"
    workingDir = rootDir
    val docker = cliPath(dockerCli)
    commandLine(docker ?: dockerCli, "build", "-f", dockerfile, "-t", tag, ".")
    // Surface a friendly message instead of a stack trace when Docker isn't installed.
    doFirst {
        require(docker != null) { "Docker CLI ('$dockerCli') not found — install Docker to build images." }
    }
}

tasks.register<Exec>("dockerBuildBackend") {
    description = "Build the backend (API) image: relikquary-backend:local"
    dockerBuild("deploy/backend.Dockerfile", "relikquary-backend:local")
}

tasks.register<Exec>("dockerBuildFrontend") {
    description = "Build the frontend (UI) image: relikquary-frontend:local"
    dockerBuild("deploy/frontend.Dockerfile", "relikquary-frontend:local")
}

tasks.register<Exec>("dockerBuildCombined") {
    description = "Build the combined API+UI image (UI under /ui): relikquary:local"
    dockerBuild("deploy/combined.Dockerfile", "relikquary:local")
}

tasks.register("dockerBuildSplit") {
    group = "deployment"
    description = "Build both split images (backend + frontend)."
    dependsOn("dockerBuildBackend", "dockerBuildFrontend")
}

// Local-dev Kubernetes deploy (the k8s counterpart of `docker compose -f docker-compose.dev.yml up`).
// Thin wrappers over `kubectl` against the current kube-context; each fails with a clear message if the
// kubectl CLI is absent. No new dependency is added.
val kubectlCli = "kubectl"
val devManifest = "deploy/k8s/relikquary-dev.yaml"

tasks.register<Exec>("k8sDeployDev") {
    group = "deployment"
    description = "Apply the local-dev k8s stack ($devManifest) — apply only, no image rebuild or rollout."
    workingDir = rootDir
    // Apply is idempotent and does NOT rebuild images or roll pods (the ':local' tag stays the same).
    // For build + apply + roll + status in one step, use deploy/dev-k8s.sh.
    val kubectl = cliPath(kubectlCli)
    commandLine(kubectl ?: kubectlCli, "apply", "-f", devManifest)
    doFirst { require(kubectl != null) { "'$kubectlCli' not found — install kubectl and point it at a cluster to deploy." } }
    doLast {
        logger.lifecycle("Applied $devManifest (namespace 'relikquary-dev'). Images were NOT (re)built or rolled.")
        logger.lifecycle("  Full build + apply + roll + status:  deploy/dev-k8s.sh deploy")
        logger.lifecycle("  Pick up rebuilt images:              deploy/dev-k8s.sh restart")
        logger.lifecycle("  Ports / URLs:                        deploy/dev-k8s.sh status")
    }
}

tasks.register<Exec>("k8sDeleteDev") {
    group = "deployment"
    description = "Tear down the local-dev k8s stack (deletes the 'relikquary-dev' namespace)."
    workingDir = rootDir
    val kubectl = cliPath(kubectlCli)
    commandLine(kubectl ?: kubectlCli, "delete", "namespace", "relikquary-dev", "--ignore-not-found")
    doFirst { require(kubectl != null) { "'$kubectlCli' not found — install kubectl and point it at a cluster." } }
}
