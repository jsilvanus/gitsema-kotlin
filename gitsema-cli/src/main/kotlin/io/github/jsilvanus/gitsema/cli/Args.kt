package io.github.jsilvanus.gitsema.cli

/**
 * A deliberately small `--flag value` / `--flag=value` parser.
 *
 * Hand-rolled rather than pulling in an argument-parsing library: the surface
 * is four commands and a dozen flags, and this module's dependency list is
 * short on purpose. If the command set grows enough that this stops being
 * obviously correct, that is the signal to take the dependency — not before.
 *
 * Unknown flags are an error rather than being ignored, because a silently
 * dropped `--top 50` produces a plausible-looking wrong answer.
 */
class Args private constructor(
    val positionals: List<String>,
    private val flags: Map<String, String>,
    private val booleans: Set<String>,
) {
    /** Thrown for anything the caller got wrong; [Cli] maps it to exit code 2. */
    class UsageException(message: String) : Exception(message)

    fun string(name: String, default: String? = null): String? = flags[name] ?: default

    fun required(name: String): String =
        flags[name] ?: throw UsageException("missing required option --$name")

    fun int(name: String, default: Int): Int {
        val raw = flags[name] ?: return default
        return raw.toIntOrNull() ?: throw UsageException("--$name expects an integer, got '$raw'")
    }

    fun positiveInt(name: String, default: Int): Int {
        val value = int(name, default)
        if (value < 1) throw UsageException("--$name must be at least 1, got $value")
        return value
    }

    fun boolean(name: String): Boolean = name in booleans

    companion object {
        /**
         * @param booleanFlags names that take no value. Declared up front
         * because `--verbose search` is otherwise ambiguous — the parser
         * cannot tell a flag's value from the next positional without knowing
         * which flags take one.
         */
        fun parse(argv: List<String>, booleanFlags: Set<String> = emptySet()): Args {
            val positionals = mutableListOf<String>()
            val flags = mutableMapOf<String, String>()
            val booleans = mutableSetOf<String>()

            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                when {
                    arg == "--" -> {
                        positionals += argv.drop(i + 1)
                        break
                    }
                    arg.startsWith("--") -> {
                        val body = arg.removePrefix("--")
                        val (name, inlineValue) = body.split("=", limit = 2).let {
                            it[0] to it.getOrNull(1)
                        }
                        if (name.isEmpty()) throw UsageException("malformed option '$arg'")
                        when {
                            name in booleanFlags -> {
                                if (inlineValue != null) throw UsageException("--$name is a flag and takes no value")
                                booleans += name
                            }
                            inlineValue != null -> flags[name] = inlineValue
                            i + 1 < argv.size -> {
                                flags[name] = argv[i + 1]
                                i++
                            }
                            else -> throw UsageException("--$name expects a value")
                        }
                    }
                    else -> positionals += arg
                }
                i++
            }
            return Args(positionals, flags, booleans)
        }
    }
}
