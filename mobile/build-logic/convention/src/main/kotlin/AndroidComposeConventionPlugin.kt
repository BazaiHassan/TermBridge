import com.android.build.api.dsl.CommonExtension
import io.termbridge.buildlogic.lib
import io.termbridge.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Apply after an Android application/library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val android = extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>
            android.buildFeatures.compose = true
            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                add("implementation", bom)
                add("implementation", libs.lib("androidx-compose-ui"))
                add("implementation", libs.lib("androidx-compose-ui-graphics"))
                add("implementation", libs.lib("androidx-compose-foundation"))
                add("implementation", libs.lib("androidx-compose-material3"))
                add("implementation", libs.lib("androidx-compose-material-icons-core"))
                add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
                add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
            }
        }
    }
}
