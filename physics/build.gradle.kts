// :physics — pure-Kotlin JVM module. ZERO Android dependencies. This is load-bearing.
//
// The seam between :physics and :app is the architectural invariant (ARCHITECTURE.md §1):
//   ./gradlew :physics:test   — runs the 7 PHYSICS.md corner cases on the JVM, no device needed.
//   ./gradlew :app:assembleDebug — assembles the Android APK, which depends on :physics as a JAR.
//
// Plugin: kotlin("jvm") = id "org.jetbrains.kotlin.jvm", versioned with Kotlin 2.4.0.
// Verified: kotlinlang.org/docs/gradle-configure-project.html (2026-06-13).
// java-library is the standard co-plugin for Gradle's api/implementation split in library modules.
// Verified: docs.gradle.org/9.2.0/samples/sample_building_kotlin_applications_multi_project.html (2026-06-13).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// JDK 17 matches AGP 9.2's requirement and is set on :app too; keep them aligned.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // kotlin-stdlib is pulled transitively by the kotlin("jvm") plugin; no explicit declaration needed.
    // Verified: kotlinlang.org/docs/gradle-configure-project.html §"Dependency on the standard library" (2026-06-13).

    // Test: JVM-only. kotlin-test-junit wires kotlin.test assertions to JUnit 4 runner.
    // Nothing in :physics is suspendable — the engine is synchronous, the classifier lives in :app —
    // so coroutines-test is intentionally absent. Add it back here only if something in this pure
    // module actually becomes async (it should not; that is the seam's whole point).
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
