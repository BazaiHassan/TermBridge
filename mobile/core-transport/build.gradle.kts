plugins {
    alias(libs.plugins.termbridge.android.library)
    alias(libs.plugins.termbridge.hilt)
}

android {
    namespace = "io.termbridge.core.transport"
}

dependencies {
    api(project(":core-proto"))
    implementation(project(":core-crypto"))
    api(libs.kotlinx.coroutines.android)
    // WebSocket client (architecture §7.7).
    implementation(libs.okhttp)

    testImplementation(libs.kotlinx.coroutines.test)
}
