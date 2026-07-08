pluginManagement {
    repositories {
        val usePublic = providers.gradleProperty("dependency.use.public").orNull == "true"

        if (usePublic) {
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

rootProject.name = "relikquary"

include("backend", "sandbox", "frontend", "integration-tests")

