import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── Secrets loaded from local.properties (NOT committed) ───────────────
// See local.properties.sample for the required keys.
val secretsFile = rootProject.file("local.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String =
    (secrets.getProperty(key) ?: System.getenv(key) ?: default)

android {
    namespace = "com.flexfly.ultron"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flexfly.ultron"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── Externalized secrets (recovered from decompiled source) ────
        buildConfigField("String", "OPENWEATHER_DEFAULT_KEY",
            "\"${secret("OPENWEATHER_DEFAULT_KEY", "")}\"")

        // ── Externalized secrets (recovered from decompiled source) ────
        buildConfigField("String", "OPENWEATHER_DEFAULT_KEY",
            "\"${secret("OPENWEATHER_DEFAULT_KEY", "")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Original shipped with R8 shrinking, non-renaming.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libs (Vosk) must be page-aligned & extracted at build time.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // ── AndroidX core ─────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)

    // ── Jetpack Compose ───────────────────────────────────────────
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Lifecycle ─────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // ── DataStore ─────────────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── CameraX ───────────────────────────────────────────────────
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // ── Coroutines ────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // ── Networking ────────────────────────────────────────────────
    implementation(libs.okhttp)

    // ── Gemini Live (raw WebSocket) ───────────────────────────────
    implementation(libs.java.websocket)

    // ── ML Kit on-device translation ──────────────────────────────
    implementation(libs.mlkit.translate)

    // ── Google Play services (location) ───────────────────────────
    implementation(libs.play.services.location)

    // ── ONNX Runtime for Silero VAD ───────────────────────────────
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
