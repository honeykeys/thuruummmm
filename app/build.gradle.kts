plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler) // Compose Compiler plugin (Kotlin-versioned)
    // kotlin.android REMOVED — AGP 9.0+ has built-in Kotlin; the standalone plugin is rejected.
    // Verified: developer.android.com/build/migrate-to-built-in-kotlin (2026-06-13)
}

android {
    namespace = "com.thrum"
    compileSdk = 37 // androidx.core(-ktx) 1.19.0 requires compileSdk 37 (AAR metadata check); AGP 9.2 max API is 37

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

// JVM target follows android.compileOptions.targetCompatibility (17) under built-in Kotlin —
// no `kotlin { jvmToolchain }` block needed. Add `kotlin { compilerOptions { jvmTarget = ... } }`
// only to override. Verified: developer.android.com/build/migrate-to-built-in-kotlin (2026-06-13)

dependencies {
    // :physics is pure Kotlin — no Android dependencies. The seam is load-bearing (ARCHITECTURE.md §1).
    implementation(project(":physics"))

    // JVM unit tests for com.thrum.deck (DeckTest, DeckRegistryIntegrityTest, etc.).
    // kotlin-test-junit wires kotlin.test assertions to the JUnit 4 runner.
    // Verified: kotlinlang.org/docs/gradle-configure-project.html §"Dependency on the standard library" (2026-06-13).
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    // Virtual-time JVM tests for the flourish hold (ARCHITECTURE.md §6). runTest/advanceTimeBy/
    // advanceUntilIdle/currentTime in package kotlinx.coroutines.test.
    // Verified: kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/ (2026-06-13).
    testImplementation(libs.kotlinx.coroutines.test)

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
