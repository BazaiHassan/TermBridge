plugins {
    alias(libs.plugins.termbridge.android.feature)
}

android {
    namespace = "io.termbridge.feature.pairing"
}

dependencies {
    implementation(project(":core-proto"))
    implementation(project(":core-crypto"))
    implementation(project(":core-transport"))
    implementation(libs.androidx.core.ktx)

    // QR scanning (architecture §7.7): CameraX + ML Kit, on-device.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
}
