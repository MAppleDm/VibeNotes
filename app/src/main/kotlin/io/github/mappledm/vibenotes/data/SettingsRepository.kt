package io.github.mappledm.vibenotes.data

import android.content.Context
import android.content.SharedPreferences
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Palette { LEMON_COFFEE, GRAPHITE }

/** The interface font. CUSTOM is a .ttf/.otf the user imported (see CustomFontStore). */
enum class AppFont { GOLOS, SYSTEM, CUSTOM }

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
    val palette: Palette,
    val font: AppFont,
    /** Name of the imported font file, null if none was imported. */
    val customFontName: String?,
    /** Changes on every import, so the UI reloads a replaced font file. */
    val customFontVersion: Long,
    val pinned: Set<String>,
    // --- how the assistant behaves ---
    val disabledTools: Set<String>,
    /** ProposalKind names applied without a confirmation card. */
    val autoApply: Set<String>,
    val useInstructions: Boolean,
    val instructionsFile: String,
    // --- updates from GitHub ---
    val autoUpdateCheck: Boolean,
    val lastUpdateCheck: Long,
    /** versionCode the user postponed with "Позже"; the banner stays hidden for it. */
    val dismissedUpdate: Long,
    /** Epoch day when the chat was last cleared automatically (keeps chat to one day). */
    val chatClearedDay: Long? = null,
    // --- weekly task reminder ---
    val taskReminderEnabled: Boolean = false,
    /** ISO day of week, 1 = Monday. */
    val taskReminderDay: Int = 1,
    val taskReminderHour: Int = 10,
    val taskReminderMinute: Int = 0,
    /** AI-written descriptions instead of the first lines in the note list. */
    val aiPreviews: Boolean = true,
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
        palette = runCatching { Palette.valueOf(prefs.getString(KEY_PALETTE, null) ?: "LEMON_COFFEE") }.getOrDefault(Palette.LEMON_COFFEE),
        font = runCatching { AppFont.valueOf(prefs.getString(KEY_FONT, null) ?: "GOLOS") }.getOrDefault(AppFont.GOLOS),
        customFontName = prefs.getString(KEY_CUSTOM_FONT_NAME, null),
        customFontVersion = prefs.getLong(KEY_CUSTOM_FONT_VERSION, 0L),
        pinned = prefs.getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet(),
        disabledTools = prefs.getStringSet(KEY_DISABLED_TOOLS, emptySet())?.toSet() ?: emptySet(),
        autoApply = prefs.getStringSet(KEY_AUTO_APPLY, emptySet())?.toSet() ?: emptySet(),
        useInstructions = prefs.getBoolean(KEY_USE_INSTRUCTIONS, false),
        instructionsFile = prefs.getString(KEY_INSTRUCTIONS_FILE, null) ?: DEFAULT_INSTRUCTIONS,
        autoUpdateCheck = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        lastUpdateCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L),
        dismissedUpdate = prefs.getLong(KEY_DISMISSED_UPDATE, 0L),
        chatClearedDay = prefs.getLong(KEY_CHAT_CLEARED, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
        taskReminderEnabled = prefs.getBoolean(KEY_TASK_REMINDER, false),
        taskReminderDay = prefs.getInt(KEY_TASK_REMINDER_DAY, 1).coerceIn(1, 7),
        taskReminderHour = prefs.getInt(KEY_TASK_REMINDER_HOUR, 10).coerceIn(0, 23),
        taskReminderMinute = prefs.getInt(KEY_TASK_REMINDER_MINUTE, 0).coerceIn(0, 59),
        aiPreviews = prefs.getBoolean(KEY_AI_PREVIEWS, true),
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
    fun setPalette(palette: Palette) = edit { putString(KEY_PALETTE, palette.name) }
    fun setFont(font: AppFont) = edit { putString(KEY_FONT, font.name) }

    fun setCustomFont(name: String) = edit {
        putString(KEY_CUSTOM_FONT_NAME, name)
        putLong(KEY_CUSTOM_FONT_VERSION, System.currentTimeMillis())
        putString(KEY_FONT, AppFont.CUSTOM.name)
    }

    fun clearCustomFont() = edit {
        remove(KEY_CUSTOM_FONT_NAME)
        putLong(KEY_CUSTOM_FONT_VERSION, System.currentTimeMillis())
        if (current.font == AppFont.CUSTOM) putString(KEY_FONT, AppFont.GOLOS.name)
    }

    fun setToolsEnabled(names: Set<String>, enabled: Boolean) = edit {
        putStringSet(KEY_DISABLED_TOOLS, if (enabled) current.disabledTools - names else current.disabledTools + names)
    }
    fun setAutoApply(kind: String, enabled: Boolean) = edit {
        putStringSet(KEY_AUTO_APPLY, if (enabled) current.autoApply + kind else current.autoApply - kind)
    }
    fun setUseInstructions(value: Boolean) = edit { putBoolean(KEY_USE_INSTRUCTIONS, value) }
    fun setAiPreviews(value: Boolean) = edit { putBoolean(KEY_AI_PREVIEWS, value) }
    fun setAutoUpdateCheck(value: Boolean) = edit { putBoolean(KEY_AUTO_UPDATE, value) }
    fun setLastUpdateCheck(millis: Long) = edit { putLong(KEY_LAST_UPDATE_CHECK, millis) }
    fun setDismissedUpdate(versionCode: Long) = edit { putLong(KEY_DISMISSED_UPDATE, versionCode) }
    fun setChatClearedDay(epochDay: Long) = edit { putLong(KEY_CHAT_CLEARED, epochDay) }
    fun setTaskReminderEnabled(value: Boolean) = edit { putBoolean(KEY_TASK_REMINDER, value) }
    fun setTaskReminderDay(value: Int) = edit { putInt(KEY_TASK_REMINDER_DAY, value.coerceIn(1, 7)) }
    fun setTaskReminderTime(hour: Int, minute: Int) = edit {
        putInt(KEY_TASK_REMINDER_HOUR, hour.coerceIn(0, 23))
        putInt(KEY_TASK_REMINDER_MINUTE, minute.coerceIn(0, 59))
    }

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
        const val DEFAULT_INSTRUCTIONS = "AGENTS.md"
        private const val KEY_DISABLED_TOOLS = "ai_disabled_tools"
        private const val KEY_AUTO_APPLY = "ai_auto_apply"
        private const val KEY_USE_INSTRUCTIONS = "ai_use_instructions"
        private const val KEY_INSTRUCTIONS_FILE = "ai_instructions_file"
        private const val KEY_AI_PREVIEWS = "ai_note_previews"
        private const val KEY_CHAT_CLEARED = "chat_cleared_day"
        private const val KEY_TASK_REMINDER = "task_reminder"
        private const val KEY_TASK_REMINDER_DAY = "task_reminder_day"
        private const val KEY_TASK_REMINDER_HOUR = "task_reminder_hour"
        private const val KEY_TASK_REMINDER_MINUTE = "task_reminder_minute"
        private const val KEY_AUTO_UPDATE = "update_auto_check"
        private const val KEY_LAST_UPDATE_CHECK = "update_last_check"
        private const val KEY_DISMISSED_UPDATE = "update_dismissed"
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
        private const val KEY_PALETTE = "palette"
        private const val KEY_FONT = "font"
        private const val KEY_CUSTOM_FONT_NAME = "font_custom_name"
        private const val KEY_CUSTOM_FONT_VERSION = "font_custom_version"
        private const val KEY_PINNED = "pinned"
    }
}
