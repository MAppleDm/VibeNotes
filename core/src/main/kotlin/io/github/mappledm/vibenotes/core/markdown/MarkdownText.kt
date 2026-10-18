package io.github.mappledm.vibenotes.core.markdown

/** Lightweight text analysis of Markdown notes (tags, links, previews). */
object MarkdownText {
    private val tagRegex = Regex("""(?<![\w/#&])#([\p{L}\p{N}_][\p{L}\p{N}_/\-]*)""")
    private val wikiLinkRegex = Regex("""\[\[([^\]\|#\n]+)(#[^\]\|\n]*)?(\|[^\]\n]*)?]]""")
    private val inlineCodeRegex = Regex("`[^`\n]*`")

    /** Lines of [body] that are outside fenced code blocks. */
    fun proseLines(body: String): List<String> {
        val result = mutableListOf<String>()
        var inFence = false
        for (line in body.lines()) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (!inFence) result += line
        }
        return result
    }

    fun tags(text: String): Set<String> {
        val (frontmatter, body) = Frontmatter.split(text)
        val result = linkedSetOf<String>()
        (frontmatter.values["tags"] ?: frontmatter.values["tag"]).orEmpty()
            .flatMap { it.split(' ', ',') }
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .forEach { result += it }
        for (line in proseLines(body)) {
            val withoutCode = line.replace(inlineCodeRegex, "")
            // "# Heading" is a heading, not a tag; the regex requires no space after '#'.
            tagRegex.findAll(withoutCode).forEach { match ->
                val tag = match.groupValues[1].trimEnd('/', '-')
                if (tag.any { it.isLetter() }) result += tag
            }
        }
        return result
    }

    /** Targets of [[wiki links]] (without alias or heading part). */
    fun wikiLinks(text: String): List<String> =
        wikiLinkRegex.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** Plain-text preview used in note lists. */
    fun preview(text: String, maxLength: Int = 160): String {
        val (_, body) = Frontmatter.split(text)
        val sb = StringBuilder()
        for (line in proseLines(body)) {
            val stripped = stripLine(line)
            if (stripped.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(stripped)
            if (sb.length >= maxLength) break
        }
        return if (sb.length > maxLength) sb.substring(0, maxLength).trimEnd() + "…" else sb.toString()
    }

    fun stripLine(line: String): String =
        line.trim()
            .replace(Regex("""^#{1,6}\s+"""), "")
            .replace(Regex("""^[-*+]\s+\[[ xX]]\s+"""), "")
            .replace(Regex("""^([-*+]|\d+[.)])\s+"""), "")
            .replace(Regex("""^>\s?"""), "")
            .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(wikiLinkRegex) { m -> m.groups[3]?.value?.removePrefix("|") ?: m.groupValues[1] }
            .replace(Regex("""(\*\*|__|~~|\*|_|`)"""), "")
            .replace(Regex("""^\|.*\|$""")) { it.value.trim('|').replace("|", " ") }
            .trim()
}
