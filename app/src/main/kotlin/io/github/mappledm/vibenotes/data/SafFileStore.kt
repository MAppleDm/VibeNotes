package io.github.mappledm.vibenotes.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.mappledm.vibenotes.core.storage.FileEntry
import io.github.mappledm.vibenotes.core.storage.FileStore
import io.github.mappledm.vibenotes.core.storage.StorageException
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * [FileStore] over a folder picked with the Storage Access Framework.
 *
 * Uses DocumentsContract queries directly (one query per directory) instead of
 * DocumentFile, which issues a query per property and is very slow on big folders.
 */
class SafFileStore(context: Context, private val treeUri: Uri) : FileStore {
    private val resolver: ContentResolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    /** path -> documentId. Rebuilt by [listAll], invalidated on structural changes. */
    private val ids = ConcurrentHashMap<String, String>()

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val lastModified: Long, val size: Long)

    override val displayName: String by lazy {
        runCatching { query(docUri(rootDocId))?.name }.getOrNull()
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null }
            ?: "Папка"
    }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_SIZE,
    )

    private fun children(parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val result = mutableListOf<Child>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                result += Child(
                    docId = c.getString(0),
                    name = name,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return result
    }

    private fun query(uri: Uri): Child? =
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Child(
                docId = c.getString(0),
                name = c.getString(1) ?: "",
                isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                size = if (c.isNull(4)) 0L else c.getLong(4),
            )
        }

    /** Resolves a vault path to a document id by walking from the root (cached). */
    private fun findDocId(path: String): String? {
        val normalized = VaultPaths.normalize(path)
        if (normalized.isEmpty()) return rootDocId
        ids[normalized]?.let { return it }
        var parentId = rootDocId
        var currentPath = ""
        for (segment in normalized.split('/')) {
            currentPath = VaultPaths.join(currentPath, segment)
            val cached = ids[currentPath]
            parentId = cached ?: children(parentId).firstOrNull { it.name == segment }?.docId?.also { ids[currentPath] = it } ?: return null
        }
        return parentId
    }

    private fun ensureDirectory(path: String): String {
        val normalized = VaultPaths.normalize(path)
        if (normalized.isEmpty()) return rootDocId
        findDocId(normalized)?.let { return it }
        val parentId = ensureDirectory(VaultPaths.parent(normalized))
        val created = DocumentsContract.createDocument(resolver, docUri(parentId), Document.MIME_TYPE_DIR, VaultPaths.name(normalized))
            ?: throw StorageException("Не удалось создать папку ${VaultPaths.name(normalized)}")
        return DocumentsContract.getDocumentId(created).also { ids[normalized] = it }
    }

    private inline fun <T> guarded(action: String, block: () -> T): T = try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: SecurityException) {
        throw StorageException("Нет доступа к папке. Выберите её заново в настройках.", e)
    } catch (e: FileNotFoundException) {
        ids.clear()
        throw StorageException("$action: файл не найден", e)
    } catch (e: Exception) {
        throw StorageException("$action: ${e.message ?: e.javaClass.simpleName}", e)
    }

    override suspend fun listAll(): List<FileEntry> = withContext(Dispatchers.IO) {
        guarded("Чтение папки") {
            ids.clear()
            val result = mutableListOf<FileEntry>()
            fun walk(docId: String, prefix: String) {
                for (child in children(docId)) {
                    if (child.name.startsWith(".")) continue
                    val path = VaultPaths.join(prefix, child.name)
                    ids[path] = child.docId
                    result += FileEntry(path, child.isDir, child.lastModified, child.size)
                    if (child.isDir) walk(child.docId, path)
                }
            }
            walk(rootDocId, "")
            result
        }
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        guarded("Чтение ${VaultPaths.name(path)}") {
            val id = findDocId(path) ?: return@guarded null
            try {
                resolver.openInputStream(docUri(id))?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: FileNotFoundException) {
                // Stale cache entry: the file was removed or renamed outside the app.
                ids.remove(VaultPaths.normalize(path))
                val fresh = findDocId(path) ?: return@guarded null
                resolver.openInputStream(docUri(fresh))?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    }

    override suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        guarded("Сохранение ${VaultPaths.name(path)}") {
            val normalized = VaultPaths.normalize(path)
            val id = findDocId(normalized) ?: run {
                val parentId = ensureDirectory(VaultPaths.parent(normalized))
                val created = DocumentsContract.createDocument(resolver, docUri(parentId), mimeFor(normalized), VaultPaths.name(normalized))
                    ?: throw StorageException("Не удалось создать файл ${VaultPaths.name(normalized)}")
                DocumentsContract.getDocumentId(created).also { ids[normalized] = it }
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            val stream = try {
                resolver.openOutputStream(docUri(id), "wt")
            } catch (e: IllegalArgumentException) {
                // Some providers do not support the truncate flag.
                resolver.openOutputStream(docUri(id), "w")
            } ?: throw StorageException("Не удалось открыть файл для записи")
            stream.use { it.write(bytes) }
        }
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        guarded("Чтение ${VaultPaths.name(path)}") {
            val normalized = VaultPaths.normalize(path)
            val id = findDocId(normalized) ?: return@guarded null
            val child = try {
                query(docUri(id))
            } catch (e: Exception) {
                ids.remove(normalized)
                null
            } ?: return@guarded null
            FileEntry(normalized, child.isDir, child.lastModified, child.size)
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        guarded("Создание папки") { ensureDirectory(path) }
        Unit
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        guarded("Перемещение ${VaultPaths.name(from)}") {
            val source = VaultPaths.normalize(from)
            val target = VaultPaths.normalize(to)
            val sourceId = findDocId(source) ?: throw StorageException("Файл не найден: ${VaultPaths.name(source)}")
            val caseOnlyRename = source.equals(target, ignoreCase = true)
            if (!caseOnlyRename && findDocId(target) != null) throw StorageException("Файл уже существует: ${VaultPaths.name(target)}")

            val sourceParent = VaultPaths.parent(source)
            val targetParent = VaultPaths.parent(target)
            var currentUri = docUri(sourceId)
            if (sourceParent != targetParent) {
                val fromParentId = findDocId(sourceParent) ?: rootDocId
                val toParentId = ensureDirectory(targetParent)
                val moved = runCatching {
                    DocumentsContract.moveDocument(resolver, currentUri, docUri(fromParentId), docUri(toParentId))
                }.getOrNull()
                if (moved != null) {
                    currentUri = moved
                } else {
                    // Provider cannot move: copy, then delete the original.
                    val content = resolver.openInputStream(currentUri)?.use { it.readBytes() }
                        ?: throw StorageException("Не удалось прочитать ${VaultPaths.name(source)}")
                    val created = DocumentsContract.createDocument(resolver, docUri(toParentId), mimeFor(target), VaultPaths.name(source))
                        ?: throw StorageException("Не удалось создать копию")
                    resolver.openOutputStream(created, "w")?.use { it.write(content) }
                        ?: throw StorageException("Не удалось записать копию")
                    DocumentsContract.deleteDocument(resolver, currentUri)
                    currentUri = created
                }
            }
            if (VaultPaths.name(source) != VaultPaths.name(target)) {
                DocumentsContract.renameDocument(resolver, currentUri, VaultPaths.name(target))
                    ?: throw StorageException("Не удалось переименовать файл")
            }
            ids.clear()
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        guarded("Удаление ${VaultPaths.name(path)}") {
            val id = findDocId(path) ?: return@guarded false
            val deleted = DocumentsContract.deleteDocument(resolver, docUri(id))
            ids.clear()
            deleted
        }
    }

    private fun mimeFor(path: String): String =
        if (VaultPaths.isMarkdown(path)) "text/markdown" else "application/octet-stream"

    companion object {
        /** True if the app still holds read+write permission for [uri]. */
        fun hasPermission(context: Context, uri: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }
}
