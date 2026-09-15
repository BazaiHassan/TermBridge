import io.termbridge.buildlogic.lib
import io.termbridge.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.plugin.KaptExtension

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.dagger.hilt.android")
            pluginManager.apply("org.jetbrains.kotlin.kapt")
            extensions.configure<KaptExtension> { correctErrorTypes = true }
            dependencies {
                add("implementation", libs.lib("hilt-android"))
                add("kapt", libs.lib("hilt-compiler"))
            }
        }
    }
}
