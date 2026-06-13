plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler) // §1: Compose Compiler Gradle plugin, versioned with Kotlin
}

android {
    namespace = "com.thrum"
    compileSdk = 36 // §1: required by current AndroidX (androidx.core 1.17+)

    defaultConfig {
        applicationId = "com.thrum"
        minSdk = 31 // unlocks THUD/SPIN/LOW_TICK (API 31) the five signatures lean on
        targetSdk = 35 // §1: current Google Play requirement
        versionCode = 1
        versionName = "0.1-haptic-bench"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true // NOTE: no composeOptions{} block — the compiler is the plugin above (§7)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17) // JDK 17 required by AGP 9.2 (§1)
}

dependencies {
    // :physics is pure Kotlin — no Android dependencies. The seam is load-bearing (ARCHITECTURE.md §1).
    implementation(project(":physics"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
