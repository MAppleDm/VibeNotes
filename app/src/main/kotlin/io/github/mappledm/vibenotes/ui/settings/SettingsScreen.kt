package io.github.mappledm.vibenotes.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.agent.DataAccess
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.privacy.AiIgnore
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.data.AppFont
import io.github.mappledm.vibenotes.data.AppSettings
import io.github.mappledm.vibenotes.data.Palette
import io.github.mappledm.vibenotes.data.ThemeMode
import io.github.mappledm.vibenotes.data.UpdateState
import io.github.mappledm.vibenotes.ui.components.ReminderOptions
import io.github.mappledm.vibenotes.ui.components.Selector
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.components.reminderLabel
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import io.github.mappledm.vibenotes.ui.update.UpdatePanel

/** Settings are grouped like a phone's settings app: a short list, details one tap away. */
enum class SettingsSection(val id: String, val title: String, val icon: ImageVector) {
    AI("ai", "Подключение AI", Icons.Outlined.Key),
    ASSISTANT("assistant", "Ассистент", Icons.Outlined.AutoAwesome),
    CALENDAR("calendar", "Календарь", Icons.Outlined.CalendarMonth),
    TASKS("tasks", "Задачи и заметки", Icons.Outlined.ViewColumn),
    STORAGE("storage", "Хранилище", Icons.Outlined.Folder),
    PRIVACY("privacy", "Приватность", Icons.Outlined.Lock),
    APPEARANCE("appearance", "Оформление", Icons.Outlined.Palette),
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
                        SettingsSection.ASSISTANT -> AssistantSection(vm, settings, onOpenNote)
                        SettingsSection.CALENDAR -> CalendarSection(vm, settings)
                        SettingsSection.TASKS -> TasksSection(vm, settings)
                        SettingsSection.STORAGE -> StorageSection(vm, settings, onOpenNote)
                        SettingsSection.PRIVACY -> PrivacySection(vm, onOpenNote)
                        SettingsSection.APPEARANCE -> AppearanceSection(vm, settings)
                        SettingsSection.ABOUT -> AboutSection(vm, settings)
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
    val update by vm.update.collectAsStateWithLifecycle()
    SettingsSection.entries.forEach { section ->
        val summary = when (section) {
            SettingsSection.AI -> if (hasKey) "${providerName(settings.baseUrl)} · ${settings.model}" else "Не подключён — сообщения сохраняются во «Входящие»"
            SettingsSection.ASSISTANT -> assistantSummary(settings)
            SettingsSection.CALENDAR -> (calendars.firstOrNull { it.id == settings.defaultCalendarId }?.label ?: "Календарь: спрашивать") +
                " · ${settings.eventMinutes} мин"
            SettingsSection.TASKS -> "Доска «${VaultPaths.baseName(settings.defaultBoard)}» · «${VaultPaths.baseName(settings.inboxNote)}»"
            SettingsSection.STORAGE -> vm.vaultName
            SettingsSection.PRIVACY -> ".aiignore и что уходит в AI"
            SettingsSection.APPEARANCE -> paletteLabel(settings.palette) + " · " + themeLabel(settings.theme) + " · " + fontLabel(settings)
            SettingsSection.ABOUT -> when (val u = update) {
                is UpdateState.Available -> "Доступна версия ${u.release.versionName}"
                is UpdateState.Downloading, is UpdateState.Ready -> "Обновление загружается"
                else -> "Версия ${vm.appVersion} · обновления"
            }
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
    Hint("Ключ шифруется ключом из Android Keystore и не попадает в логи.")
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
    Hint("Сюда попадают задачи из чата. Её же показывает вкладка «Задачи».")
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
private fun StorageSection(vm: SettingsViewModel, settings: AppSettings, onOpenNote: (String) -> Unit) {
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
    Label("Файлы, на которых всё держится")
    Hint("Это обычный текст: можно открыть и поправить здесь или в Obsidian на ПК.")
    Column {
        FileRow("Входящие", settings.inboxNote) { vm.openInbox(onOpenNote) }
        FileRow("Доска задач", settings.defaultBoard) { vm.openBoardFile(onOpenNote) }
        FileRow("Инструкции ассистенту", settings.instructionsFile) { vm.openInstructions(onOpenNote) }
        FileRow("Что скрыто от AI", AiIgnore.FILE_NAME) { vm.openAiIgnore(onOpenNote) }
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
            "• заметки, задачи и события — только когда ассистент их ищет или читает, и только то, что разрешено в «Ассистент». Каждое такое действие видно в чате над ответом;\n" +
            "• ваш файл инструкций, если он включён в «Ассистент».\n" +
            "Без ключа приложение полностью офлайн.",
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

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri)
    }
    // Font files come with all kinds of MIME types (or none), so any file is offered and checked on import.
    val pickFont = { fontPicker.launch(arrayOf("*/*")) }
    val customName = settings.customFontName
    Label("Шрифт")
    Column {
        FontRow(vm, settings, AppFont.GOLOS, "Golos Text", "Сделан для русского интерфейса. По умолчанию")
        FontRow(vm, settings, AppFont.SYSTEM, "Системный", "Как в остальных приложениях телефона")
        if (customName != null) {
            FontRow(vm, settings, AppFont.CUSTOM, customName, "Свой шрифт из файла")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = pickFont) { Text(if (customName == null) "Загрузить .ttf" else "Заменить файл") }
        if (customName != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = vm::removeCustomFont) { Text("Удалить") }
        }
    }
    Hint("Подойдёт файл .ttf или .otf, лучше с кириллицей: каких букв нет в шрифте, те будут системными. Код и .env-заметки всегда моноширинные.")
}

@Composable
private fun FontRow(vm: SettingsViewModel, settings: AppSettings, font: AppFont, title: String, subtitle: String) {
    val family = remember(font, settings.customFontVersion) { vm.fontFamily(font) }
    val selected = settings.font == font
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.setFont(font) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { vm.setFont(font) })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family, fontWeight = FontWeight.Medium))
            Text(
                "Съешь же ещё этих мягких французских булок",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * How the assistant understands you and what it may do — the part worth tinkering with,
 * in plain words. Everything here is optional; the defaults only ever propose.
 */
@Composable
private fun AssistantSection(vm: SettingsViewModel, settings: AppSettings, onOpenNote: (String) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }

    Group("Мои инструкции")
    Hint(
        "Как agents.md на ПК: файл ${settings.instructionsFile} в папке заметок, который ассистент читает перед каждым ответом. " +
            "Напишите туда распорядок, адреса, привычки — например, «врач — это поликлиника на Кирова, 38» или «покупки клади на доску Покупки».",
    )
    SwitchRow("Учитывать инструкции", null, settings.useInstructions, vm::setUseInstructions)
    OutlinedButton(onClick = { vm.openInstructions(onOpenNote) }) { Text("Открыть ${settings.instructionsFile}") }

    Group("Без подтверждения")
    Hint("Обычно ассистент показывает карточку и ничего не меняет, пока вы не нажмёте кнопку. Можно разрешить ему сразу:")
    Column {
        ProposalKind.entries.forEach { kind ->
            SwitchRow("Добавлять ${kind.label}", null, kind.name in settings.autoApply) { vm.setAutoApply(kind.name, it) }
        }
    }
    Hint("Событие без выбранного календаря всё равно спросит, куда его поставить. Такие действия помечаются в чате.")

    Group("Что ассистент может смотреть")
    Column {
        DataAccess.entries.forEach { access ->
            SwitchRow(access.label, access.description, access.tools.none { it in settings.disabledTools }) { vm.setAccess(access, it) }
        }
    }
    Hint("Отдельные заметки и папки можно скрыть от AI в разделе «Приватность».")

    Group("Память")
    Text("Помнит последних сообщений: ${settings.historySize}", style = MaterialTheme.typography.bodyLarge)
    Hint("Чтобы понимать «перенеси на 10» или «и ещё молоко». Больше — лучше помнит, но запрос дольше и дороже.")
    Slider(
        value = settings.historySize.toFloat(),
        onValueChange = { vm.setHistorySize(it.toInt()) },
        valueRange = 0f..20f,
        steps = 19,
    )
    TextButton(onClick = { confirmClear = true }) { Text("Очистить историю чата") }

    if (confirmClear) {
        ConfirmDialog("Очистить историю чата?", "Заметки, задачи и события не затрагиваются.", vm::clearChat) { confirmClear = false }
    }
}

