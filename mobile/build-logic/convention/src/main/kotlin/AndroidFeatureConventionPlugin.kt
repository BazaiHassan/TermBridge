import io.termbridge.buildlogic.lib
import io.termbridge.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature screen: Compose UI + Hilt ViewModels + a type-safe nav route. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("termbridge.android.library")
            pluginManager.apply("termbridge.android.compose")
            pluginManager.apply("termbridge.hilt")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                add("implementation", project(":core-ui"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-navigation-compose"))
                add("implementation", libs.lib("hilt-navigation-compose"))
                add("implementation", libs.lib("kotlinx-serialization-json"))
            }
        }
    }
}
