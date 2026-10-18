package io.github.mappledm.vibenotes.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The user's own font, copied into app storage so it keeps working without access to the
 * original file. One font at a time: importing a new one replaces the old.
 */
class CustomFontStore(private val context: Context) {
    private val file = File(context.filesDir, "fonts/custom.ttf")

    @Volatile
    private var cached: Pair<Long, Typeface>? = null

    /**
     * Copies a .ttf/.otf from [uri] and returns its display name.
     * Throws [IllegalArgumentException] with a user-facing message if it is not a usable font.
     */
    fun import(uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Свой шрифт"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = input.readNBytesCompat(MAX_SIZE + 1)
            require(buffer.size <= MAX_SIZE) { "Файл больше ${MAX_SIZE / 1024 / 1024} МБ — это точно шрифт?" }
            buffer
        } ?: throw IllegalArgumentException("Не удалось открыть файл")
        require(looksLikeFont(bytes)) { "«$name» — не файл шрифта. Нужен .ttf или .otf" }

        val temp = File(file.parentFile, "import.tmp")
        file.parentFile?.mkdirs()
        temp.writeBytes(bytes)
        // Android quietly falls back to the default typeface when it cannot parse a font.
        val typeface = runCatching { Typeface.createFromFile(temp) }.getOrNull()
        if (typeface == null || typeface == Typeface.DEFAULT) {
            temp.delete()
            throw IllegalArgumentException("Android не смог прочитать «$name»")
        }
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Не удалось сохранить шрифт" }
        }
        cached = null
        return name.substringBeforeLast('.').ifBlank { name }
    }

    /** The imported font, or null if there is none or it can no longer be read. */
    fun typeface(): Typeface? {
        if (!file.exists()) return null
        val stamp = file.lastModified() xor file.length()
        cached?.let { (key, typeface) -> if (key == stamp) return typeface }
        val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
            ?.takeIf { it != Typeface.DEFAULT } ?: return null
        cached = stamp to typeface
        return typeface
    }

    fun delete() {
        file.delete()
        cached = null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_SIZE = 30 * 1024 * 1024

        /** TrueType, OpenType (CFF) or a font collection, by the first four bytes. */
        fun looksLikeFont(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val tag = String(bytes, 0, 4, Charsets.ISO_8859_1)
            val trueType = bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
            return trueType || tag == "OTTO" || tag == "true" || tag == "ttcf"
        }
    }
}
