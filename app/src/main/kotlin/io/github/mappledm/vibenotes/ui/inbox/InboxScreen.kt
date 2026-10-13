package io.github.mappledm.vibenotes.ui.inbox

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyCharacterMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ActivityKind
import io.github.mappledm.vibenotes.core.agent.ResultTarget
import io.github.mappledm.vibenotes.core.agent.ToolActivity
import io.github.mappledm.vibenotes.core.inbox.ChatEntry
import io.github.mappledm.vibenotes.core.inbox.CommandAction
import io.github.mappledm.vibenotes.core.inbox.SlashCommand
import io.github.mappledm.vibenotes.core.inbox.ChatRole
import io.github.mappledm.vibenotes.core.inbox.ProposalItem
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.ui.components.MarkdownView
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import java.time.ZoneId

private const val LOAD_ONLY = "load"

private val Suggestions = listOf(
    "Завтра в 15:00 встреча с Андреем на час",
    "В четверг утром сходить к врачу",
    "Купить батарейки до пятницы",
    "Что у меня на этой неделе?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenDrawer: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenBoard: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchTab: (Int) -> Unit,
) {
    val vm: InboxViewModel = viewModel(factory = containerFactory { InboxViewModel(it) })
    val entries by vm.entries.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val boards by vm.boards.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, ProposalItem>?>(null) }
    var pendingConfirm by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val target = pendingConfirm
        pendingConfirm = null
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) permissionDenied = true
        if (target != null && target != LOAD_ONLY) {
            // Without permission the card shows an honest failure with next steps;
            // with it, the event is added unless a calendar still has to be chosen.
            val (entryId, proposalId) = target.split('|', limit = 2)
            vm.afterPermission(entryId, proposalId, granted)
        } else {
            vm.loadCalendars()
        }
    }

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    fun confirm(entryId: String, item: ProposalItem) {
        if (item.proposal is ActionProposal.CreateEvent && !vm.hasCalendarPermission()) {
            pendingConfirm = "$entryId|${item.id}"
            permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
        } else {
            vm.confirm(entryId, item.id)
        }
    }

    fun requestPermission(entryId: String, item: ProposalItem) {
        when {
            vm.hasCalendarPermission() -> vm.afterPermission(entryId, item.id, granted = true)
            // After a denial Android may stop showing the dialog: send the user to app settings.
            permissionDenied -> openAppSettings()
            else -> {
                pendingConfirm = "$entryId|${item.id}"
                permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
            }
        }
    }

    fun loadCalendars() {
        when {
            vm.hasCalendarPermission() -> vm.loadCalendars()
            permissionDenied -> openAppSettings()
            else -> {
                pendingConfirm = LOAD_ONLY
                permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
            }
        }
    }

    fun openTarget(item: ProposalItem) {
        when (val target = item.target) {
            is ResultTarget.Note -> onOpenNote(target.path)
            is ResultTarget.Board -> onOpenBoard(target.path)
            is ResultTarget.Event -> runCatching {
                val event = item.proposal as? ActionProposal.CreateEvent
                val begin = event?.startDateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                    ?: System.currentTimeMillis()
                context.startActivity(AndroidCalendarGateway.viewTimeIntent(begin))
            }
            null -> Unit
        }
    }

    OnResume { vm.loadCalendars() }

    LaunchedEffect(vm) {
        vm.commandEvents.collect { action ->
            when (action) {
                CommandAction.OPEN_WEEK -> onSwitchTab(1)
                CommandAction.OPEN_TASKS -> onSwitchTab(2)
                CommandAction.OPEN_NOTES -> onSwitchTab(3)
                CommandAction.OPEN_SETTINGS -> onOpenSettings()
                CommandAction.CLEAR -> confirmClear = true
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Входящие") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Меню") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Команды") }, onClick = { menuOpen = false; vm.useSuggestion("/") })
                            DropdownMenuItem(text = { Text("Под капотом: последний ответ") }, onClick = { menuOpen = false; vm.showLatestTrace() })
                            DropdownMenuItem(text = { Text("Настройки") }, onClick = { menuOpen = false; onOpenSettings() })
                            DropdownMenuItem(text = { Text("Очистить историю чата") }, onClick = { menuOpen = false; confirmClear = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                vm = vm,
                generating = generating,
                aiConfigured = aiConfigured,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!aiConfigured) AiBanner(onOpenSettings)
            if (entries.isEmpty()) {
                EmptyInbox(aiConfigured, onSuggestion = vm::useSuggestion)
            } else {
                val listState = rememberLazyListState()
                val reversed = remember(entries) { entries.asReversed() }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(reversed, key = { it.id }) { entry ->
                        EntryItem(
                            entry = entry,
                            calendars = calendars,
                            onConfirm = { confirm(entry.id, it) },
                            onDismiss = { vm.dismiss(entry.id, it.id) },
                            onEdit = { editing = entry.id to it },
                            onOpen = { openTarget(it) },
                            onRequestPermission = { requestPermission(entry.id, it) },
                            onOpenInCalendarApp = { openInCalendarApp(context, it.proposal) },
                            onRetry = { vm.retry(entry.id) },
                            needsCalendarChoice = { vm.needsCalendarChoice(it.proposal, calendars) },
                            selectedCalendar = { (it.proposal as? ActionProposal.CreateEvent)?.let { e -> vm.effectiveCalendar(e, calendars) } },
                            onChooseCalendar = { item, id -> vm.chooseCalendar(entry.id, item.id, id) },
                            onLoadCalendars = ::loadCalendars,
                            onWikiLink = { target -> vm.resolveNote(target)?.let(onOpenNote) },
                            showDetails = settings.showDetails,
                            onShowTrace = { vm.showTrace(entry.id) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { (entryId, item) ->
        ProposalEditorDialog(
            proposal = item.proposal,
            calendars = calendars,
            boards = boards,
            onDismiss = { editing = null },
            onSave = { proposal, done -> vm.edit(entryId, item.id, proposal, done) },
        )
    }

    vm.trace?.let { TraceSheet(it, onDismiss = vm::closeTrace) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Сообщения чата будут удалены. Заметки, задачи и события останутся.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AiBanner(onOpenSettings: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "AI не подключён — сообщения сохраняются во «Входящие».",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            TextButton(onClick = onOpenSettings) { Text("Подключить") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyInbox(aiConfigured: Boolean, onSuggestion: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Что запомнить?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            if (aiConfigured) {
                "Пишите как в мессенджере. AI разберёт сообщение на событие, задачу или заметку и спросит подтверждение."
            } else {
                "Пишите как в мессенджере — всё сохранится в заметку «Входящие». С AI сообщения будут превращаться в события и задачи."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Suggestions.forEach { s ->
                Surface(
                    onClick = { onSuggestion(s) },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun EntryItem(
    entry: ChatEntry,
    calendars: List<io.github.mappledm.vibenotes.core.calendar.CalendarInfo>,
    onConfirm: (ProposalItem) -> Unit,
    onDismiss: (ProposalItem) -> Unit,
    onEdit: (ProposalItem) -> Unit,
    onOpen: (ProposalItem) -> Unit,
    onRequestPermission: (ProposalItem) -> Unit,
    onOpenInCalendarApp: (ProposalItem) -> Unit,
    onRetry: () -> Unit,
    onWikiLink: (String) -> Unit,
    needsCalendarChoice: (ProposalItem) -> Boolean,
    selectedCalendar: (ProposalItem) -> io.github.mappledm.vibenotes.core.calendar.CalendarInfo?,
    onChooseCalendar: (ProposalItem, Long) -> Unit,
    onLoadCalendars: () -> Unit,
    showDetails: Boolean,
    onShowTrace: () -> Unit,
) {
    when (entry.role) {
        ChatRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
        ChatRole.NOTICE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            SelectionContainer(Modifier.weight(1f)) {
                MarkdownView(
                    entry.text,
                    compact = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    onWikiLink = onWikiLink,
                )
            }
        }
        ChatRole.ASSISTANT -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.activities.isNotEmpty()) ActivityList(entry.activities)
            if (entry.streaming && entry.text.isEmpty() && entry.proposals.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Думаю…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.text.isNotBlank()) {
                SelectionContainer {
                    MarkdownView(entry.text, compact = true, onWikiLink = onWikiLink)
                }
            }
            entry.proposals.forEach { item ->
                ProposalCard(
                    item = item,
                    calendars = calendars,
                    onConfirm = { onConfirm(item) },
                    onDismiss = { onDismiss(item) },
                    onEdit = { onEdit(item) },
                    onOpen = { onOpen(item) },
                    onRequestPermission = { onRequestPermission(item) },
                    onOpenInCalendarApp = { onOpenInCalendarApp(item) },
                    selectedCalendar = selectedCalendar(item),
                    needsCalendarChoice = needsCalendarChoice(item),
                    onChooseCalendar = { id -> onChooseCalendar(item, id) },
                    onLoadCalendars = onLoadCalendars,
                )
            }
            entry.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
            }
            if (showDetails && entry.hasTrace && !entry.streaming) {
                Surface(
                    onClick = onShowTrace,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Code, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(6.dp))
                        Text("Под капотом", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Shows exactly what the assistant looked at — this content was sent to the AI provider. */
@Composable
private fun ActivityList(activities: List<ToolActivity>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        activities.forEach { activity ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(activityIcon(activity.kind), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                Text(activity.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun activityIcon(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.SEARCH -> Icons.Outlined.Search
    ActivityKind.READ -> Icons.Outlined.Description
    ActivityKind.TASKS -> Icons.Outlined.TaskAlt
    ActivityKind.CALENDAR -> Icons.Outlined.CalendarMonth
    ActivityKind.PRIVATE -> Icons.Outlined.Lock
    ActivityKind.ERROR -> Icons.Outlined.ErrorOutline
}

@Composable
private fun Composer(vm: InboxViewModel, generating: Boolean, aiConfigured: Boolean) {
    val context = LocalContext.current
    val speechAvailable = remember {
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.appendToComposer(text)
    }
    val value = vm.composer

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val suggestions = vm.suggestions
            if (suggestions.isNotEmpty()) CommandSuggestions(suggestions, onPick = vm::pickCommand)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        if (value.text.isEmpty()) {
                            Text(
                                if (aiConfigured) "Событие, задача, мысль… или /команда" else "Запишите мысль… или /команда",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = vm::onComposerChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp)
                                .onPreviewKeyEvent { event ->
                                    // Hardware keyboard: Enter sends, Shift+Enter is a new line.
                                    val hardware = event.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                                        (event.isCtrlPressed || (hardware && !event.isShiftPressed))
                                    ) {
                                        vm.send()
                                        true
                                    } else false
                                },
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                when {
                    generating -> FilledIconButton(onClick = vm::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Остановить")
                    }
                    value.text.isBlank() && speechAvailable -> FilledIconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
                        runCatching { speechLauncher.launch(intent) }
                    }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
                    }
                    else -> FilledIconButton(onClick = vm::send, enabled = value.text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

/** The command palette: shown while the composer holds "/…". */
@Composable
private fun CommandSuggestions(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        commands.forEach { command ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(command) }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "/${command.name}" + if (command.takesArgument) " ${command.hint}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 120.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Fallback without calendar permission: open the system "new event" screen prefilled. */
private fun openInCalendarApp(context: android.content.Context, proposal: ActionProposal) {
    val p = proposal as? ActionProposal.CreateEvent ?: return
    val zone = ZoneId.systemDefault()
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, p.title)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, p.allDay)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, p.startDateTime.atZone(zone).toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, p.endDateTime.atZone(zone).toInstant().toEpochMilli())
    p.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    p.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}
