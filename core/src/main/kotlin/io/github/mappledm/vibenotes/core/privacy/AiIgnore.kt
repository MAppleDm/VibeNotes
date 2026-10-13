package io.github.mappledm.vibenotes.core.privacy

import io.github.mappledm.vibenotes.core.storage.VaultPaths

/**
 * `.aiignore` in the vault root: gitignore-like rules for notes and folders the assistant
 * must never see. The file syncs with the vault, so the same rules are visible on a PC.
 *
 * Supported syntax (a practical subset of .gitignore):
 * - `# comment`, blank lines
 * - `Пароли.md`, `*.env` — no slash: matches a file or folder name at any depth
 * - `/Работа/Секреты.md`, `Личное/Дневник.md` — with a slash: matched from the vault root
 * - `Секреты/` — trailing slash: folders only (everything inside is hidden)
 * - `*` within a name, `**` across folders, `?` one character
 */
class AiIgnore private constructor(val rules: List<Rule>) {

    data class Rule(val pattern: String, val builtIn: Boolean) {
        private val dirOnly = pattern.endsWith("/")
        private val body = pattern.trimEnd('/')
        private val anchored = body.startsWith("/") || body.contains('/')
        private val regex = globToRegex(body.trimStart('/'))

        fun matches(path: String, isDirectory: Boolean): Boolean {
            val segments = VaultPaths.normalize(path).split('/').filter { it.isNotEmpty() }
            for (i in segments.indices) {
                val isDirPrefix = i < segments.lastIndex || isDirectory
                if (dirOnly && !isDirPrefix) continue
                val subject = if (anchored) segments.subList(0, i + 1).joinToString("/") else segments[i]
                if (regex.matches(subject)) return true
            }
            return false
        }
    }

    /** The first rule that hides [path], or null if the assistant may see it. */
    fun matchingRule(path: String, isDirectory: Boolean = false): Rule? {
        if (VaultPaths.normalize(path).equals(FILE_NAME, ignoreCase = true)) return BUILT_IN_SELF
        return rules.firstOrNull { it.matches(path, isDirectory) }
    }

    fun isHidden(path: String, isDirectory: Boolean = false): Boolean = matchingRule(path, isDirectory) != null

    companion object {
        const val FILE_NAME = ".aiignore"

        /** Secrets files are private even without an .aiignore file. */
        val BUILT_IN_PATTERNS = listOf("*.env")
        private val BUILT_IN_SELF = Rule(FILE_NAME, builtIn = true)

        val HEADER = """
            # Заметки и папки, которые VibeNotes никогда не отправляет AI.
            # Одна строка — одно правило, как в .gitignore:
            #   Пароли.md        файл с таким именем в любой папке
            #   /Работа/План.md  точный путь от корня
            #   Личное/          вся папка
            #   *.env            по маске (уже скрыто по умолчанию)
        """.trimIndent()

        fun parse(text: String?): AiIgnore {
            val user = text.orEmpty().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { Rule(it, builtIn = false) }
            return AiIgnore(BUILT_IN_PATTERNS.map { Rule(it, builtIn = true) } + user)
        }

        /** The exact, root-anchored rule the UI writes for one note or folder. */
        fun exactRule(path: String, isDirectory: Boolean): String =
            "/" + VaultPaths.normalize(path) + if (isDirectory) "/" else ""

        fun addRule(text: String?, rule: String): String {
            val current = text?.takeIf { it.isNotBlank() } ?: HEADER
            if (current.lines().any { it.trim().equals(rule, ignoreCase = true) }) return current
            val base = if (current.endsWith("\n")) current else "$current\n"
            return "$base$rule\n"
        }

        fun removeRule(text: String?, rule: String): String =
            text.orEmpty().lines()
                .filterNot { it.trim().equals(rule, ignoreCase = true) }
                .joinToString("\n")

        /** Updates an exact rule after a rename or move so the note stays hidden. */
        fun renameRule(text: String?, from: String, to: String): String? {
            val old = exactRule(from, isDirectory = false)
            val lines = text.orEmpty().lines()
            if (lines.none { it.trim().equals(old, ignoreCase = true) }) return null
            return lines.joinToString("\n") { if (it.trim().equals(old, ignoreCase = true)) exactRule(to, false) else it }
        }

        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        sb.append(".*")
                        i++
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
