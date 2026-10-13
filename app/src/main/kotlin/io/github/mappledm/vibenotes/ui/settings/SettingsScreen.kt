package io.github.mappledm.vibenotes.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewColumn
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.ToolKind
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.inbox.SlashCommands
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.data.AppSettings
import io.github.mappledm.vibenotes.data.Palette
import io.github.mappledm.vibenotes.data.ThemeMode
import io.github.mappledm.vibenotes.ui.components.ReminderOptions
import io.github.mappledm.vibenotes.ui.components.Selector
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import io.github.mappledm.vibenotes.ui.theme.MonoStyle

/** Settings are grouped like a phone's settings app: a short list, details one tap away. */
enum class SettingsSection(val id: String, val title: String, val icon: ImageVector) {
    AI("ai", "AI-ассистент", Icons.Outlined.AutoAwesome),
    CALENDAR("calendar", "Календарь", Icons.Outlined.CalendarMonth),
    TASKS("tasks", "Задачи и заметки", Icons.Outlined.ViewColumn),
    STORAGE("storage", "Хранилище", Icons.Outlined.Folder),
    PRIVACY("privacy", "Приватность", Icons.Outlined.Lock),
    APPEARANCE("appearance", "Оформление", Icons.Outlined.Palette),
    ADVANCED("advanced", "Для продвинутых", Icons.Outlined.Tune),
    ABOUT("about", "О приложении", Icons.Outlined.Info),
    ;

