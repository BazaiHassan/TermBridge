plugins {
    alias(libs.plugins.termbridge.android.feature)
}

android {
    namespace = "io.termbridge.feature.terminal"
}

dependencies {
    implementation(project(":core-terminal"))
    implementation(project(":core-transport"))
    implementation(project(":core-crypto"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
