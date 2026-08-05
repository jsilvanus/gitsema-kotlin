package io.github.jsilvanus.gitsema.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArgsTest {
    @Test
    fun `separated and inline flag values are equivalent`() {
        assertEquals("50", Args.parse(listOf("--top", "50")).string("top"))
        assertEquals("50", Args.parse(listOf("--top=50")).string("top"))
    }

    @Test
    fun `positionals are collected in order, around flags`() {
        val args = Args.parse(listOf("some", "--top", "5", "query", "words"))

        assertEquals(listOf("some", "query", "words"), args.positionals)
        assertEquals(5, args.int("top", 10))
    }

    @Test
    fun `declared boolean flags do not swallow the next argument`() {
        // The reason booleanFlags has to be declared: without it, `--quiet
        // HEAD` parses as quiet="HEAD" and the ref silently disappears.
        val args = Args.parse(listOf("--quiet", "HEAD"), booleanFlags = setOf("quiet"))

        assertTrue(args.boolean("quiet"))
        assertEquals(listOf("HEAD"), args.positionals)
    }

    @Test
    fun `a double dash stops flag parsing`() {
        val args = Args.parse(listOf("--top", "5", "--", "--not-a-flag"))

        assertEquals(listOf("--not-a-flag"), args.positionals)
        assertEquals(5, args.int("top", 1))
    }

    @Test
    fun `a flag with no value is a usage error, not a silent default`() {
        assertFailsWith<Args.UsageException> { Args.parse(listOf("--top")) }
    }

    @Test
    fun `a boolean flag given a value is a usage error`() {
        assertFailsWith<Args.UsageException> { Args.parse(listOf("--quiet=yes"), booleanFlags = setOf("quiet")) }
    }

    @Test
    fun `non-numeric and non-positive integers are rejected with the flag named`() {
        val notANumber = assertFailsWith<Args.UsageException> { Args.parse(listOf("--top", "lots")).int("top", 10) }
        assertTrue(notANumber.message!!.contains("--top"), "the message must name the flag: ${notANumber.message}")

        assertFailsWith<Args.UsageException> { Args.parse(listOf("--top", "0")).positiveInt("top", 10) }
    }

    @Test
    fun `defaults apply only when the flag is absent`() {
        assertEquals(10, Args.parse(emptyList()).int("top", 10))
        assertEquals(3, Args.parse(listOf("--top", "3")).int("top", 10))
    }

    @Test
    fun `a missing required option names itself`() {
        val e = assertFailsWith<Args.UsageException> { Args.parse(emptyList()).required("url") }
        assertTrue(e.message!!.contains("--url"))
    }
}