    companion object {
        fun byId(id: String?): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    section: String?,
    onBack: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = containerFactory { SettingsViewModel(it) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = SettingsSection.byId(section)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            vm.noticeShown()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(current?.title ?: "Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            if (current == null) {
                SettingsHome(vm, settings, onOpenSection)
            } else {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (current) {
                        SettingsSection.AI -> AiSection(vm, settings)
                        SettingsSection.CALENDAR -> CalendarSection(vm, settings)
                        SettingsSection.TASKS -> TasksSection(vm, settings)
                        SettingsSection.STORAGE -> StorageSection(vm)
                        SettingsSection.PRIVACY -> PrivacySection(vm, onOpenNote)
                        SettingsSection.APPEARANCE -> AppearanceSection(vm, settings)
                        SettingsSection.ADVANCED -> AdvancedSection(vm, settings, onOpenNote)
                        SettingsSection.ABOUT -> AboutSection(vm)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsHome(vm: SettingsViewModel, settings: AppSettings, onOpen: (String) -> Unit) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    SettingsSection.entries.forEach { section ->
        if (section == SettingsSection.ADVANCED) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val summary = when (section) {
            SettingsSection.AI -> if (hasKey) "${providerName(settings.baseUrl)} · ${settings.model}" else "Не подключён — сообщения сохраняются во «Входящие»"
            SettingsSection.CALENDAR -> (calendars.firstOrNull { it.id == settings.defaultCalendarId }?.label ?: "Календарь: спрашивать") +
                " · ${settings.eventMinutes} мин"
            SettingsSection.TASKS -> "Доска «${VaultPaths.baseName(settings.defaultBoard)}» · «${VaultPaths.baseName(settings.inboxNote)}»"
            SettingsSection.STORAGE -> vm.vaultName
            SettingsSection.PRIVACY -> ".aiignore и что уходит в AI"
            SettingsSection.APPEARANCE -> paletteLabel(settings.palette) + " · " + themeLabel(settings.theme)
            SettingsSection.ADVANCED -> "Инструменты, свои инструкции, детали запросов, команды"
            SettingsSection.ABOUT -> "Версия ${vm.appVersion} · GPL-3.0"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(section.id) }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- sections -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(vm: SettingsViewModel, settings: AppSettings) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var key by remember { mutableStateOf("") }

    Hint("Свой ключ (BYOK). По умолчанию DeepSeek; подходит любой OpenAI-совместимый API.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.forEach { (name, url, defaultModel) ->
            FilterChip(
                selected = baseUrl.trim().trimEnd('/') == url,
                onClick = { baseUrl = url; model = defaultModel },
                label = { Text(name) },
            )
        }
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
    Hint("Ключ шифруется ключом из Android Keystore и не попадает в логи. Тонкая настройка модели — в разделе «Для продвинутых».")
}

@Composable
private fun CalendarSection(vm: SettingsViewModel, settings: AppSettings) {
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.loadCalendars() }
    if (!vm.calendarAllowed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Нет доступа к календарю", modifier = Modifier.weight(1f))
            TextButton(onClick = { permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS) }) { Text("Разрешить") }
        }
    } else if (calendars.isNotEmpty()) {
        Label("Календарь для новых событий")
        val options = listOf<Pair<Long?, String>>(null to "Спрашивать, если аккаунтов несколько") + calendars.map { it.id to it.label }
        Selector(options, settings.defaultCalendarId, vm::setDefaultCalendar)
    }
    Label("Длительность события по умолчанию")
    Selector(listOf(15, 30, 45, 60, 90, 120).map { it to "$it мин" }, settings.eventMinutes, vm::setEventMinutes)
    Label("Напоминание по умолчанию")
    Selector(ReminderOptions.map { it to reminderLabel(it) }, settings.reminderMinutes, vm::setReminder)
}

@Composable
private fun TasksSection(vm: SettingsViewModel, settings: AppSettings) {
    val index = vm.index?.collectAsStateWithLifecycle()?.value
    val boards = index?.boards.orEmpty()
    Label("Доска по умолчанию")
    Hint("Сюда попадают задачи из чата и команды /задача. Её же показывает вкладка «Задачи».")
    Selector(
        options = (boards.map { it.path to it.title } + (settings.defaultBoard to VaultPaths.baseName(settings.defaultBoard))).distinctBy { it.first },
        selected = settings.defaultBoard,
        onSelect = vm::setDefaultBoard,
    )
    Label("Заметка «Входящие»")
    Hint("Куда складываются сообщения без AI и мысли, которые ассистент предлагает «дописать».")
    var inbox by rememberSaveable { mutableStateOf(settings.inboxNote) }
    OutlinedTextField(inbox, { inbox = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { vm.setInboxNote(inbox) }, enabled = inbox.isNotBlank() && inbox != settings.inboxNote) { Text("Сохранить") }
}

@Composable
private fun StorageSection(vm: SettingsViewModel) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }
    Text("Сейчас: ${vm.vaultName}", style = MaterialTheme.typography.bodyLarge)
    Hint("Заметки — обычные .md файлы. Выберите папку, которую синхронизирует Syncthing, Google Drive или Obsidian, — и они будут видны на ПК.")
    Row {
        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = vm::useInternalStorage) { Text("Внутри приложения") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Hint("Если что-то изменили на ПК, а в приложении не видно, — перечитайте файлы.")
    OutlinedButton(onClick = vm::reindex) { Text("Перечитать все файлы") }
}

@Composable
private fun PrivacySection(vm: SettingsViewModel, onOpenNote: (String) -> Unit) {
    Label("Что отправляется AI")
    Hint(
        "• ваше сообщение и последние сообщения чата;\n" +
            "• служебный контекст: дата и время, названия досок, колонок, папок и календарей;\n" +
            "• заметки, задачи и события — только когда ассистент их ищет или читает. Каждое такое действие видно в чате над ответом;\n" +
            "• ваш файл инструкций, если он включён в «Для продвинутых».\n" +
            "Команды через «/» работают без AI. Без ключа приложение полностью офлайн.",
    )
    Label("Скрытые от AI заметки")
    Hint(
        "Заметки и папки из файла .aiignore ассистент не видит вообще: их нет в поиске, чтении и списках. " +
            "Файлы *.env скрыты всегда. Скрыть заметку или папку можно долгим нажатием в списке заметок.",
    )
    OutlinedButton(onClick = { vm.openAiIgnore(onOpenNote) }) { Text("Правила .aiignore") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(vm: SettingsViewModel, settings: AppSettings) {
    Label("Палитра")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.entries.forEach { palette ->
            FilterChip(selected = settings.palette == palette, onClick = { vm.setPalette(palette) }, label = { Text(paletteLabel(palette)) })
        }
    }
    Label("Тема")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(selected = settings.theme == mode, onClick = { vm.setTheme(mode) }, label = { Text(themeLabel(mode)) })
        }
    }
}

@Composable
private fun AdvancedSection(vm: SettingsViewModel, settings: AppSettings, onOpenNote: (String) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Hint("Всё здесь необязательно: приложение работает и без этих настроек.")

    Group("Прозрачность")
    SwitchRow(
        "Показывать «Под капотом»",
        "Под ответами AI появится кнопка: промпт, отправленные сообщения, вызовы инструментов, токены и время. Последний ответ — также командой /детали.",
        settings.showDetails,
        vm::setShowDetails,
    )
    OutlinedButton(onClick = vm::loadSystemPrompt) { Text("Показать системный промпт") }

    Group("Мои инструкции")
    SwitchRow(
        "Использовать ${settings.instructionsFile}",
        "Файл в папке заметок, который ассистент читает перед ответом: ваш распорядок, адреса, правила. Уходит провайдеру AI вместе с запросом.",
        settings.useInstructions,
        vm::setUseInstructions,
    )
    OutlinedButton(onClick = { vm.openInstructions(onOpenNote) }) { Text("Открыть ${settings.instructionsFile}") }

    Group("Без подтверждения")
    Hint("Выбранные действия выполняются сразу, без карточки. События — только если календарь уже выбран.")
    ProposalKind.entries.forEach { kind ->
        SwitchRow("Сразу добавлять ${kind.label}", null, kind.name in settings.autoApply) { vm.setAutoApply(kind.name, it) }
    }

    Group("Инструменты ассистента")
    Hint("Выключенный инструмент модель не видит и не может вызвать.")
    vm.tools.forEach { tool ->
        val name = tool.spec.name
        SwitchRow(
            toolLabel(name),
            (if (tool.kind == ToolKind.READ) "Чтение · " else "Предложение с подтверждением · ") + name,
            name !in settings.disabledTools,
        ) { vm.setToolEnabled(name, it) }
    }

    Group("Модель")
    Text("Температура: ${"%.1f".format(settings.temperature)}", style = MaterialTheme.typography.bodyLarge)
    Hint("Ниже — точнее и предсказуемее, выше — свободнее формулировки.")
    Slider(value = settings.temperature, onValueChange = { vm.setTemperature((it * 10).toInt() / 10f) }, valueRange = 0f..1f, steps = 9)
    Text("Шагов на ответ: ${settings.maxSteps}", style = MaterialTheme.typography.bodyLarge)
    Hint("Сколько раз ассистент может поискать или прочитать что-то перед ответом.")
    Slider(value = settings.maxSteps.toFloat(), onValueChange = { vm.setMaxSteps(it.toInt()) }, valueRange = 1f..10f, steps = 8)
    Text("Сообщений истории в запросе: ${settings.historySize}", style = MaterialTheme.typography.bodyLarge)
    Slider(value = settings.historySize.toFloat(), onValueChange = { vm.setHistorySize(it.toInt()) }, valueRange = 0f..20f, steps = 19)

    Group("Команды")
    Hint("Наберите «/» в строке ввода. Команды работают без AI.")
    SlashCommands.all.forEach { c ->
        Text(
            "/${c.name}${if (c.takesArgument) " ${c.hint}" else ""} — ${c.description}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Group("Обслуживание")
    TextButton(onClick = { confirmClear = true }) { Text("Очистить историю чата и детали запросов") }
    TextButton(onClick = { confirmReset = true }) { Text("Сбросить продвинутые настройки") }

    vm.systemPrompt?.let { prompt -> TextDialog("Системный промпт", prompt, vm::closeSystemPrompt) }
    if (confirmClear) {
        ConfirmDialog("Очистить историю чата?", "Заметки, задачи и события не затрагиваются.", { vm.clearChat() }) { confirmClear = false }
    }
    if (confirmReset) {
        ConfirmDialog(
            "Сбросить продвинутые настройки?",
            "Инструменты, автодобавление, параметры модели и детали вернутся к значениям по умолчанию.",
            { vm.resetAdvanced() },
        ) { confirmReset = false }
    }
}

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    val uriHandler = LocalUriHandler.current
    Text("VibeNotes ${vm.appVersion}", style = MaterialTheme.typography.titleMedium)
    Hint("Свободное ПО (GPL-3.0). Без аккаунтов, серверов и телеметрии. Данные — обычные файлы в вашей папке.")
    TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/MAppleDm/VibeNotes") } }) { Text("Исходный код на GitHub") }
}

// --- building blocks --------------------------------------------------------------------------

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun TextDialog(title: String, text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); onClose() }) { Text("Скопировать") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Да") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private val Presets = listOf(
    Triple("DeepSeek", ProviderConfig.DEEPSEEK_BASE_URL, ProviderConfig.DEEPSEEK_MODEL),
    Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Triple("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
)

private fun providerName(baseUrl: String): String =
    Presets.firstOrNull { baseUrl.trim().trimEnd('/') == it.second }?.first
        ?: baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')

private fun paletteLabel(palette: Palette) = when (palette) {
    Palette.LEMON_COFFEE -> "Лимон и кофе"
    Palette.GRAPHITE -> "Графит"
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "Как в системе"
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
}

fun toolLabel(name: String): String = when (name) {
    "search_notes" -> "Поиск по заметкам"
    "read_note" -> "Чтение заметок"
    "list_tasks" -> "Просмотр задач"
    "list_calendars" -> "Список календарей"
    "list_calendar_events" -> "Чтение календаря"
    "create_calendar_event" -> "Предлагать события"
    "create_note" -> "Предлагать заметки"
    "append_to_note" -> "Дописывать в заметки"
    "add_task" -> "Предлагать задачи"
    else -> name
}
