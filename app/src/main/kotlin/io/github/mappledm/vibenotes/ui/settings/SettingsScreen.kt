package io.github.mappledm.vibenotes.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.data.ThemeMode
import io.github.mappledm.vibenotes.ui.components.ReminderOptions
import io.github.mappledm.vibenotes.ui.components.Selector
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = containerFactory { SettingsViewModel(it) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()

    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var key by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.loadCalendars() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("AI-провайдер")
            Text(
                "Свой ключ (BYOK). По умолчанию DeepSeek; подходит любой OpenAI-совместимый API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = baseUrl.trim().trimEnd('/') == ProviderConfig.DEEPSEEK_BASE_URL,
                    onClick = { baseUrl = ProviderConfig.DEEPSEEK_BASE_URL; model = ProviderConfig.DEEPSEEK_MODEL },
                    label = { Text("DeepSeek") },
                )
                FilterChip(
                    selected = baseUrl.trim().trimEnd('/') == "https://api.openai.com/v1",
                    onClick = { baseUrl = "https://api.openai.com/v1"; model = "gpt-4o-mini" },
                    label = { Text("OpenAI") },
                )
                FilterChip(
                    selected = baseUrl.trim().trimEnd('/') == "https://openrouter.ai/api/v1",
                    onClick = { baseUrl = "https://openrouter.ai/api/v1"; model = "deepseek/deepseek-chat" },
                    label = { Text("OpenRouter") },
                )
            }
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API-ключ") },
                placeholder = { Text(if (hasKey) "Сохранён — введите новый, чтобы заменить" else "sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.saveProvider(baseUrl, model, key); key = "" }) { Text("Сохранить") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.test(baseUrl, model, key) }, enabled = !vm.testing) { Text("Проверить") }
                if (hasKey) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = vm::removeKey) { Text("Удалить ключ") }
                }
            }
            if (vm.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            vm.testResult?.let { (ok, message) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        null,
                        Modifier.size(18.dp),
                        tint = if (ok) LocalExtraColors.current.success else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Ключ шифруется ключом из Android Keystore и не попадает в логи.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Сообщений истории в запросе: ${settings.historySize}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = settings.historySize.toFloat(),
                onValueChange = { vm.setHistorySize(it.toInt()) },
                valueRange = 0f..20f,
                steps = 19,
            )

            Section("Что отправляется AI")
            Text(
                "• ваше сообщение и последние сообщения чата (число выше);\n" +
                    "• служебный контекст: дата и время, названия досок и колонок, папок и календарей;\n" +
                    "• заметки, задачи и события — только когда ассистент их ищет или читает. " +
                    "Каждое такое действие видно в чате над ответом.\n" +
                    "Все заметки целиком никогда не отправляются. Без ключа приложение работает полностью офлайн.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Календарь")
            if (!vm.calendarAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Нет доступа к календарю", modifier = Modifier.weight(1f))
                    TextButton(onClick = { permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS) }) { Text("Разрешить") }
                }
            } else if (calendars.isNotEmpty()) {
                Text("Календарь для новых событий", style = MaterialTheme.typography.labelLarge)
                val options = listOf<Pair<Long?, String>>(null to "Основной") + calendars.map { it.id to "${it.name} (${it.accountName})" }
                Selector(options, settings.defaultCalendarId, vm::setDefaultCalendar)
            }
            Text("Длительность события по умолчанию", style = MaterialTheme.typography.labelLarge)
            Selector(listOf(15, 30, 45, 60, 90, 120).map { it to "$it мин" }, settings.eventMinutes, vm::setEventMinutes)
            Text("Напоминание по умолчанию", style = MaterialTheme.typography.labelLarge)
            Selector(ReminderOptions.map { it to reminderLabel(it) }, settings.reminderMinutes, vm::setReminder)

            Section("Хранилище")
            Text("Сейчас: ${vm.vaultName}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Заметки — обычные .md файлы. Выберите папку, которую синхронизирует Syncthing, Google Drive или Obsidian, — и они будут видны на ПК.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::useInternalStorage) { Text("Внутри приложения") }
            }

            Section("Оформление")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (mode, label) ->
                    FilterChip(selected = settings.theme == mode, onClick = { vm.setTheme(mode) }, label = { Text(label) })
                }
            }

            Section("Данные")
            TextButton(onClick = { confirmClear = true }) { Text("Очистить историю чата") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "VibeNotes — свободное ПО (GPL-3.0). Без аккаунтов, серверов и телеметрии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю чата?") },
            text = { Text("Заметки, задачи и события не затрагиваются.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearChat() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}
