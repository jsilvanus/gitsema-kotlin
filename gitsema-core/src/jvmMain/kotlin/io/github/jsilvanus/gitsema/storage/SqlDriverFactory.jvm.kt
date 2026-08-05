package io.github.jsilvanus.gitsema.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import java.io.File

/**
 * JVM implementation: `sqlite-jdbc` (native, jvmMain-only — never ships to
 * Android; the Android target uses the platform's own SQLite, see
 * androidMain's `createSqlDriver(Context, String)`).
 *
 * This is deliberately *not* the `actual` half of an `expect fun` — Android's
 * driver cannot be constructed without a `Context`, so the two targets have
 * genuinely different signatures, and nothing in commonMain calls either one
 * (the driver is built at the platform edge by the host and injected
 * downstream). See the Android file for the full reasoning.
 *
 * Schema creation is intentionally the simplest thing that's correct for a
 * v1 schema with no migrations yet: create the tables only when the database
 * file doesn't already exist. Once a real schema evolution is needed, this
 * is where a versioned-migration runner (mirroring gitsema-TS's own
 * `PRAGMA user_version`-driven approach) belongs — not invented speculatively
 * here before there's a second schema version to migrate between. (The
 * Android driver already gets this from `SQLiteOpenHelper`; the two will need
 * reconciling when that second version arrives.)
 */
fun createSqlDriver(databasePath: String): SqlDriver {
    val isFreshDatabase = databasePath == ":memory:" || !File(databasePath).exists()
    val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")
    if (isFreshDatabase) {
        GitsemaDatabase.Schema.create(driver)
    }
    return driver
}
