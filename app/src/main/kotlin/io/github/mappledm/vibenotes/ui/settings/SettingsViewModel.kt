package io.github.mappledm.vibenotes.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.ai.LlmEvent
import io.github.mappledm.vibenotes.core.ai.LlmException
import io.github.mappledm.vibenotes.core.ai.LlmMessage
import io.github.mappledm.vibenotes.core.ai.LlmRequest
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.ai.Role
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.data.AppSettings
import io.github.mappledm.vibenotes.data.StorageLocation
import io.github.mappledm.vibenotes.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.state
    val hasKey: StateFlow<Boolean> = container.secrets.hasApiKey
    val vaultName: String = container.vault.value?.name ?: "—"

    var testing by mutableStateOf(false)
        private set
    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    val calendarAllowed: Boolean get() = container.calendar.hasPermission()

    init {
        loadCalendars()
    }

    fun loadCalendars() {
        if (!container.calendar.hasPermission()) return
        viewModelScope.launch {
            _calendars.value = runCatching { container.calendar.writableCalendars() }.getOrDefault(emptyList())
        }
    }

    fun saveProvider(baseUrl: String, model: String, newKey: String) {
        container.settings.setProvider(baseUrl, model)
        if (newKey.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { container.secrets.saveApiKey(newKey) }
        }
        testResult = null
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

    fun setHistorySize(value: Int) = container.settings.setHistorySize(value)
    fun setTheme(mode: ThemeMode) = container.settings.setTheme(mode)
    fun setDefaultCalendar(id: Long?) = container.settings.setDefaultCalendar(id)
    fun setEventMinutes(value: Int) = container.settings.setEventMinutes(value)
    fun setReminder(value: Int?) = container.settings.setReminderMinutes(value)

    fun chooseFolder(uri: Uri) = container.chooseStorage(StorageLocation.Folder(uri.toString()))
    fun useInternalStorage() = container.chooseStorage(StorageLocation.Internal)

    fun clearChat() {
        viewModelScope.launch { container.vault.value?.inbox?.clear() }
    }
}
