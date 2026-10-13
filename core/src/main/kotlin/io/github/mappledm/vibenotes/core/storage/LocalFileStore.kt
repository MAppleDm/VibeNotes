package io.github.mappledm.vibenotes.core.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** [FileStore] backed by a regular directory. */
class LocalFileStore(
    private val root: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileStore {

    override val displayName: String get() = root.name

    private fun resolve(path: String): File {
        val normalized = VaultPaths.normalize(path)
        if (normalized.split('/').any { it == ".." }) throw StorageException("Недопустимый путь: $path")
        return if (normalized.isEmpty()) root else File(root, normalized)
    }

    override suspend fun listAll(): List<FileEntry> = withContext(dispatcher) {
        if (!root.exists()) return@withContext emptyList()
        val result = mutableListOf<FileEntry>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                result += FileEntry(path, child.isDirectory, child.lastModified(), if (child.isDirectory) 0 else child.length())
                if (child.isDirectory) walk(child, path)
            }
        }
        walk(root, "")
        result
    }

    override suspend fun read(path: String): String? = withContext(dispatcher) {
        val file = resolve(path)
        if (!file.isFile) null else file.readText(Charsets.UTF_8)
    }

    override suspend fun write(path: String, content: String) = withContext(dispatcher) {
        val file = resolve(path)
        try {
            file.parentFile?.mkdirs()
            // Write to a temp file first so a crash never leaves a half-written note.
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            throw StorageException("Не удалось сохранить файл ${VaultPaths.name(path)}", e)
        }
    }

    override suspend fun stat(path: String): FileEntry? = withContext(dispatcher) {
        val file = resolve(path)
        if (!file.exists()) null
        else FileEntry(VaultPaths.normalize(path), file.isDirectory, file.lastModified(), if (file.isDirectory) 0 else file.length())
    }

    override suspend fun createDirectory(path: String) = withContext(dispatcher) {
        val dir = resolve(path)
        if (!dir.isDirectory && !dir.mkdirs()) throw StorageException("Не удалось создать папку ${VaultPaths.name(path)}")
    }

    override suspend fun move(from: String, to: String) = withContext(dispatcher) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) throw StorageException("Файл не найден: ${VaultPaths.name(from)}")
        if (target.exists() && !source.canonicalPath.equals(target.canonicalPath, ignoreCase = false)) {
            throw StorageException("Файл уже существует: ${VaultPaths.name(to)}")
        }
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = false)
            source.deleteRecursively()
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(dispatcher) {
        val file = resolve(path)
        if (!file.exists()) false else file.delete()
    }
}
