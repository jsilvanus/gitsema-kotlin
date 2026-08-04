import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

group = "io.github.jsilvanus"
version = "0.1.0-SNAPSHOT"

// ---------------------------------------------------------------------------
// Android target: NOT wired in this build file yet.
//
// The porting brief requires JVM + Android targets. This sandboxed dev
// environment has neither an Android SDK nor network access to Google's Maven
// (dl.google.com is blocked by the outbound proxy here — confirmed via a
// direct request, not assumed), so the Android Gradle Plugin cannot be
// resolved, let alone an SDK configured. Wiring `androidTarget()` here without
// being able to build or test it would be committing unverified configuration.
//
// The source layout already anticipates this: `jvmAndroidMain` (below) holds
// everything that needs java.* but is shared between jvm() and a future
// androidTarget() — JGit usage, later the SQLite/vector-file code. Adding
// Android for real, once run somewhere with SDK + AGP access, is:
//   1. `alias(libs.plugins.android.library)` in the plugins block above.
//   2. `androidTarget { ... }` next to `jvm { ... }` below.
//   3. An `androidMain` source set with `dependsOn(jvmAndroidMain)`.
//   4. The `android { namespace = ...; compileSdk = ...; minSdk = ... }` block.
// No commonMain or jvmAndroidMain code needs to change for this — see
// docs/design/kotlin-port.md §7.3/§8 for why the split was made this way.
// ---------------------------------------------------------------------------

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Shared between jvm() and androidTarget() — both are JVM-bytecode
        // targets, so code needing java.* (JGit, java.nio for the vector store,
        // etc.) lives here rather than in commonMain, which must stay portable
        // to a hypothetical future non-JVM target (kotlin-port.md §9.3 notes
        // none is currently planned, but the seam costs nothing to keep clean).
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.jgit)
            }
        }

        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.sqldelight.jvm.driver)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

// SQLDelight generates its typed Database/Queries API from the .sq files
// under src/commonMain/sqldelight/ into commonMain-visible code — the
// generated API is itself pure Kotlin (no java.* usage), so the actual
// MetadataStore/FtsStore implementations can live in commonMain too. Only
// constructing the underlying SqlDriver is platform-specific (kotlin-port.md
// §6.4's storage seam) — see SqlDriverFactory.kt (expect in jvmAndroidMain,
// actual in jvmMain, and eventually androidMain).
sqldelight {
    databases {
        create("GitsemaDatabase") {
            packageName.set("io.github.jsilvanus.gitsema.db")
        }
    }
}
