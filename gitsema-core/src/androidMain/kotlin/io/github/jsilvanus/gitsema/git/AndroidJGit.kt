package io.github.jsilvanus.gitsema.git

import org.eclipse.jgit.storage.file.WindowCacheConfig

/**
 * Must be called once, before any [JGitRepository] is constructed, in a
 * process running on Android.
 *
 * **Why this exists.** JGit's `WindowCache` — initialised lazily the first
 * time a packfile is read, which is essentially any real repository access —
 * registers a JMX MBean when `WindowCacheConfig.exposeStatsViaJmx` is set,
 * and that flag defaults to `true`. Registration goes through
 * `org.eclipse.jgit.util.Monitoring`, which touches
 * `java.lang.management.ManagementFactory` and `javax.management.*`. Neither
 * package exists on Android: the whole `java.management` module is absent
 * from `android.jar`, so those references resolve to nothing at runtime.
 *
 * `Monitoring.registerMBean`'s catch block covers only the five
 * `javax.management` *checked exceptions* (verified against JGit 6.10's
 * bytecode exception table, not inferred from the source). A missing class
 * raises `NoClassDefFoundError`, an `Error` — outside that catch — so it
 * propagates out of the `WindowCache` constructor and out of whatever
 * repository read triggered it.
 *
 * Turning the flag off and installing that config keeps JGit from ever
 * reaching `Monitoring`. Nothing else in JGit's read path references the
 * absent module: of the 371 JDK classes JGit 6.10 references, exactly 14 are
 * missing from `android.jar` (API 34) — two are `java.lang.invoke` factories
 * that D8 desugars, eleven are this JMX path, and the last is
 * `java.lang.ProcessHandle`, reachable only from `GC$PidLock` (JGit's garbage
 * collector, which this library never invokes).
 *
 * **Not yet verified on a device.** The analysis above is static — bytecode
 * references, exception tables, and `android.jar`'s class list. No emulator
 * or device has run it. Two adjacent JGit behaviours are known hazards that
 * this function does *not* address and that on-device verification should
 * look at specifically: `FS_POSIX`'s probe for a system `git` executable
 * (there is none on Android), and `FileStoreAttributes`' filesystem
 * timestamp-resolution measurement.
 */
fun configureJGitForAndroid() {
    WindowCacheConfig().apply { exposeStatsViaJmx = false }.install()
}
