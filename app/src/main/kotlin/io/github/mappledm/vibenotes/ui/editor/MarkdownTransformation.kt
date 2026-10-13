package io.github.mappledm.vibenotes.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.github.mappledm.vibenotes.core.markdown.HighlightKind
import io.github.mappledm.vibenotes.core.markdown.MarkdownHighlighter

data class EditorColors(
    val text: Color,
    val muted: Color,
    val syntax: Color,
    val accent: Color,
    val codeBackground: Color,
)

/**
 * Light syntax highlighting while editing. Characters are not hidden or moved,
 * so the offset mapping is the identity and the cursor always matches the file.
 */
class MarkdownTransformation(private val colors: EditorColors) : VisualTransformation {
    private var lastInput: String? = null
    private var lastOutput: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw == lastInput) lastOutput?.let { return it }
        val builder = AnnotatedString.Builder(raw)
        for (h in MarkdownHighlighter.highlight(raw)) {
            val start = h.start.coerceIn(0, raw.length)
            val end = h.end.coerceIn(start, raw.length)
            if (start == end) continue
            builder.addStyle(style(h.kind), start, end)
        }
        val result = TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        lastInput = raw
        lastOutput = result
        return result
    }

    private fun style(kind: HighlightKind): SpanStyle = when (kind) {
        HighlightKind.HEADING_1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
        HighlightKind.HEADING_2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)
        HighlightKind.HEADING_3 -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        HighlightKind.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
        HighlightKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        HighlightKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.muted)
        HighlightKind.CODE, HighlightKind.CODE_BLOCK -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
        HighlightKind.QUOTE -> SpanStyle(color = colors.muted, fontStyle = FontStyle.Italic)
        HighlightKind.LINK, HighlightKind.TAG -> SpanStyle(color = colors.accent)
        HighlightKind.LIST_MARKER -> SpanStyle(color = colors.accent)
        HighlightKind.TASK_DONE -> SpanStyle(color = colors.muted, textDecoration = TextDecoration.LineThrough)
        HighlightKind.SYNTAX -> SpanStyle(color = colors.syntax)
    }
}
