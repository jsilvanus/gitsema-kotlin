import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A plain Kotlin/JVM module, deliberately not Kotlin Multiplatform: this is a
// desktop tool, so a KMP structure here would buy source sets for targets that
// will never exist. kotlin-port.md §10 Decision A anticipates exactly this —
// "a JVM-only, non-Android module" alongside the library — and the root build
// file was kept empty so adding one needed no restructuring.
//
// It consumes :gitsema-core through Gradle module metadata, which resolves the
// library's `jvm` variant automatically. Nothing Android-specific is pulled in.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "io.github.jsilvanus"
version = (findProperty("version") as String?)?.takeUnless { it == "unspecified" } ?: "0.1.0-SNAPSHOT"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":gitsema-core"))
    implementation(libs.kotlinx.coroutines.core)
    // HTTP is java.net.http (JDK 11+), so the only wire dependency is a JSON
    // codec. Hand-rolling that would mean hand-rolling string escaping for
    // arbitrary source-file content, which is exactly the wrong thing to get
    // subtly wrong.
    implementation(libs.kotlinx.serialization.json)
    // JGit logs through SLF4J and prints a "Failed to load StaticLoggerBinder"
    // banner to stderr on every run without a binding. A CLI has no use for
    // JGit's internal logging, so bind it to nothing rather than shipping a
    // logging framework and configuring it to be silent.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jgit)
}

application {
    mainClass.set("io.github.jsilvanus.gitsema.cli.MainKt")
}

tasks.named<Test>("test") {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
