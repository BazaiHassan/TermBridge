plugins {
    alias(libs.plugins.termbridge.android.feature)
}

android {
    namespace = "io.termbridge.feature.devices"
}

dependencies {
    // Paired machines live, sealed, in core-crypto's MachineStore.
    implementation(project(":core-crypto"))
}
