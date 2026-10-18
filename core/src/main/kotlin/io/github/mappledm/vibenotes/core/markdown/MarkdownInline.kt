package io.github.mappledm.vibenotes.core.markdown

enum class InlineStyle { BOLD, ITALIC, STRIKE, CODE, HIGHLIGHT, LINK, WIKILINK, TAG }

/** Style applied to [start, end) of [InlineText.text]. [target] is set for links and tags. */
data class InlineSpan(val start: Int, val end: Int, val style: InlineStyle, val target: String? = null)

/** Rendered inline Markdown: display text with markers removed, plus style spans. */
data class InlineText(val text: String, val spans: List<InlineSpan>)

/** Parses inline Markdown (emphasis, code, links, [[wiki links]], #tags) for display. */
object MarkdownInline {
    private val tokenRegex = Regex(
        listOf(
            """(?<code>`+)(?<codeBody>.+?)\k<code>""",
            """!\[(?<imgAlt>[^\]]*)]\((?<imgUrl>[^)\s]+)[^)]*\)""",
            """\[\[(?<wiki>[^\]\|\n]+)(?:\|(?<wikiAlias>[^\]\n]+))?]]""",
            """\[(?<linkText>[^\]\n]+)]\((?<linkUrl>[^)\s]+)[^)]*\)""",
            """\*\*(?<bold>[^*\n](?:.*?[^*\n])?)\*\*""",
            """__(?<bold2>[^_\n](?:.*?[^_\n])?)__""",
            """~~(?<strike>[^~\n](?:.*?[^~\n])?)~~""",
            """==(?<mark>[^=\n](?:.*?[^=\n])?)==""",
            """\*(?<italic>[^*\s](?:[^*\n]*?[^*\s])?)\*""",
            """(?<![\p{L}\p{N}])_(?<italic2>[^_\s](?:[^_\n]*?[^_\s])?)_(?![\p{L}\p{N}])""",
            """(?<url>https?://[^\s<>()]+[^\s<>().,;:!?'"])""",
            """(?<![\p{L}\p{N}/#&])#(?<tag>[\p{L}_][\p{L}\p{N}_/\-]*)""",
        ).joinToString("|"),
    )

    fun parse(source: String): InlineText {
        val out = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        append(source, out, spans)
        return InlineText(out.toString(), spans)
    }

    private fun append(source: String, out: StringBuilder, spans: MutableList<InlineSpan>) {
        var pos = 0
        while (pos < source.length) {
            val match = tokenRegex.find(source, pos)
            if (match == null) {
                out.append(source, pos, source.length)
                return
            }
            out.append(source, pos, match.range.first)
            val g = match.groups
            val start = out.length
            when {
                g["codeBody"] != null -> {
                    out.append(g["codeBody"]!!.value.trim())
                    spans += InlineSpan(start, out.length, InlineStyle.CODE)
                }
                g["imgUrl"] != null -> {
                    val alt = g["imgAlt"]?.value.orEmpty().ifEmpty { "изображение" }
                    out.append("🖼 ").append(alt)
                    spans += InlineSpan(start, out.length, InlineStyle.LINK, g["imgUrl"]!!.value)
                }
                g["wiki"] != null -> {
                    val target = g["wiki"]!!.value.trim()
                    out.append(g["wikiAlias"]?.value?.trim() ?: target)
                    spans += InlineSpan(start, out.length, InlineStyle.WIKILINK, target)
                }
                g["linkUrl"] != null -> {
                    append(g["linkText"]!!.value, out, spans)
                    spans += InlineSpan(start, out.length, InlineStyle.LINK, g["linkUrl"]!!.value)
                }
                g["bold"] != null || g["bold2"] != null -> {
                    append((g["bold"] ?: g["bold2"])!!.value, out, spans)
                    spans += InlineSpan(start, out.length, InlineStyle.BOLD)
                }
                g["strike"] != null -> {
                    append(g["strike"]!!.value, out, spans)
                    spans += InlineSpan(start, out.length, InlineStyle.STRIKE)
                }
                g["mark"] != null -> {
                    append(g["mark"]!!.value, out, spans)
                    spans += InlineSpan(start, out.length, InlineStyle.HIGHLIGHT)
                }
                g["italic"] != null || g["italic2"] != null -> {
                    append((g["italic"] ?: g["italic2"])!!.value, out, spans)
                    spans += InlineSpan(start, out.length, InlineStyle.ITALIC)
                }
                g["url"] != null -> {
                    out.append(g["url"]!!.value)
                    spans += InlineSpan(start, out.length, InlineStyle.LINK, g["url"]!!.value)
                }
                g["tag"] != null -> {
                    out.append('#').append(g["tag"]!!.value)
                    spans += InlineSpan(start, out.length, InlineStyle.TAG, g["tag"]!!.value)
                }
                else -> out.append(match.value)
            }
            pos = match.range.last + 1
        }
    }
}
