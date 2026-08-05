package io.github.jsilvanus.gitsema.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The only part of this module that knows it is a process. Everything it does
 * beyond that lives in [Cli], which is a function of its arguments and
 * streams — which is what lets the tests drive real commands against real
 * repositories without spawning a JVM.
 */
fun main(args: Array<String>) {
    val exitCode = runBlocking {
        Cli(out = System.out, err = System.err).run(args)
    }
    exitProcess(exitCode)
}
