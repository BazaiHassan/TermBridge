// Same optional Google Maven mirror as the main build; an included build does not see the main
// build's gradle.properties, so read it directly.
val googleMirror: String? = providers.gradleProperty("termbridge.googleMirror").orNull
    ?: file("../gradle.properties").takeIf { it.exists() }?.let { f ->
        java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("termbridge.googleMirror")
    }

dependencyResolutionManagement {
    repositories {
        googleMirror?.let { maven(it) { name = "GoogleMirror" } }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include(":convention")
