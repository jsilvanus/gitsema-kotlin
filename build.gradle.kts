// Root build file: declares every plugin the subprojects use with
// `apply false`, and configures none of them. Per-module configuration lives
// in each module's own build.gradle.kts.
//
// This is not ceremony. It stayed empty while there was one module; the moment
// a second appeared (gitsema-cli, the JVM-only desktop tool anticipated by
// docs/design/kotlin-port.md §10 Decision A), applying Kotlin plugins with
// explicit versions in two subprojects loaded the Kotlin Gradle plugin twice
// on two classloaders, which Gradle rejects outright ("The Kotlin Gradle
// plugin was loaded multiple times in different subprojects"). Declaring the
// versions here resolves each one once, on one classpath, leaving each module
// to apply only what it needs.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
}
