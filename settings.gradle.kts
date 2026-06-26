pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://open-reliquary.nyc3.cdn.digitaloceanspaces.com")
        }
    }
}

includeModules()

class Module(private val moduleName: String) {
    override fun toString(): String = moduleName
}

fun includeModules(vararg modules: Any) {
    val ids = modules.asSequence().flatMap { it.asStrings() }.toList()

    logger.debug("including: {}", ids)
    include(ids)
}

fun Any.asStrings(): Sequence<String> = when (this) {
    is String -> sequenceOf(this)
    is List<*> -> this.asSequence().map { it.toString() }
    else -> sequenceOf(toString())
}

fun String.subModules(vararg subModulesIds: String): List<Module> {
    val subModules = subModulesIds.map { Module("$this:$it") }

    return listOf(Module(this)) + subModules
}