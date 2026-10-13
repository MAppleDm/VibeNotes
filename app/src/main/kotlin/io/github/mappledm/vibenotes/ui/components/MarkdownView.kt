package io.github.mappledm.vibenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.mappledm.vibenotes.core.markdown.InlineStyle
import io.github.mappledm.vibenotes.core.markdown.MarkdownBlocks
import io.github.mappledm.vibenotes.core.markdown.MarkdownInline
import io.github.mappledm.vibenotes.core.markdown.MdBlock
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import io.github.mappledm.vibenotes.ui.theme.MonoStyle

/** Converts inline Markdown to a styled string with clickable web and [[wiki]] links. */
@Composable
fun rememberInline(source: String, onWikiLink: ((String) -> Unit)?): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = LocalExtraColors.current.codeBackground
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    return remember(source, primary, codeBg, onWikiLink) {
        val parsed = MarkdownInline.parse(source)
        val linkStyles = TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            append(parsed.text)
            for (span in parsed.spans) {
                val start = span.start.coerceIn(0, parsed.text.length)
                val end = span.end.coerceIn(start, parsed.text.length)
                if (start == end) continue
                when (span.style) {
                    InlineStyle.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
                    InlineStyle.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    InlineStyle.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                    InlineStyle.CODE -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, end)
                    InlineStyle.HIGHLIGHT -> addStyle(SpanStyle(background = highlight), start, end)
                    InlineStyle.TAG -> addStyle(SpanStyle(color = primary), start, end)
                    InlineStyle.LINK -> {
                        val url = span.target.orEmpty()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            addLink(LinkAnnotation.Url(url, linkStyles), start, end)
                        } else {
                            addStyle(SpanStyle(color = primary), start, end)
                        }
                    }
                    InlineStyle.WIKILINK -> {
                        val target = span.target.orEmpty()
                        if (onWikiLink != null) {
                            addLink(
                                LinkAnnotation.Clickable(
                                    tag = target,
                                    styles = linkStyles,
                                    linkInteractionListener = LinkInteractionListener { onWikiLink(target) },
                                ),
                                start,
                                end,
                            )
                        } else {
                            addStyle(SpanStyle(color = primary), start, end)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Read mode: renders Markdown blocks natively (no WebView).
 * Task checkboxes call [onToggleTask] with the source line so the file can be updated.
 */
@Composable
fun MarkdownView(
    source: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    compact: Boolean = false,
    onToggleTask: ((Int) -> Unit)? = null,
    onWikiLink: ((String) -> Unit)? = null,
) {
    val blocks = remember(source) { MarkdownBlocks.parse(source) }
    val gap = if (compact) 6.dp else 10.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        rememberInline(block.text, onWikiLink),
                        style = style,
                        modifier = Modifier.padding(top = if (compact) 2.dp else 8.dp),
                    )
                }
                is MdBlock.Paragraph -> Text(rememberInline(block.text, onWikiLink), style = textStyle)
                is MdBlock.ListItem -> ListItemRow(block, textStyle, onToggleTask, onWikiLink)
                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        rememberInline(block.text, onWikiLink),
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(LocalExtraColors.current.codeBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(block.code, style = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface))
                }
                is MdBlock.Table -> TableView(block, textStyle, onWikiLink)
                is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ListItemRow(
    item: MdBlock.ListItem,
    textStyle: TextStyle,
    onToggleTask: ((Int) -> Unit)?,
    onWikiLink: ((String) -> Unit)?,
) {
    val checked = item.checked
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 18).dp),
        verticalAlignment = if (checked != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        when {
            checked != null -> Checkbox(
                checked = checked,
                onCheckedChange = onToggleTask?.let { toggle -> { _: Boolean -> toggle(item.line) } },
                modifier = Modifier.size(32.dp),
            )
            item.ordered -> Text("${item.number}.", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
            else -> Text("•", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        val style = if (checked == true) {
            textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
        } else textStyle
        Text(rememberInline(item.text, onWikiLink), style = style)
    }
}

@Composable
private fun TableView(table: MdBlock.Table, textStyle: TextStyle, onWikiLink: ((String) -> Unit)?) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, border, MaterialTheme.shapes.small),
    ) {
        val rows = listOf(table.header) + table.rows
        rows.forEachIndexed { index, row ->
            Row(if (index == 0) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier) {
                for (col in 0 until columns) {
                    Box(
                        Modifier
                            .width(140.dp)
                            .border(0.5.dp, border)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            rememberInline(row.getOrElse(col) { "" }, onWikiLink),
                            style = if (index == 0) textStyle.copy(fontWeight = FontWeight.SemiBold) else textStyle,
                        )
                    }
                }
            }
        }
    }
}
