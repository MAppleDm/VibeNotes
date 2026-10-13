package io.github.mappledm.vibenotes.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun frontmatterSplitAndValues() {
        val (fm, body) = Frontmatter.split("---\ntags: [a, \"b c\"]\nlist:\n  - x\n  - y\n---\nBody")
        assertEquals(listOf("a", "b c"), fm.values["tags"])
        assertEquals(listOf("x", "y"), fm.values["list"])
        assertEquals("Body", body)
        assertEquals("", Frontmatter.split("No frontmatter").first.raw)
        assertEquals("---\nunclosed", Frontmatter.split("---\nunclosed").second)
    }

    @Test
    fun tagsIgnoreHeadingsCodeAndLinks() {
        val text = "---\ntags: [work]\n---\n# Heading\nText #idea and #проект/vibe.\n`#notatag`\n```\n#nope\n```\nhttp://x.com/#anchor"
        assertEquals(setOf("work", "idea", "проект/vibe"), MarkdownText.tags(text))
    }

    @Test
    fun wikiLinksAndPreview() {
        assertEquals(listOf("Note A", "B"), MarkdownText.wikiLinks("See [[Note A|alias]] and [[B#part]] and [[Note A]]"))
        assertEquals("Title · item · done task · link", MarkdownText.preview("# Title\n\n- item\n- [x] done task\n[link](http://x)"))
    }

    @Test
    fun toggleWrapAddsAndRemovesMarkers() {
        val bold = MarkdownEditing.toggleWrap(TextEdit("say hello", 4, 9), "**")
        assertEquals("say **hello**", bold.text)
        assertEquals(6 to 11, bold.selStart to bold.selEnd)
        val unbold = MarkdownEditing.toggleWrap(bold, "**")
        assertEquals("say hello", unbold.text)

        val empty = MarkdownEditing.toggleWrap(TextEdit("x ", 2), "*", "текст")
        assertEquals("x *текст*", empty.text)
        assertEquals(3 to 8, empty.selStart to empty.selEnd)
    }

    @Test
    fun toggleLinePrefixReplacesExistingPrefixes() {
        val heading = MarkdownEditing.toggleLinePrefix(TextEdit("one\ntwo", 5), "# ")
        assertEquals("one\n# two", heading.text)
        assertEquals(7, heading.selStart)

        val task = MarkdownEditing.toggleLinePrefix(TextEdit("- a\n- b", 0, 7), "- [ ] ")
        assertEquals("- [ ] a\n- [ ] b", task.text)
        val removed = MarkdownEditing.toggleLinePrefix(TextEdit(task.text, 0, task.text.length), "- [ ] ")
        assertEquals("a\nb", removed.text)
    }

    @Test
    fun continueListOnEnter() {
        val bullet = MarkdownEditing.continueList("- milk\n", 7)!!
        assertEquals("- milk\n- ", bullet.text)
        assertEquals(9, bullet.selStart)

        val numbered = MarkdownEditing.continueList("  9. step\n", 10)!!
        assertEquals("  9. step\n  10. ", numbered.text)

        val task = MarkdownEditing.continueList("- [x] done\n", 11)!!
        assertEquals("- [x] done\n- [ ] ", task.text)

        val stop = MarkdownEditing.continueList("- a\n- \n", 7)!!
        assertEquals("- a\n", stop.text)
        assertEquals(4, stop.selStart)

        assertNull(MarkdownEditing.continueList("plain\n", 6))
    }

    @Test
    fun toggleTaskLine() {
        assertEquals("a\n- [x] b", MarkdownEditing.toggleTaskAtLine("a\n- [ ] b", 1))
        assertEquals("a\n- [ ] b", MarkdownEditing.toggleTaskAtLine("a\n- [X] b", 1))
        assertEquals("a\nb", MarkdownEditing.toggleTaskAtLine("a\nb", 1))
    }

    @Test
    fun inlineParsing() {
        val r = MarkdownInline.parse("**Жирный _и курсив_** `код` [[Заметка|алиас]] [сайт](https://x.org) #тег")
        assertEquals("Жирный и курсив код алиас сайт #тег", r.text)
        val styles = r.spans.map { it.style }.toSet()
        assertTrue(styles.containsAll(listOf(InlineStyle.BOLD, InlineStyle.ITALIC, InlineStyle.CODE, InlineStyle.WIKILINK, InlineStyle.LINK, InlineStyle.TAG)))
        val wiki = r.spans.first { it.style == InlineStyle.WIKILINK }
        assertEquals("Заметка", wiki.target)
        assertEquals("алиас", r.text.substring(wiki.start, wiki.end))
        // snake_case is not italic
        assertEquals("snake_case_name", MarkdownInline.parse("snake_case_name").text)
    }

    @Test
    fun blockParsing() {
        val src = "---\na: b\n---\n# Title\n\nPara line 1\nline 2\n\n- [ ] task\n  - nested\n1. first\n> quote\n\n```kotlin\nval x = 1\n```\n| A | B |\n|---|---|\n| 1 | 2 |\n---"
        val blocks = MarkdownBlocks.parse(src)
        assertEquals(MdBlock.Heading(1, "Title", 3), blocks[0])
        assertEquals(MdBlock.Paragraph("Para line 1\nline 2", 5), blocks[1])
        val task = blocks[2] as MdBlock.ListItem
        assertEquals(false, task.checked)
        assertEquals(8, task.line)
        assertEquals(1, (blocks[3] as MdBlock.ListItem).depth)
        assertTrue((blocks[4] as MdBlock.ListItem).ordered)
        assertEquals("quote", (blocks[5] as MdBlock.Quote).text)
        assertEquals(MdBlock.Code("kotlin", "val x = 1", 13), blocks[6])
        assertEquals(listOf(listOf("1", "2")), (blocks[7] as MdBlock.Table).rows)
        assertTrue(blocks[8] is MdBlock.Rule)
    }

    @Test
    fun highlighterMarksHeadingsAndCode() {
        val h = MarkdownHighlighter.highlight("# Title\n**b**\n```\ncode\n```")
        assertTrue(h.any { it.kind == HighlightKind.HEADING_1 && it.start == 0 && it.end == 7 })
        assertTrue(h.any { it.kind == HighlightKind.BOLD && it.start == 8 && it.end == 13 })
        assertTrue(h.any { it.kind == HighlightKind.CODE_BLOCK && it.start == 18 && it.end == 22 })
    }
}
