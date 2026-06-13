// Top-level build file. Plugins are declared here (apply false) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false // §1: Compose Compiler Gradle plugin
    // Pure-Kotlin JVM plugin for :physics. id = "org.jetbrains.kotlin.jvm", versioned with Kotlin.
    // Verified: kotlinlang.org/docs/gradle-configure-project.html (2026-06-13).
    alias(libs.plugins.kotlin.jvm) apply false
}
