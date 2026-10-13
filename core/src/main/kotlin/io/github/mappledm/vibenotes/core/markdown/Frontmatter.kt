package io.github.mappledm.vibenotes.core.markdown

/**
 * YAML frontmatter split. Only a tiny subset of YAML is interpreted (flat keys,
 * inline lists and dash lists) — enough for tags and the Kanban marker.
 * The raw block is always preserved so files round-trip unchanged.
 */
data class Frontmatter(
    /** Raw frontmatter including the surrounding '---' lines and trailing newline, or "" if absent. */
    val raw: String,
    val values: Map<String, List<String>>,
) {
    fun first(key: String): String? = values[key]?.firstOrNull()

    companion object {
        val EMPTY = Frontmatter("", emptyMap())

        /** Splits [text] into frontmatter and body. */
        fun split(text: String): Pair<Frontmatter, String> {
            val normalized = text
            if (!normalized.startsWith("---")) return EMPTY to text
            val firstLineEnd = normalized.indexOf('\n')
            if (firstLineEnd < 0 || normalized.substring(0, firstLineEnd).trimEnd() != "---") return EMPTY to text
            var pos = firstLineEnd + 1
            while (pos <= normalized.length) {
                val lineEnd = normalized.indexOf('\n', pos).let { if (it < 0) normalized.length else it }
                val line = normalized.substring(pos, lineEnd).trimEnd()
                if (line == "---" || line == "...") {
                    val rawEnd = if (lineEnd < normalized.length) lineEnd + 1 else lineEnd
                    val raw = normalized.substring(0, rawEnd)
                    val inner = normalized.substring(firstLineEnd + 1, pos)
                    return Frontmatter(raw, parseValues(inner)) to normalized.substring(rawEnd)
                }
                if (lineEnd >= normalized.length) break
                pos = lineEnd + 1
            }
            return EMPTY to text
        }

        private fun parseValues(inner: String): Map<String, List<String>> {
            val result = linkedMapOf<String, MutableList<String>>()
            var currentKey: String? = null
            for (rawLine in inner.lines()) {
                val line = rawLine.trimEnd()
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val trimmed = line.trimStart()
                if (trimmed.startsWith("- ") && currentKey != null) {
                    result.getOrPut(currentKey) { mutableListOf() } += unquote(trimmed.removePrefix("- ").trim())
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0 || line.startsWith(" ")) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                currentKey = key
                val list = result.getOrPut(key) { mutableListOf() }
                when {
                    value.isEmpty() -> Unit
                    value.startsWith("[") && value.endsWith("]") ->
                        value.substring(1, value.length - 1).split(',')
                            .map { unquote(it.trim()) }
                            .filter { it.isNotEmpty() }
                            .forEach { list += it }
                    else -> list += unquote(value)
                }
            }
            return result
        }

        private fun unquote(s: String): String =
            if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) {
                s.substring(1, s.length - 1)
            } else s
    }
}
