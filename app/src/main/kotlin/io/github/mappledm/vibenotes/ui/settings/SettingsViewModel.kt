package io.github.mappledm.vibenotes.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.agent.DataAccess
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmException
import io.github.mappledm.vibenotes.core.ai.LlmMessage
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.notes.VaultIndex
import io.github.mappledm.vibenotes.core.privacy.AiIgnore
import androidx.compose.ui.text.font.FontFamily
import io.github.mappledm.vibenotes.data.AppFont
import io.github.mappledm.vibenotes.data.AppSettings
import io.github.mappledm.vibenotes.data.Palette
import io.github.mappledm.vibenotes.data.StorageLocation
import io.github.mappledm.vibenotes.data.ThemeMode
import io.github.mappledm.vibenotes.data.UpdateState
import io.github.mappledm.vibenotes.ui.theme.appFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.state
    val hasKey: StateFlow<Boolean> = container.secrets.hasApiKey
    val vaultName: String = container.vault.value?.name ?: "—"
    val index: StateFlow<VaultIndex>? = container.vault.value?.notes?.index

    var testing by mutableStateOf(false)
        private set
    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    val calendarAllowed: Boolean get() = container.calendar.hasPermission()

    val update: StateFlow<UpdateState> = container.updater.state

    init {
        loadCalendars()
    }

    fun loadCalendars() {
        if (!container.calendar.hasPermission()) return
        viewModelScope.launch {
            _calendars.value = runCatching { container.calendar.writableCalendars() }.getOrDefault(emptyList())
        }
    }

    // --- AI provider --------------------------------------------------------------------

    fun saveProvider(baseUrl: String, model: String, newKey: String) {
        container.settings.setProvider(baseUrl, model)
        if (newKey.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { container.secrets.saveApiKey(newKey) }
        }
        testResult = null
        notice = "Сохранено"
    }

    fun removeKey() {
        container.secrets.clearApiKey()
        testResult = null
    }

    /** Sends a tiny request to check URL, model and key. Uses the typed key if present. */
    fun test(baseUrl: String, model: String, typedKey: String) {
        if (testing) return
        testing = true
        testResult = null
        viewModelScope.launch {
            val key = typedKey.trim().ifEmpty { withContext(Dispatchers.IO) { container.secrets.apiKey() } }
            testResult = if (key.isNullOrBlank()) {
                false to "Введите API-ключ"
            } else {
                try {
                    val config = ProviderConfig(baseUrl.trim().ifEmpty { ProviderConfig.DEEPSEEK_BASE_URL }, model.trim().ifEmpty { ProviderConfig.DEEPSEEK_MODEL }, key)
                    var answered = false
                    container.llm.stream(config, LlmRequest(listOf(LlmMessage(Role.USER, "Ответь одним словом: ок")), maxTokens = 5))
                        .collect { if (it is LlmEvent.Completed) answered = true }
                    if (answered) true to "Подключение работает" else false to "Сервер не вернул ответ"
                } catch (e: LlmException) {
                    false to (e.message ?: "Ошибка")
                } catch (e: Exception) {
                    false to "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            testing = false
        }
    }

    // --- simple settings ----------------------------------------------------------------

    fun setTheme(mode: ThemeMode) = container.settings.setTheme(mode)
    fun setPalette(palette: Palette) = container.settings.setPalette(palette)
    fun setFont(font: AppFont) = container.settings.setFont(font)
    fun fontFamily(font: AppFont): FontFamily = appFontFamily(font, container.fonts)

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.fonts.import(uri) } }
                .onSuccess { name ->
                    container.settings.setCustomFont(name)
                    notice = "Шрифт «$name» включён"
                }
                .onFailure { notice = it.message ?: "Не удалось загрузить шрифт" }
        }
    }

    fun removeCustomFont() {
        container.fonts.delete()
        container.settings.clearCustomFont()
    }
    fun setDefaultCalendar(id: Long?) = container.settings.setDefaultCalendar(id)
    fun setEventMinutes(value: Int) = container.settings.setEventMinutes(value)
    fun setReminder(value: Int?) = container.settings.setReminderMinutes(value)
    fun setDefaultBoard(path: String) = container.settings.setDefaultBoard(path)
    fun setInboxNote(path: String) {
        val clean = path.trim().ifEmpty { return }
        container.settings.setInboxNote(if (clean.endsWith(".md")) clean else "$clean.md")
        notice = "Сохранено"
    }

    fun chooseFolder(uri: Uri) = container.chooseStorage(StorageLocation.Folder(uri.toString()))
    fun useInternalStorage() = container.chooseStorage(StorageLocation.Internal)

    fun reindex() {
        val notes = container.vault.value?.notes ?: return
        viewModelScope.launch {
            notice = runCatching { notes.reindex() }
                .fold({ "Файлы перечитаны: ${it.notes.size} заметок" }, { "Не удалось: ${it.message}" })
        }
    }

    /**
     * Opens a file the app relies on in the editor, creating it from [template] if it is missing.
     * [onCreated] runs only when the file did not exist yet.
     */
    private fun openFile(path: String, template: String, onCreated: () -> Unit = {}, onReady: (String) -> Unit) {
        val notes = container.vault.value?.notes ?: return
        viewModelScope.launch {
            runCatching {
                val missing = notes.read(path) == null
                if (missing) notes.save(path, template)
                missing
            }
                .onSuccess { created ->
                    if (created) onCreated()
                    onReady(path)
                }
                .onFailure { notice = it.message ?: "Не удалось открыть файл" }
        }
    }

    fun openAiIgnore(onReady: (String) -> Unit) = openFile(AiIgnore.FILE_NAME, AiIgnore.HEADER + "\n", onReady = onReady)

    fun openInbox(onReady: (String) -> Unit) = openFile(settings.value.inboxNote, "", onReady = onReady)

    fun openBoardFile(onReady: (String) -> Unit) {
        val kanban = container.vault.value?.kanban ?: return
        viewModelScope.launch {
            runCatching { kanban.ensureBoard(settings.value.defaultBoard) }
                .onSuccess(onReady)
                .onFailure { notice = it.message ?: "Не удалось открыть доску" }
        }
    }

    /** A new instructions file is switched on right away: writing it is the point. */
    fun openInstructions(onReady: (String) -> Unit) = openFile(
        settings.value.instructionsFile,
        """
        # Инструкции для ассистента

        Этот файл ассистент читает перед каждым ответом (выключается в «Настройки → Ассистент»).
        Пишите правила и факты о себе обычным языком, например:

        - Мой рабочий день: 9:00–18:00, по выходным встречи не ставь.
        - «Врач» — это поликлиника №3 на Кирова, 38.
        - Встречи с Андреем всегда по 30 минут.
        - Покупки клади на доску «Покупки».
        """.trimIndent() + "\n",
        onCreated = { container.settings.setUseInstructions(true) },
        onReady = onReady,
    )

    // --- assistant ----------------------------------------------------------------------

    fun setHistorySize(value: Int) = container.settings.setHistorySize(value)
    fun setAccess(access: DataAccess, enabled: Boolean) = container.settings.setToolsEnabled(access.tools, enabled)
    fun setAutoApply(kind: String, enabled: Boolean) = container.settings.setAutoApply(kind, enabled)
    fun setUseInstructions(value: Boolean) = container.settings.setUseInstructions(value)
    fun setAiPreviews(value: Boolean) = container.settings.setAiPreviews(value)
    fun setAutoUpdateCheck(value: Boolean) = container.settings.setAutoUpdateCheck(value)
    fun setTaskReminderEnabled(value: Boolean) = container.settings.setTaskReminderEnabled(value)
    fun setTaskReminderDay(value: Int) = container.settings.setTaskReminderDay(value)
    fun setTaskReminderTime(hour: Int, minute: Int) = container.settings.setTaskReminderTime(hour, minute)
    fun showNotice(text: String) {
        notice = text
    }

    fun clearChat() {
        viewModelScope.launch {
            container.vault.value?.inbox?.clear()
            container.settings.setChatClearedDay(LocalDate.now().toEpochDay())
            notice = "История чата очищена"
        }
    }

    fun noticeShown(shown: String) {
        if (notice == shown) notice = null
    }

    val appVersion: String = container.updater.currentVersionName
}
