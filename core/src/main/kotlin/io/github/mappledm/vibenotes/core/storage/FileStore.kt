package io.github.mappledm.vibenotes.core.storage

/**
 * Entry inside a vault. [path] is relative to the vault root, uses '/' as separator
 * and never starts with '/'.
 */
data class FileEntry(
    val path: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long,
) {
    val name: String get() = VaultPaths.name(path)
}

/**
 * Minimal file-system abstraction over the user's notes folder.
 *
 * Implementations: [LocalFileStore] (java.io, used for app-private storage and tests)
 * and the Storage Access Framework store in the Android app.
 * All methods are blocking-free for callers: implementations switch to an IO dispatcher.
 */
interface FileStore {
    /** Human readable description of the root, e.g. a folder name. */
    val displayName: String

    /** Lists every file and directory below the root. Hidden entries (".name") are skipped. */
    suspend fun listAll(): List<FileEntry>

    /** Returns file content or null when the file does not exist. */
    suspend fun read(path: String): String?

    /** Writes the whole file, creating it and missing parent directories if needed. */
    suspend fun write(path: String, content: String)

    suspend fun stat(path: String): FileEntry?

    suspend fun createDirectory(path: String)

    /** Moves or renames. Fails with [StorageException] if [to] already exists. */
    suspend fun move(from: String, to: String)

    /** Permanently deletes a file or an empty directory. Returns false if it did not exist. */
    suspend fun delete(path: String): Boolean
}

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

suspend fun FileStore.exists(path: String): Boolean = stat(path) != null
