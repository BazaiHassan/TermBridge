package io.termbridge.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** SDK levels from TermBridge-Architecture.md §7.1. */
object Sdk {
    const val COMPILE = 35
    const val MIN = 26
    const val TARGET = 35
}

private val JAVA = JavaVersion.VERSION_17
private val JVM = JvmTarget.JVM_17

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

internal fun Project.configureKotlinAndroid(android: CommonExtension<*, *, *, *, *, *>) {
    android.apply {
        compileSdk = Sdk.COMPILE
        defaultConfig.minSdk = Sdk.MIN
        compileOptions {
            sourceCompatibility = JAVA
            targetCompatibility = JAVA
        }
    }
    extensions.configure<KotlinAndroidProjectExtension> { compilerOptions.jvmTarget.set(JVM) }
    configureTests()
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA
        targetCompatibility = JAVA
    }
    extensions.configure<KotlinJvmProjectExtension> { compilerOptions.jvmTarget.set(JVM) }
    configureTests()
}

internal fun Project.addTestDependencies() {
    dependencies.add("testImplementation", libs.lib("junit"))
    dependencies.add("testImplementation", libs.lib("kotlin-test"))
}

private fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
