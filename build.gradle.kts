plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
    application
    alias(libs.plugins.khorum.pipeline) apply false
    alias(libs.plugins.khorum.secrets) apply false
    alias(libs.plugins.khorum.maven.artifacts) apply false
    alias(libs.plugins.khorum.digital.ocean) apply false
}

group = "org.khorum.oss.konstellation"

extra["dslVersion"] = file("VERSION").readText().trim()
extra["metaDslVersion"] = libs.versions.meta.dsl.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

sharedRepositories()

allprojects {
    apply {
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.dokka")
        plugin("application")
        plugin("org.jetbrains.kotlinx.kover")
    }

    sharedRepositories()

    dependencies {
        implementation(kotlin("stdlib"))
        implementation(rootProject.libs.kotlin.reflect)
        implementation(rootProject.libs.kotlin.logging)

        testImplementation(kotlin("test"))
        testImplementation(rootProject.libs.junit.jupiter.api)
        testRuntimeOnly(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }
}

fun Project.sharedRepositories() {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven { url = uri("https://www.jetbrains.com/intellij-repository/releases") }
        maven { url = uri("https://open-reliquary.nyc3.cdn.digitaloceanspaces.com") }
    }
}

tasks.register("koverMergedReport") {
    group = "verification"
    description = "Generates coverage report for the dsl module"

    dependsOn(project(":dsl").tasks.named("koverXmlReport"))
}

sonar {
    properties {
        property("sonar.projectKey", "khorum-oss_konstellation-dsl")
        property("sonar.organization", "khorum-oss")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${project(":dsl").layout.buildDirectory.get()}/reports/kover/report.xml"
        )
    }
}
