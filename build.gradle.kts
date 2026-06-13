// Top-level build file. Plugins are declared here (apply false) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin.android REMOVED — built-in to AGP 9.0+ (developer.android.com/build/migrate-to-built-in-kotlin, 2026-06-13)
    alias(libs.plugins.compose.compiler) apply false // Compose Compiler Gradle plugin
    // Pure-Kotlin JVM plugin for :physics. id = "org.jetbrains.kotlin.jvm", versioned with Kotlin.
    // Verified: kotlinlang.org/docs/gradle-configure-project.html (2026-06-13).
    alias(libs.plugins.kotlin.jvm) apply false
}
