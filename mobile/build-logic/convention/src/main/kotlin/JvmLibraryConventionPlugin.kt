import io.termbridge.buildlogic.addTestDependencies
import io.termbridge.buildlogic.configureKotlinJvm
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Pure Kotlin/JVM module: no Android dependency, millisecond unit tests. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureKotlinJvm()
            addTestDependencies()
        }
    }
}
