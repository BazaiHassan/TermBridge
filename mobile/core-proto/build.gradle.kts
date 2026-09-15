plugins {
    alias(libs.plugins.termbridge.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // JSON control payloads (HELLO, SESSION_OPEN, ERROR) — never on the DATA hot path.
    implementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // Golden vectors shared with the Go codec (docs/PROTOCOL.md §7).
    val vectors = rootProject.file("../docs/vectors/frames.json")
    inputs.file(vectors).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("termbridge.vectors", vectors.absolutePath)
}
