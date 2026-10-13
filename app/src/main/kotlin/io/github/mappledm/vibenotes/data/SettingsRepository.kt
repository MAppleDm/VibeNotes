package io.github.mappledm.vibenotes.data

import android.content.Context
import android.content.SharedPreferences
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Where the user's Markdown files live. */
sealed interface StorageLocation {
    /** A folder chosen through the Storage Access Framework (can be synced to a PC). */
    data class Folder(val treeUri: String) : StorageLocation

    /** App-private storage; simplest, but invisible to other apps. */
    data object Internal : StorageLocation

    fun encode(): String = when (this) {
        is Folder -> "saf:$treeUri"
        Internal -> "internal"
    }

    companion object {
        fun decode(raw: String?): StorageLocation? = when {
            raw == null -> null
            raw == "internal" -> Internal
            raw.startsWith("saf:") -> Folder(raw.removePrefix("saf:"))
            else -> null
        }
    }
}

data class AppSettings(
    val storage: StorageLocation?,
    val baseUrl: String,
    val model: String,
    val historySize: Int,
    val defaultCalendarId: Long?,
    val eventMinutes: Int,
    /** null = no reminder. */
    val reminderMinutes: Int?,
    val defaultBoard: String,
    val inboxNote: String,
    val theme: ThemeMode,
    val pinned: Set<String>,
)

/** Small key-value settings; SharedPreferences is enough and has no extra dependencies. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        storage = StorageLocation.decode(prefs.getString(KEY_STORAGE, null)),
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: ProviderConfig.DEEPSEEK_BASE_URL,
        model = prefs.getString(KEY_MODEL, null) ?: ProviderConfig.DEEPSEEK_MODEL,
        historySize = prefs.getInt(KEY_HISTORY, 10),
        defaultCalendarId = prefs.getLong(KEY_CALENDAR, -1L).takeIf { it >= 0 },
        eventMinutes = prefs.getInt(KEY_EVENT_MINUTES, 60),
        reminderMinutes = prefs.getInt(KEY_REMINDER, 30).takeIf { it >= 0 },
        defaultBoard = prefs.getString(KEY_BOARD, null) ?: DEFAULT_BOARD,
        inboxNote = prefs.getString(KEY_INBOX, null) ?: DEFAULT_INBOX,
        theme = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        pinned = prefs.getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet(),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setStorage(location: StorageLocation?) = edit {
        if (location == null) remove(KEY_STORAGE) else putString(KEY_STORAGE, location.encode())
    }

    fun setProvider(baseUrl: String, model: String) = edit {
        putString(KEY_BASE_URL, baseUrl.trim().ifEmpty { ProviderConfig.DEEPSEEK_BASE_URL })
        putString(KEY_MODEL, model.trim().ifEmpty { ProviderConfig.DEEPSEEK_MODEL })
    }

    fun setHistorySize(value: Int) = edit { putInt(KEY_HISTORY, value.coerceIn(0, 40)) }
    fun setDefaultCalendar(id: Long?) = edit { putLong(KEY_CALENDAR, id ?: -1L) }
    fun setEventMinutes(value: Int) = edit { putInt(KEY_EVENT_MINUTES, value.coerceIn(5, 24 * 60)) }
    fun setReminderMinutes(value: Int?) = edit { putInt(KEY_REMINDER, value ?: -1) }
    fun setDefaultBoard(path: String) = edit { putString(KEY_BOARD, path) }
    fun setInboxNote(path: String) = edit { putString(KEY_INBOX, path) }
    fun setTheme(mode: ThemeMode) = edit { putString(KEY_THEME, mode.name) }

    fun setPinned(path: String, pinned: Boolean) = edit {
        val updated = current.pinned.toMutableSet().apply { if (pinned) add(path) else remove(path) }
        putStringSet(KEY_PINNED, updated)
    }

    fun renamePinned(from: String, to: String) {
        if (from !in current.pinned) return
        edit { putStringSet(KEY_PINNED, current.pinned - from + to) }
    }

    companion object {
        const val DEFAULT_BOARD = "Задачи.md"
        const val DEFAULT_INBOX = "Входящие.md"
        private const val KEY_STORAGE = "storage"
        private const val KEY_BASE_URL = "ai_base_url"
        private const val KEY_MODEL = "ai_model"
        private const val KEY_HISTORY = "ai_history"
        private const val KEY_CALENDAR = "calendar_default"
        private const val KEY_EVENT_MINUTES = "event_minutes"
        private const val KEY_REMINDER = "reminder_minutes"
        private const val KEY_BOARD = "board_default"
        private const val KEY_INBOX = "inbox_note"
        private const val KEY_THEME = "theme"
        private const val KEY_PINNED = "pinned"
    }
}
