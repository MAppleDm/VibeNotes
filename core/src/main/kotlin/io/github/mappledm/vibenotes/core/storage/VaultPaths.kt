package io.github.mappledm.vibenotes.core.storage

/** Helpers for vault-relative paths ("Folder/Sub/Note.md"). */
object VaultPaths {
    const val MARKDOWN_EXTENSION = "md"
    const val TRASH_DIR = ".trash"

    private val forbiddenChars = Regex("""[\\/:*?"<>|#^\[\]\u0000-\u001F]""")
    private const val MAX_NAME_LENGTH = 120

    fun normalize(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    fun join(parent: String, child: String): String {
        val p = normalize(parent)
        val c = normalize(child)
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    fun parent(path: String): String {
        val n = normalize(path)
        val idx = n.lastIndexOf('/')
        return if (idx < 0) "" else n.substring(0, idx)
    }

    fun name(path: String): String {
        val n = normalize(path)
        return n.substring(n.lastIndexOf('/') + 1)
    }

    fun extension(path: String): String {
        val name = name(path)
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) "" else name.substring(idx + 1).lowercase()
    }

    fun baseName(path: String): String {
        val name = name(path)
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) name else name.substring(0, idx)
    }

    fun isMarkdown(path: String): Boolean = extension(path) == MARKDOWN_EXTENSION

    fun isHidden(path: String): Boolean = normalize(path).split('/').any { it.startsWith(".") }

    /**
     * Turns arbitrary user/AI text into a safe file name (without extension).
     * Characters that break links or file systems are replaced, length is capped.
     */
    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(forbiddenChars, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        val capped = if (cleaned.length > MAX_NAME_LENGTH) cleaned.substring(0, MAX_NAME_LENGTH).trim() else cleaned
        return capped
    }

    fun markdownFileName(title: String): String = "${sanitizeFileName(title)}.$MARKDOWN_EXTENSION"

    /**
     * Returns [desired] if it is free, otherwise "Name 2.md", "Name 3.md", ...
     */
    fun uniquePath(desired: String, isTaken: (String) -> Boolean): String {
        val normalized = normalize(desired)
        if (!isTaken(normalized)) return normalized
        val parent = parent(normalized)
        val base = baseName(normalized)
        val ext = extension(normalized).let { if (it.isEmpty()) "" else ".$it" }
        var counter = 2
        while (true) {
            val candidate = join(parent, "$base $counter$ext")
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }
}
