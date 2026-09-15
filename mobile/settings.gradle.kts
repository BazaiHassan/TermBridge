pluginManagement {
    includeBuild("build-logic")
    // Google Maven is geo-blocked in some regions (e.g. Iran). When the gradle property
    // termbridge.googleMirror names a mirror of https://maven.google.com, it is tried first.
    val googleMirror = providers.gradleProperty("termbridge.googleMirror").orNull
    repositories {
        googleMirror?.let { maven(it) { name = "GoogleMirror" } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val googleMirror = providers.gradleProperty("termbridge.googleMirror").orNull
    repositories {
        googleMirror?.let { maven(it) { name = "GoogleMirror" } }
        google()
        mavenCentral()
    }
}

rootProject.name = "termbridge"

// Module names follow TermBridge-Architecture.md §7.2 (see docs/adr/0001).
include(":app")
include(":core-crypto")
include(":core-proto")
include(":core-terminal")
include(":core-transport")
include(":core-ui")
include(":feature-devices")
include(":feature-pairing")
include(":feature-terminal")
