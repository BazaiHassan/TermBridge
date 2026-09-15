import java.util.Properties

plugins {
    alias(libs.plugins.termbridge.android.application)
    alias(libs.plugins.termbridge.android.compose)
    alias(libs.plugins.termbridge.hilt)
}

/**
 * Release signing. Secrets never live in the repo: they come from environment variables (CI) or
 * from the gitignored mobile/keystore.properties (local). Without either, release builds are
 * produced unsigned rather than failing.
 */
val signing: Map<String, String>? = run {
    val env = mapOf(
        "storeFile" to System.getenv("TERMBRIDGE_KEYSTORE"),
        "storePassword" to System.getenv("TERMBRIDGE_KEYSTORE_PASSWORD"),
        "keyAlias" to System.getenv("TERMBRIDGE_KEY_ALIAS"),
        "keyPassword" to System.getenv("TERMBRIDGE_KEY_PASSWORD"),
    )
    if (env.values.all { !it.isNullOrBlank() }) return@run env.mapValues { it.value!! }
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        val props = Properties().apply { file.inputStream().use(::load) }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { key ->
            checkNotNull(props.getProperty(key)) { "keystore.properties is missing '$key'" }
        }
    }
}

android {
    namespace = "io.termbridge.app"

    defaultConfig {
        applicationId = "io.termbridge.app"
        versionCode = 1
        // Release builds in CI take the version from the git tag (v0.1.0-alpha.1 → 0.1.0-alpha.1).
        versionName = System.getenv("TERMBRIDGE_VERSION")?.removePrefix("v") ?: "0.1.0"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // ML Kit's bundled QR model ships a ~5 MB native library per ABI; one APK per ABI keeps each
    // under the 15 MB budget (architecture §9). Play builds (.aab) split automatically.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Post-quantum parameter tables from BouncyCastle; TermBridge never loads them.
        resources.excludes += "org/bouncycastle/pqc/**"
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-transport"))
    implementation(project(":core-crypto"))
    implementation(project(":feature-devices"))
    implementation(project(":feature-pairing"))
    implementation(project(":feature-terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
