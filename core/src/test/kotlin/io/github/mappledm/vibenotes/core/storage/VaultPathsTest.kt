package io.github.mappledm.vibenotes.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPathsTest {
    @Test
    fun normalizeJoinAndParts() {
        assertEquals("a/b/c.md", VaultPaths.normalize("/a//b/./c.md"))
        assertEquals("a/b.md", VaultPaths.join("a/", "/b.md"))
        assertEquals("b.md", VaultPaths.join("", "b.md"))
        assertEquals("a", VaultPaths.parent("a/b.md"))
        assertEquals("", VaultPaths.parent("b.md"))
        assertEquals("b", VaultPaths.baseName("a/b.md"))
        assertEquals("md", VaultPaths.extension("a/B.MD"))
        assertTrue(VaultPaths.isMarkdown("x/y.md"))
        assertFalse(VaultPaths.isMarkdown("x/y.txt"))
        assertTrue(VaultPaths.isHidden(".trash/a.md"))
    }

    @Test
    fun sanitizeRemovesCharactersThatBreakFilesAndLinks() {
        assertEquals("Встреча 15 00 план", VaultPaths.sanitizeFileName("Встреча 15:00 / план"))
        assertEquals("Идея", VaultPaths.sanitizeFileName("  [[Идея]]?  "))
        assertEquals("", VaultPaths.sanitizeFileName(" ... "))
        assertEquals(120, VaultPaths.sanitizeFileName("x".repeat(300)).length)
    }

    @Test
    fun uniquePathAddsCounter() {
        val taken = setOf("Notes/Идея.md", "Notes/Идея 2.md")
        assertEquals("Notes/Идея 3.md", VaultPaths.uniquePath("Notes/Идея.md") { it in taken })
        assertEquals("Notes/Другое.md", VaultPaths.uniquePath("Notes/Другое.md") { it in taken })
    }
}
