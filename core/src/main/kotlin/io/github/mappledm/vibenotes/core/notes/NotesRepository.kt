package io.github.mappledm.vibenotes.core.notes

import io.github.mappledm.vibenotes.core.kanban.KanbanFormat
import io.github.mappledm.vibenotes.core.markdown.Frontmatter
import io.github.mappledm.vibenotes.core.markdown.MarkdownText
import io.github.mappledm.vibenotes.core.privacy.AiIgnore
import io.github.mappledm.vibenotes.core.storage.FileEntry
import io.github.mappledm.vibenotes.core.storage.FileStore
import io.github.mappledm.vibenotes.core.storage.StorageException
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class NoteKind { NOTE, BOARD }

data class NoteMeta(
    val path: String,
    val lastModified: Long,
    val size: Long,
    val kind: NoteKind = NoteKind.NOTE,
    val tags: Set<String> = emptySet(),
    val preview: String = "",
    /** Listed in .aiignore (or a built-in rule): the assistant never sees this note. */
    val hiddenFromAi: Boolean = false,
) {
    val title: String get() = VaultPaths.noteTitle(path)
    val folder: String get() = VaultPaths.parent(path)
}

data class VaultIndex(
    val notes: List<NoteMeta>,
    val folders: List<String>,
    val loaded: Boolean,
    val hiddenFolders: Set<String> = emptySet(),
) {
    val boards: List<NoteMeta> get() = notes.filter { it.kind == NoteKind.BOARD }

    companion object {
        val EMPTY = VaultIndex(emptyList(), emptyList(), loaded = false)
    }
}

data class SearchHit(val note: NoteMeta, val snippet: String, val score: Int)

/**
 * Notes stored as plain Markdown files in a [FileStore].
 *
 * Keeps an in-memory index (metadata + content) that is refreshed incrementally:
 * only files whose size or modification time changed are re-read.
 */
class NotesRepository(private val store: FileStore) {

    private data class Cached(val meta: NoteMeta, val content: String)

    private val mutex = Mutex()
    // Concurrent: searches read it from UI threads while refreshes write from IO.
    private val cache = ConcurrentHashMap<String, Cached>()
    private var folders: List<String> = emptyList()
    @Volatile
    private var aiIgnore: AiIgnore = AiIgnore.parse(null)
    private val _index = MutableStateFlow(VaultIndex.EMPTY)

    val index: StateFlow<VaultIndex> = _index.asStateFlow()
    val storeName: String get() = store.displayName

    /** Re-scans the folder. Cheap when nothing changed. */
    suspend fun refresh(): VaultIndex = mutex.withLock {
        val entries = store.listAll()
        aiIgnore = AiIgnore.parse(runCatching { store.read(AiIgnore.FILE_NAME) }.getOrNull())
        folders = entries.filter { it.isDirectory }.map { it.path }.sorted()
        val markdown = entries.filter { !it.isDirectory && VaultPaths.isNoteFile(it.path) }
        val present = markdown.map { it.path }.toSet()
        cache.keys.retainAll(present)

        val changed = markdown.filter { entry ->
            val cached = cache[entry.path]
            cached == null || cached.meta.lastModified != entry.lastModified || cached.meta.size != entry.size
        }
        if (changed.isNotEmpty() && !_index.value.loaded) {
            // Publish names first so a large vault shows up immediately.
            publish(loaded = false, extra = changed.map { NoteMeta(it.path, it.lastModified, it.size) })
        }
        for (entry in changed) {
            val content = runCatching { store.read(entry.path) }.getOrNull() ?: continue
            cache[entry.path] = Cached(buildMeta(entry, content), content)
        }
        publish(loaded = true)
    }

    /** Forgets the index and reads every file again (for "something looks stale"). */
    suspend fun reindex(): VaultIndex {
        mutex.withLock { cache.clear() }
        return refresh()
    }

    private fun publish(loaded: Boolean, extra: List<NoteMeta> = emptyList()): VaultIndex {
        val rules = aiIgnore
        val known = cache.values.map { it.meta }
        val knownPaths = known.map { it.path }.toSet()
        val notes = (known + extra.filter { it.path !in knownPaths })
            .map { it.copy(hiddenFromAi = rules.isHidden(it.path)) }
            .sortedByDescending { it.lastModified }
        val hiddenFolders = folders.filter { rules.isHidden(it, isDirectory = true) }.toSet()
        val index = VaultIndex(notes, folders, loaded, hiddenFolders)
        _index.value = index
        return index
    }

    private fun buildMeta(entry: FileEntry, content: String): NoteMeta = NoteMeta(
        path = entry.path,
        lastModified = entry.lastModified,
        size = entry.size,
        kind = if (KanbanFormat.isBoard(content)) NoteKind.BOARD else NoteKind.NOTE,
        tags = MarkdownText.tags(content),
        preview = MarkdownText.preview(content),
        hiddenFromAi = aiIgnore.isHidden(entry.path),
    )

    fun cachedContent(path: String): String? = cache[VaultPaths.normalize(path)]?.content