private fun assistantSummary(settings: AppSettings): String {
    val parts = buildList {
        if (settings.useInstructions) add("свои инструкции")
        if (settings.autoApply.isNotEmpty()) add("часть действий без подтверждения")
        if (DataAccess.entries.any { a -> a.tools.any { it in settings.disabledTools } }) add("доступ ограничен")
    }
    return if (parts.isEmpty()) "Инструкции, подтверждения, доступ к данным" else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun AboutSection(vm: SettingsViewModel, settings: AppSettings) {
    val uriHandler = LocalUriHandler.current
    Group("Обновления")
    UpdatePanel()
    SwitchRow(
        "Проверять автоматически",
        "Раз в 12 часов приложение скачивает с GitHub маленький файл с номером последней версии. О вас ничего не отправляется. Устанавливается обновление только после вашего подтверждения.",
        settings.autoUpdateCheck,
        vm::setAutoUpdateCheck,
    )
    Group("VibeNotes")
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
private fun FileRow(title: String, fileName: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

private fun fontLabel(settings: AppSettings) = when (settings.font) {
    AppFont.GOLOS -> "Golos Text"
    AppFont.SYSTEM -> "системный шрифт"
    AppFont.CUSTOM -> settings.customFontName ?: "свой шрифт"
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "Как в системе"
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
}
