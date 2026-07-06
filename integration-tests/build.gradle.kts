import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Integration-test module (feature 018 onward). Hosts Spring-context / Testcontainers integration tests
// that boot the real `:backend` application, kept out of the `backend` module so its heavier tests can be
// grown and (later) parallelised separately. Parallelism is intentionally kept to a single fork for now
// due to GitHub-hosted-runner limits. This module ships NO runnable artifact — it only runs tests against
// `:backend`. Coverage it produces over backend's classes is aggregated at the root (see root Kover config).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "org.khorum.oss.relikquary"

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
    // The application under test; brings backend's classes (compile) and its runtime deps (runtime).
    implementation(project(":backend"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    // ContainerStorageS3IT builds an S3ArtifactStorage directly, so the AWS SDK is needed at compile time
    // (backend declares it `implementation`, which is not exposed to consumers' compile classpath).
    testImplementation(libs.aws.s3)

    // Booting the app needs the persistence driver + SQLite dialect at runtime. Backend declares these
    // `runtimeOnly`; restate them here so the @SpringBootTest context starts even if they don't propagate.
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.hibernate.community.dialects)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

// No runnable artifact — disable the Boot fat-jar (there is no application main class in this module).
tasks.named("bootJar") { enabled = false }

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Keep CI parallelism low for now (GitHub-hosted runner limits); can be raised as the suite grows.
    maxParallelForks = 1
    // Keep the default (SQLite) datasource file inside the build dir for any test that doesn't override it.
    systemProperty(
        "relikquary.persistence.sqlite.path",
        layout.buildDirectory.file("test-sqlite/relikquary.db").get().asFile.absolutePath,
    )
}
