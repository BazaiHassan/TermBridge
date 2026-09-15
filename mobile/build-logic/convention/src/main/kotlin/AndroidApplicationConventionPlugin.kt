import com.android.build.api.dsl.ApplicationExtension
import io.termbridge.buildlogic.Sdk
import io.termbridge.buildlogic.addTestDependencies
import io.termbridge.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = Sdk.TARGET
                // Multi-release metadata duplicated by BouncyCastle and jspecify; unused on Android.
                packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            }
            addTestDependencies()
        }
    }
}
