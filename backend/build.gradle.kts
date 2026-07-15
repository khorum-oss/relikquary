import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    `maven-publish`
}

group = "org.khorum.oss.relikquary"

// Single source of truth for the release version is the repo-root VERSION file (also used by the
// container-image and CD flows), so the published Maven coordinates never drift from it.
version = rootProject.file("VERSION").readText().trim()

// KDoc → Javadoc-format HTML, packaged as the conventional `-javadoc.jar` so IDEs and
// `dependency:resolve -Dclassifier=javadoc` discover it. Dokka's Gradle plugin doesn't ship a jar task, so
// we wrap dokkaGeneratePublicationJavadoc's output ourselves (per Dokka docs).
val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "KDoc (Javadoc-format) documentation JAR"
    from(tasks.dokkaGeneratePublicationJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

// Main sources, packaged as the conventional `-sources.jar` (the classifier IDEs auto-attach). Registered
// manually rather than via `java { withSourcesJar() }` because the publication attaches the bootJar, not the
// `java` component.
val sourcesJar by tasks.registering(Jar::class) {
    description = "Main Kotlin/Java sources JAR"
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}

// adobe/s3mock runnable jar (exec classifier) — driven as an external process in S3RoundTripTest so it
// runs in its own JVM (no Spring Boot classpath clash) and needs no Docker.
val s3mock: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.liquibase)
    implementation(libs.liquibase.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.aws.s3)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)

    // Persistence drivers (feature 016, Phase 3): embedded SQLite by default, PostgreSQL when selected.
    // hibernate-community-dialects supplies the SQLite dialect (not in hibernate-core).
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.hibernate.community.dialects)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.postgresql)

    s3mock("com.adobe.testing:s3mock:${libs.versions.s3mock.get()}:exec") { isTransitive = false }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

// Locate the Maven executable for the real-client round-trip tests. MAVEN_EXECUTABLE wins when set
// (CI sets it); otherwise search PATH and the common install locations and use the absolute path, so a
// plain `./gradlew test` works out of the box wherever Maven is installed (rather than assuming a
// single hard-coded path). Falls back to a bare "mvn" as a last resort.
fun resolveMavenExecutable(): String {
    System.getenv("MAVEN_EXECUTABLE")?.takeIf { it.isNotBlank() }?.let { return it }
    val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar) +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/maven/bin", "/usr/bin", "/bin")
    return dirs.asSequence()
        .map { File(it, "mvn") }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
        ?: "mvn"
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Real-client round-trip tests drive the repo's Gradle wrapper and the system Maven via
    // external processes (keeping gradle-api/maven libs off the test classpath).
    systemProperty("relikquary.rootProjectDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("relikquary.mavenExecutable", resolveMavenExecutable())
    // Keep the default (SQLite) persistence backend's database file inside the build directory during
    // tests, so the JPA datasource every @SpringBootTest now boots never writes into the source tree.
    systemProperty(
        "relikquary.persistence.sqlite.path",
        layout.buildDirectory.file("test-sqlite/relikquary.db").get().asFile.absolutePath,
    )
    // The s3mock runnable jar, resolved lazily and passed to S3RoundTripTest.
    val s3mockJar = s3mock
    doFirst { systemProperty("relikquary.s3mockJar", s3mockJar.singleFile.absolutePath) }
}

// Opt-in: bundle the SvelteKit static build into the backend jar, served under /ui (see WebConfig).
// Enable with `-PbundleFrontend`. Kept opt-in so the frontend stays a separable module by default.
if (project.hasProperty("bundleFrontend")) {
    tasks.named<Copy>("processResources") {
        dependsOn(":frontend:npmBuild")
        from(rootProject.layout.projectDirectory.dir("frontend/build")) {
            into("static/ui")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("relikquary") {
            // group/version inherited from the project; artifactId matches rootProject.name.
            artifactId = "relikquary"
            // The runnable Spring Boot app is the main artifact (Spring Boot disables the plain `jar`).
            artifact(tasks.named("bootJar"))
            artifact(sourcesJar)
            artifact(dokkaJavadocJar)
            pom {
                name.set("Relikquary")
                description.set("Relikquary artifact repository — backend (Kotlin/Spring) application JAR.")
                url.set("https://github.com/khorum-oss/relikquary")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/khorum-oss/relikquary")
                    connection.set("scm:git:https://github.com/khorum-oss/relikquary.git")
                }
                developers {
                    developer {
                        id.set("khorum-oss")
                        name.set("khorum-oss")
                    }
                }
            }
        }
    }
    repositories {
        // Internal locations are NEVER committed. Each repo is registered only when its URL is injected at
        // call time (by `logos maven publish`). A bare checkout registers nothing and leaks nothing.
        val publisherUser = providers.environmentVariable("RELIKQUARY_PUBLISHER_USER")
        val publisherPw = providers.environmentVariable("RELIKQUARY_PUBLISHER_PW")
        listOf("Stage" to "RELIKQUARY_PUBLISH_STAGE_URL", "Prod" to "RELIKQUARY_PUBLISH_PROD_URL")
            .forEach { (repoName, urlVar) ->
                val urlProvider = providers.environmentVariable(urlVar)
                if (urlProvider.isPresent) {
                    maven {
                        name = repoName
                        url = uri(urlProvider.get())
                        // `file://` targets (used by the Step 5 test) need no auth; real HTTPS targets do.
                        if (url.scheme != "file" && publisherUser.isPresent) {
                            credentials {
                                username = publisherUser.get()
                                password = publisherPw.orNull
                            }
                        }
                    }
                }
            }
    }
}