    fun meta(path: String): NoteMeta? = cache[VaultPaths.normalize(path)]?.meta

    /** Reads the current file content from storage (not from cache). */
    suspend fun read(path: String): String? {
        val normalized = VaultPaths.normalize(path)
        val content = store.read(normalized) ?: return null
        if (!isIndexed(normalized)) return content
        mutex.withLock {
            val entry = store.stat(normalized)
            if (entry != null) cache[normalized] = Cached(buildMeta(entry, content), content)
            publish(loaded = _index.value.loaded)
        }
        return content
    }

    suspend fun save(path: String, content: String): NoteMeta = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        store.write(normalized, content)
        updateCacheAfterWrite(normalized, content)
    }

    /** Creates a note with a unique file name derived from [title]. */
    suspend fun create(folder: String, title: String, content: String = ""): NoteMeta = mutex.withLock {
        val safeTitle = VaultPaths.sanitizeFileName(title).ifEmpty { DEFAULT_TITLE }
        val path = uniquePath(VaultPaths.join(folder, VaultPaths.noteFileName(safeTitle)))
        store.write(path, content)
        if (folder.isNotEmpty() && folder !in folders) folders = (folders + folder).sorted()
        updateCacheAfterWrite(path, content)
    }

    /** Appends a block of text to a note, creating the note when it does not exist. */
    suspend fun append(path: String, text: String): NoteMeta = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        val existing = store.read(normalized)
        val newContent = when {
            existing.isNullOrEmpty() -> text.trimEnd() + "\n"
            existing.endsWith("\n") -> existing + text.trimEnd() + "\n"
            else -> existing + "\n" + text.trimEnd() + "\n"
        }
        store.write(normalized, newContent)
        updateCacheAfterWrite(normalized, newContent)
    }

    suspend fun rename(path: String, newTitle: String): String = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        val safeTitle = VaultPaths.sanitizeFileName(newTitle)
        if (safeTitle.isEmpty()) throw StorageException("Название не может быть пустым")
        val fileName = VaultPaths.noteFileName(safeTitle, keepExtension = VaultPaths.extension(normalized))
        val target = VaultPaths.join(VaultPaths.parent(normalized), fileName)
        if (target == normalized) return@withLock normalized
        if (!target.equals(normalized, ignoreCase = true) && store.stat(target) != null) {
            throw StorageException("Заметка «${VaultPaths.noteTitle(target)}» уже существует")
        }
        moveCached(normalized, target)
        followIgnoreRule(normalized, target)
        target
    }

    suspend fun move(path: String, newFolder: String): String = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        val target = uniquePath(VaultPaths.join(newFolder, VaultPaths.name(normalized)))
        if (target == normalized) return@withLock normalized
        moveCached(normalized, target)
        followIgnoreRule(normalized, target)
        target
    }

    /** Moves the note into the hidden ".trash" folder instead of destroying it. Returns the trash path. */
    suspend fun delete(path: String): String = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        val trashPath = uniquePath(VaultPaths.join(VaultPaths.TRASH_DIR, VaultPaths.name(normalized)))
        store.move(normalized, trashPath)
        cache.remove(normalized)
        publish(loaded = true)
        trashPath
    }

    suspend fun restore(trashPath: String, originalPath: String): String = mutex.withLock {
        val target = uniquePath(originalPath)
        store.move(trashPath, target)
        val content = store.read(target) ?: ""
        updateCacheAfterWrite(target, content)
        target
    }

    suspend fun createFolder(path: String) = mutex.withLock {
        val normalized = VaultPaths.normalize(path)
        if (normalized.isEmpty()) return@withLock
        store.createDirectory(normalized)
        folders = (folders + normalized).distinct().sorted()
        publish(loaded = _index.value.loaded)
    }

    private suspend fun moveCached(from: String, to: String) {
        store.move(from, to)
        val content = cache.remove(from)?.content ?: store.read(to) ?: ""
        updateCacheAfterWrite(to, content)
    }

    /** Only visible note files are indexed; service files like .aiignore are not notes. */
    private fun isIndexed(path: String): Boolean = VaultPaths.isNoteFile(path) && !VaultPaths.isHidden(path)

    private suspend fun updateCacheAfterWrite(path: String, content: String): NoteMeta {
        val entry = store.stat(path) ?: FileEntry(path, false, System.currentTimeMillis(), content.length.toLong())
        val meta = buildMeta(entry, content)
        if (path.equals(AiIgnore.FILE_NAME, ignoreCase = true)) aiIgnore = AiIgnore.parse(content)
        if (!isIndexed(path)) {
            publish(loaded = _index.value.loaded)
            return meta
        }
        cache[path] = Cached(meta, content)
        val parent = VaultPaths.parent(path)
        if (parent.isNotEmpty() && parent !in folders) folders = (folders + parent).sorted()
        publish(loaded = _index.value.loaded)
        return meta
    }

    private suspend fun uniquePath(desired: String): String {
        var candidate = VaultPaths.normalize(desired)
        val parent = VaultPaths.parent(candidate)
        val base = VaultPaths.baseName(candidate)
        val ext = VaultPaths.extension(candidate).let { if (it.isEmpty()) "" else ".$it" }
        var counter = 2
        while (store.stat(candidate) != null) {
            candidate = VaultPaths.join(parent, "$base $counter$ext")
            counter++
        }
        return candidate
    }

    // --- privacy ----------------------------------------------------------------------------

    /** True if the assistant must never see [path] (see [AiIgnore]). */
    fun isHiddenFromAi(path: String, isDirectory: Boolean = false): Boolean = aiIgnore.isHidden(path, isDirectory)

    /** The .aiignore rule hiding [path], for explaining why it cannot simply be un-hidden. */
    fun aiRuleFor(path: String, isDirectory: Boolean = false): AiIgnore.Rule? = aiIgnore.matchingRule(path, isDirectory)

    /**
     * Hides or shows a note/folder for the assistant by editing `.aiignore`.
     * Returns a user-facing problem when a broader rule keeps it hidden.
     */
    suspend fun setHiddenFromAi(path: String, isDirectory: Boolean, hidden: Boolean): String? = mutex.withLock {
        val rule = AiIgnore.exactRule(path, isDirectory)
        val current = store.read(AiIgnore.FILE_NAME)
        val updated = if (hidden) AiIgnore.addRule(current, rule) else AiIgnore.removeRule(current, rule)
        if (updated != current) {
            store.write(AiIgnore.FILE_NAME, updated)
            aiIgnore = AiIgnore.parse(updated)
            publish(loaded = _index.value.loaded)
        }
        if (!hidden) {
            aiIgnore.matchingRule(path, isDirectory)?.let { remaining ->
                return@withLock if (remaining.builtIn) {
                    "Файлы ${remaining.pattern} всегда скрыты от AI"
                } else {
                    "Скрыто правилом «${remaining.pattern}» в .aiignore"
                }
            }
        }
        null
    }

    /** Keeps an exact .aiignore rule pointing at a note after it was renamed or moved. */
    private suspend fun followIgnoreRule(from: String, to: String) {
        val current = store.read(AiIgnore.FILE_NAME) ?: return
        val updated = AiIgnore.renameRule(current, from, to) ?: return
        store.write(AiIgnore.FILE_NAME, updated)
        aiIgnore = AiIgnore.parse(updated)
        publish(loaded = _index.value.loaded)
    }

    /** Finds a note for a [[wiki link]] target: exact path, then title match (case-insensitive). */
    fun resolveLink(target: String): NoteMeta? {
        val clean = target.substringBefore('#').substringBefore('|').trim()
        if (clean.isEmpty()) return null
        val asPath = VaultPaths.normalize(if (VaultPaths.isNoteFile(clean)) clean else "$clean.md")
        cache[asPath]?.let { return it.meta }
        val title = VaultPaths.noteTitle(asPath)
        return cache.values.map { it.meta }
            .filter { it.title.equals(title, ignoreCase = true) }
            .minByOrNull { it.path.count { c -> c == '/' } }
    }

    /**
     * Full-text search over the cached index. Every query term must match the
     * title, tags or content (case-insensitive). Titles weigh more than body text.
     */
    fun search(query: String, limit: Int = 50): List<SearchHit> {
        val terms = query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val phrase = query.trim().lowercase()
        val hits = mutableListOf<SearchHit>()
        for ((_, cached) in cache) {
            val meta = cached.meta
            val title = meta.title.lowercase()
            val body = Frontmatter.split(cached.content).second
            val bodyLower = body.lowercase()
            val tagsLower = meta.tags.joinToString(" ") { it.lowercase() }
            var score = 0
            var allMatch = true
            for (term in terms) {
                val inTitle = title.contains(term)
                val inTags = tagsLower.contains(term.removePrefix("#"))
                val count = countOccurrences(bodyLower, term)
                if (!inTitle && !inTags && count == 0) {
                    allMatch = false
                    break
                }
                if (inTitle) score += 30
                if (inTags) score += 15
                score += minOf(count, 10) * 2
            }
            if (!allMatch) continue
            if (title.contains(phrase)) score += 50
            if (title == phrase) score += 50
            hits += SearchHit(meta, snippet(body, bodyLower, terms.first()), score)
        }
        return hits.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.note.lastModified })
            .take(limit)
    }

    private fun countOccurrences(text: String, term: String): Int {
        var count = 0
        var idx = text.indexOf(term)
        while (idx >= 0) {
            count++
            idx = text.indexOf(term, idx + term.length)
        }
        return count
    }

    private fun snippet(body: String, bodyLower: String, term: String, radius: Int = 70): String {
        val idx = bodyLower.indexOf(term)
        if (idx < 0) return MarkdownText.preview(body, radius * 2)
        val start = maxOf(0, idx - radius)
        val end = minOf(body.length, idx + term.length + radius)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < body.length) "…" else ""
        return prefix + body.substring(start, end).replace(Regex("\\s+"), " ").trim() + suffix
    }

    companion object {
        const val DEFAULT_TITLE = "Без названия"
    }
}
