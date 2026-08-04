package io.github.jsilvanus.gitsema.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jsilvanus.gitsema.db.GitsemaDatabase

/**
 * Android implementation: the platform's own SQLite
 * (`android.database.sqlite`), adding zero native code of our own — which is
 * also what makes FTS5 free here (kotlin-port.md §9.2 point 5: Android's
 * bundled SQLite ships with FTS5).
 *
 * **Why this takes a [Context] when the JVM overload doesn't.** Constructing
 * an `AndroidSqliteDriver` requires either a `Context` or a
 * `SupportSQLiteDatabase`, and the only public way to obtain the latter from
 * a raw `SQLiteDatabase` — `FrameworkSQLiteDatabase` — is Kotlin-`internal`
 * in androidx.sqlite (public in bytecode, so this is a compiler-verified
 * fact, not one `javap` will tell you). There is therefore no Context-free
 * path to a driver short of implementing `SupportSQLiteDatabase` by hand.
 * That is why this is a plain platform function rather than the `actual` half
 * of an `expect fun createSqlDriver(databasePath: String)`: a signature the
 * two targets cannot share should not pretend to be shared. Nothing in
 * commonMain calls it — the driver is built at the platform edge by the host
 * (Aidos on Android, tests and desktop tooling on the JVM) and injected
 * downstream, so the expect/actual bought type-checking for a call that is
 * never written in common code.
 *
 * [databasePath] is passed to the platform as the database *name*. An
 * absolute path is what Aidos will pass (it owns the index location —
 * `.aidos/index/`, outside `state.db`, per aidos D21/D29), and
 * `Context.getDatabasePath` resolves an absolute name to that exact file
 * rather than to the app's `databases/` directory. **That resolution is
 * asserted from AOSP's documented behaviour, not verified here** — this
 * module has no emulator, so it belongs on the on-device verification list
 * along with JGit. A relative name still works and lands in the app's
 * private `databases/` directory.
 *
 * Unlike the JVM overload, this path gets schema versioning for free:
 * SQLDelight's `AndroidSqliteDriver` drives `GitsemaDatabase.Schema` through
 * `SQLiteOpenHelper`, so `onCreate` and `onUpgrade` are already wired for
 * whenever a second schema version exists.
 */
fun createSqlDriver(context: Context, databasePath: String): SqlDriver =
    AndroidSqliteDriver(
        schema = GitsemaDatabase.Schema,
        context = context,
        name = databasePath,
    )
