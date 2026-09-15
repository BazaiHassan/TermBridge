import com.android.build.api.dsl.LibraryExtension
import io.termbridge.buildlogic.addTestDependencies
import io.termbridge.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.unitTests.isReturnDefaultValues = true
            }
            addTestDependencies()
        }
    }
}
