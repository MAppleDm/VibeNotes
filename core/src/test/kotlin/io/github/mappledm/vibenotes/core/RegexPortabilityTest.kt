package io.github.mappledm.vibenotes.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which is stricter than the JVM:
 * a bare "}" (or a "{" that does not start a quantifier) is a syntax error there,
 * while Java accepts it. Such a pattern passes JVM tests and crashes on the phone,
 * so every Regex literal in the sources is checked here.
 */
class RegexPortabilityTest {

    private val literal = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val quantifier = Regex("\\{\\d+(,\\d*)?}")

    private fun problems(pattern: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    if (next != null && next in "pPNx" && pattern.getOrNull(i + 2) == '{') {
                        val close = pattern.indexOf('}', i + 3)
                        i = if (close < 0) pattern.length else close + 1
                        continue
                    }
                    i += 2
                    continue
                }
                c == '{' -> {
                    val q = quantifier.matchAt(pattern, i)
                    if (q != null) {
                        i = q.range.last + 1
                        continue
                    }
                    result += "bare '{' at $i"
                }
                c == '}' -> result += "bare '}' at $i"
            }
            i++
        }
        return result
    }

    @Test
    fun checkerCatchesTheAndroidCrash() {
        assertTrue(problems("""(?<!@)@\{([^}]+)}""").isNotEmpty())
        assertTrue(problems("""(?<!@)@\{([^\}]+)\}""").isEmpty())
        assertTrue(problems("""^(#{1,6})\s[\p{L}\p{N}]-{2,}""").isEmpty())
    }

    @Test
    fun allRegexLiteralsArePortableToAndroid() {
        val roots = listOf(File("src/main"), File("../app/src/main")).filter { it.exists() }
        assertTrue("source roots not found from ${File(".").absolutePath}", roots.isNotEmpty())
        val failures = mutableListOf<String>()
        roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }.forEach { file ->
            literal.findAll(file.readText()).forEach { match ->
                val pattern = match.groupValues[1]
                problems(pattern).forEach { failures += "${file.name}: $it in $pattern" }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
