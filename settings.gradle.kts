pluginManagement {
    repositories {
        val usePublic = providers.gradleProperty("dependency.env").orNull == "public"

        if (usePublic) {
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

rootProject.name = "relikquary"

include("backend", "sandbox", "frontend", "integration-tests")

