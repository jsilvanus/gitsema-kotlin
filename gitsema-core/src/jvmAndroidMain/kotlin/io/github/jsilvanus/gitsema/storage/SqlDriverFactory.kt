package io.github.jsilvanus.gitsema.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Constructing a [SqlDriver] is the one genuinely platform-specific piece of
 * the storage layer (kotlin-port.md §6.4): on the JVM it's a JDBC connection
 * string (backed by `sqlite-jdbc`, a native dependency confined to jvmMain
 * desktop/tooling builds -- it never ships in an Android artifact); on
 * Android it will be SQLDelight's `AndroidSqliteDriver` over the
 * platform-provided SQLite (`android.database.sqlite`), adding zero native
 * code of our own. Everything downstream of the driver -- every store
 * implementation -- is plain Kotlin in commonMain and doesn't know or care
 * which one it got.
 */
expect fun createSqlDriver(databasePath: String): SqlDriver
