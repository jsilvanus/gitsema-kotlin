import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    `maven-publish`
}

group = "io.github.jsilvanus"
// `-Pversion=X` (used by the manual publish workflow) must be able to
// override this -- a bare `version = "0.1.0-SNAPSHOT"` assignment would
// silently clobber a command-line-supplied value, since script evaluation
// runs after Gradle sets project properties from -P flags.
version = (findProperty("version") as String?)?.takeUnless { it == "unspecified" } ?: "0.1.0-SNAPSHOT"

// ---------------------------------------------------------------------------
// Android target: wired and building. Requires an Android SDK — `sdk.dir` in
// local.properties, or ANDROID_HOME. `./gradlew build` on a machine without
// one will fail at configuration time; that's AGP's behaviour, not something
// this file can paper over.
//
// The source split it was designed around is unchanged: `jvmAndroidMain`
// (below) holds everything that needs java.* but is shared between jvm() and
// androidTarget() — JGit, the memory-mapped vector store — while commonMain
// stays free of java.*. Nothing in either source set had to change to add
// this target; the only genuinely Android-specific code is the SqlDriver
// `actual` (kotlin-port.md §6.4's storage seam). See androidMain/.
// ---------------------------------------------------------------------------

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // One publication ("androidRelease") rather than debug+release: a
        // consumer of this library has no use for a debug variant of an
        // index, and publishing both doubles the artifact set for nothing.
        publishLibraryVariants("release")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.kotlinx.datetime)
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

        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.sqldelight.android.driver)
                // FrameworkSQLiteDatabase (the SupportSQLiteDatabase wrapper
                // around a raw android.database.sqlite.SQLiteDatabase) is what
                // lets the driver be opened by absolute path with no Context —
                // see SqlDriverFactory.android.kt. Declared explicitly rather
                // than leaned on transitively through android-driver.
                implementation(libs.androidx.sqlite.framework)
            }
        }
        // Android unit tests (JVM-hosted, no device) run commonTest's pure-Kotlin
        // suites against the Android variant's compilation. They cannot cover
        // anything touching android.database.sqlite or a real repository on
        // device — that needs connectedAndroidTest against an emulator/device,
        // which this module does not yet have. Stated so the green
        // `testDebugUnitTest` is not mistaken for on-device verification.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "io.github.jsilvanus.gitsema"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// ---------------------------------------------------------------------------
// Publishing: GitHub Packages, manual-dispatch only (.github/workflows/publish.yml)
// — not on every push. Not expected to be used routinely while Tier 1/2 are
// still in flux; wired now per explicit request so it exists and is correct
// once there's something worth publishing, rather than being invented later
// under time pressure. KMP + maven-publish auto-creates one publication per
// target (currently just "jvm"; androidRelease joins automatically once that
// target is wired, no changes needed here).
// ---------------------------------------------------------------------------
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jsilvanus/gitsema-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("gitsema-kotlin")
            description.set("Kotlin Multiplatform port of gitsema's indexing and search core.")
            url.set("https://github.com/jsilvanus/gitsema-kotlin")
            licenses {
                license {
                    name.set("ISC License")
                    url.set("https://github.com/jsilvanus/gitsema-kotlin/blob/main/LICENSE")
                }
            }
            developers {
                developer {
                    id.set("jsilvanus")
                    name.set("Juha Itäleino")
                    email.set("jsilvanus@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/jsilvanus/gitsema-kotlin")
                connection.set("scm:git:https://github.com/jsilvanus/gitsema-kotlin.git")
            }
        }
    }
}
