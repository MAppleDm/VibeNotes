package io.github.mappledm.vibenotes.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.notes.NoteKind
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.core.notes.TimeScale
import io.github.mappledm.vibenotes.core.preview.AiPreviews
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Notes as a timeline, like a photo gallery: grouped by day, week, month or year of their last change.
 * Zooming out (pinch, or the switch on top) also makes the list denser — rows with the AI gist and facts
 * for days and weeks, tiles for months, small tiles for years. Folders are chips above the timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    folder: String,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenBoard: (String) -> Unit,
    onNewNote: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val vm: NotesViewModel = viewModel(key = "notes:$folder", factory = containerFactory { NotesViewModel(it, folder) })
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val grid = rememberLazyGridState()

    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteMeta?>(null) }
    var moving by remember { mutableStateOf<NoteMeta?>(null) }
    var deleting by remember { mutableStateOf<NoteMeta?>(null) }

    OnResume { vm.refresh() }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        // Cleared only after the snackbar is gone: clearing first would change the key and cancel the snackbar.
        try {
            val result = snackbar.showSnackbar(
                m.text,
                actionLabel = if (m.trashPath != null) "Отменить" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoDelete(m)
        } finally {
            vm.messageShown(m)
        }
    }

    // AI previews are made only for notes that actually reach the screen.
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.let(::notePathOf) } }
            .distinctUntilChanged()
            .collectLatest { paths ->
                delay(400)
                vm.setVisible(paths)
            }
    }

    fun open(meta: NoteMeta) = if (meta.kind == NoteKind.BOARD) onOpenBoard(meta.path) else onOpenNote(meta.path)

    val actions = NoteActions(
        onOpen = ::open,
        onPin = { vm.togglePin(it.path) },
        onToggleAi = { vm.setHiddenFromAi(it.path, false, !it.hiddenFromAi) },
        onRename = { renaming = it },
        onMove = { moving = it },
        onDelete = { deleting = it },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text("Поиск по заметкам") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(if (folder.isEmpty()) "Заметки" else VaultPaths.name(folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (folder.isNotEmpty()) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; vm.setQuery("") }) { Icon(Icons.Outlined.Close, "Закрыть поиск") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Поиск") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Сначала новые") },
                                    leadingIcon = { RadioButton(selected = !state.oldestFirst, onClick = null) },
                                    onClick = { menu = false; vm.setOldestFirst(false) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Сначала старые") },
                                    leadingIcon = { RadioButton(selected = state.oldestFirst, onClick = null) },
                                    onClick = { menu = false; vm.setOldestFirst(true) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Новая папка") },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    onClick = { menu = false; newFolder = true },
                                )
                            }
                        }
                        onOpenSettings?.let { open -> IconButton(onClick = open) { Icon(Icons.Outlined.Settings, "Настройки") } }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching) {
                FloatingActionButton(onClick = { onNewNote(folder) }) { Icon(Icons.Filled.Add, "Новая заметка") }
            }
        },
    ) { padding ->
        if (searching && state.query.isNotBlank()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                if (state.results.isEmpty()) {
                    item { EmptyText("Ничего не найдено") }
                }
                items(state.results, key = { it.note.path }) { hit ->
                    NoteRow(hit.note, hit.snippet, aiPreview = false, pinned = hit.note.path in state.pinnedPaths, time = Dates.modified(hit.note.lastModified), showFolder = true, actions = actions)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                ScaleSwitch(state.scale, vm::setScale)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    state = grid,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchToZoom(
                            onZoomIn = { vm.setScale(state.scale.finer()) },
                            onZoomOut = { vm.setScale(state.scale.coarser()) },
                        ),
                ) {
                    timeline(state, folder, actions, onOpenFolder = onOpenFolder, onToggleFolderAi = { path, hidden -> vm.setHiddenFromAi(path, true, hidden) })
                }
            }
        }
    }

    if (newFolder) {
        TextInputDialog("Новая папка", "", "Создать", onDismiss = { newFolder = false }) { vm.createFolder(it) }
    }
    renaming?.let { meta ->
        TextInputDialog("Переименовать", meta.title, "Сохранить", onDismiss = { renaming = null }) { vm.rename(meta.path, it) }
    }
    moving?.let { meta ->
        MoveDialog(state.allFolders, meta.folder, onDismiss = { moving = null }) { vm.move(meta.path, it) }
    }
    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${meta.title}»?") },
            text = { Text("Файл будет перемещён в папку .trash внутри хранилища.") },
            confirmButton = { TextButton(onClick = { vm.delete(meta.path); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

/** What a note's long press offers, the same in rows and tiles. */
private class NoteActions(
    val onOpen: (NoteMeta) -> Unit,
    val onPin: (NoteMeta) -> Unit,
    val onToggleAi: (NoteMeta) -> Unit,
    val onRename: (NoteMeta) -> Unit,
    val onMove: (NoteMeta) -> Unit,
    val onDelete: (NoteMeta) -> Unit,
)

private fun LazyGridScope.timeline(
    state: NotesUiState,
    folder: String,
    actions: NoteActions,
    onOpenFolder: (String) -> Unit,
    onToggleFolderAi: (String, Boolean) -> Unit,
) {
    if (state.subfolders.isNotEmpty()) {
        item(key = "folders", span = { GridItemSpan(GRID_COLUMNS) }) {
            FolderChips(state.subfolders, state.hiddenFolders, onOpenFolder, onToggleFolderAi)
        }
    }
    if (state.pinned.isNotEmpty()) {
        item(key = "pinned-header", span = { GridItemSpan(GRID_COLUMNS) }) { GroupHeader("Закреплённые", state.pinned.size) }
        items(state.pinned, key = { "pin:" + it.path }, span = { GridItemSpan(GRID_COLUMNS) }) { meta ->
            NoteRow(meta, state.aiPreviews[meta.path] ?: meta.preview, aiPreview = meta.path in state.aiPreviews, pinned = true, time = Dates.modified(meta.lastModified), showFolder = true, actions = actions)
        }
    }
    val today = LocalDate.now()
    val span = when (state.scale) {
        TimeScale.DAY, TimeScale.WEEK -> GRID_COLUMNS
        TimeScale.MONTH -> GRID_COLUMNS / 2
        TimeScale.YEAR -> GRID_COLUMNS / 3
    }
    state.groups.forEach { group ->
        item(key = "group:${state.scale}:${group.start}", span = { GridItemSpan(GRID_COLUMNS) }) {
            GroupHeader(groupTitle(group.start, state.scale, today), group.items.size)
        }
        items(group.items, key = { "note:" + it.path }, span = { GridItemSpan(span) }) { meta ->
            val ai = state.aiPreviews[meta.path]
            val showFolder = meta.folder != folder
            when (state.scale) {
                TimeScale.DAY, TimeScale.WEEK -> NoteRow(
                    meta, ai ?: meta.preview, aiPreview = ai != null, pinned = false,
                    time = timeLabel(meta.lastModified, state.scale), showFolder = showFolder, actions = actions,
                )
                TimeScale.MONTH -> NoteTile(meta, gist = ai?.lines()?.firstOrNull() ?: meta.preview, aiPreview = ai != null, time = timeLabel(meta.lastModified, state.scale), dense = false, actions = actions)
                TimeScale.YEAR -> NoteTile(meta, gist = null, aiPreview = false, time = timeLabel(meta.lastModified, state.scale), dense = true, actions = actions)
            }
        }
    }
    if (state.loaded && state.isEmpty) {
        item(key = "empty", span = { GridItemSpan(GRID_COLUMNS) }) { EmptyText(if (folder.isEmpty()) "Заметок пока нет. Нажмите +, чтобы создать первую." else "Папка пуста") }
    }
}

private fun notePathOf(key: String): String? = when {
    key.startsWith("note:") -> key.removePrefix("note:")
    key.startsWith("pin:") -> key.removePrefix("pin:")
    else -> null
}

/**
 * Two-finger pinch steps the time scale like zooming a photo gallery: spread to see days, pinch to see years.
 * One step per gesture; single-finger touches pass through untouched, so scrolling and tab swipes still work.
 */
private fun Modifier.pinchToZoom(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier = composed {
    val zoomIn by rememberUpdatedState(onZoomIn)
    val zoomOut by rememberUpdatedState(onZoomOut)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var fired = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (!fired && zoom > ZOOM_STEP) {
                        fired = true
                        zoomIn()
                    } else if (!fired && zoom < 1 / ZOOM_STEP) {
                        fired = true
                        zoomOut()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleSwitch(scale: TimeScale, onScale: (TimeScale) -> Unit) {
    val options = listOf(TimeScale.DAY to "День", TimeScale.WEEK to "Неделя", TimeScale.MONTH to "Месяц", TimeScale.YEAR to "Год")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = scale == value,
                onClick = { onScale(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            "$count ${plural(count, "заметка", "заметки", "заметок")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FolderChips(folders: List<String>, hidden: Set<String>, onOpen: (String) -> Unit, onToggleAi: (String, Boolean) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        folders.forEach { path ->
            var menu by remember { mutableStateOf(false) }
            val isHidden = path in hidden
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { onOpen(path) }, onLongClick = { menu = true })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(VaultPaths.name(path), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isHidden) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Lock, "Скрыто от AI", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isHidden) "Показать папку AI" else "Скрыть папку от AI") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = { menu = false; onToggleAi(path, !isHidden) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    meta: NoteMeta,
    subtitle: String,
    /** [subtitle] was written by AI: the gist on the first line, then facts; marked so it is not mistaken for the note's text. */
    aiPreview: Boolean,
    pinned: Boolean,
    time: String,
    showFolder: Boolean,
    actions: NoteActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { actions.onOpen(meta) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            KindIcon(meta, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title and marks take the free width, so the time always sits at the right edge.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(meta.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Marks(meta, pinned)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    if (aiPreview) AiPreview(subtitle) else PlainPreview(subtitle)
                }
                if (showFolder && meta.folder.isNotEmpty()) FolderLabel(meta.folder)
            }
        }
        NoteMenu(meta, pinned, menu, onDismiss = { menu = false }, actions)
    }
}

/** The gist stands out like a headline, the facts follow in small print: the meaning reads at a glance. */
@Composable
private fun AiPreview(text: String) {
    val lines = text.lines()
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.AutoAwesome,
            "Описание от AI",
            Modifier.padding(top = 3.dp, end = 6.dp).size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(
                lines.first(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lines.drop(1).take(AiPreviews.MAX_LINES - 1).forEach { fact ->
                Text(
                    "· $fact",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlainPreview(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FolderLabel(folder: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A zoomed-out note: title and gist for months, title alone for years. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteTile(meta: NoteMeta, gist: String?, aiPreview: Boolean, time: String, dense: Boolean, actions: NoteActions) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(start = if (dense) 4.dp else 6.dp, end = if (dense) 4.dp else 6.dp, bottom = if (dense) 8.dp else 12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (dense) 72.dp else 128.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .combinedClickable(onClick = { actions.onOpen(meta) }, onLongClick = { menu = true })
                .padding(if (dense) 8.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindIcon(meta, Modifier.size(if (dense) 14.dp else 16.dp))
                Spacer(Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                meta.title,
                style = if (dense) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                maxLines = if (dense) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!gist.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    gist,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (aiPreview) FontWeight.Medium else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NoteMenu(meta, pinned = false, menu, onDismiss = { menu = false }, actions)
    }
}

@Composable
private fun KindIcon(meta: NoteMeta, modifier: Modifier = Modifier) {
    Icon(
        if (meta.kind == NoteKind.BOARD) Icons.Outlined.ViewColumn else Icons.Outlined.Description,
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun Marks(meta: NoteMeta, pinned: Boolean) {
    if (pinned) {
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.PushPin, "Закреплено", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    }
    if (meta.hiddenFromAi) {
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.Lock, "Скрыто от AI", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteMenu(meta: NoteMeta, pinned: Boolean, expanded: Boolean, onDismiss: () -> Unit, actions: NoteActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(if (pinned) "Открепить" else "Закрепить") }, onClick = { onDismiss(); actions.onPin(meta) })
        DropdownMenuItem(text = { Text(if (meta.hiddenFromAi) "Показать AI" else "Скрыть от AI") }, onClick = { onDismiss(); actions.onToggleAi(meta) })
        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { onDismiss(); actions.onRename(meta) })
        DropdownMenuItem(text = { Text("Переместить") }, onClick = { onDismiss(); actions.onMove(meta) })
        DropdownMenuItem(text = { Text("Удалить") }, onClick = { onDismiss(); actions.onDelete(meta) })
    }
}

/** "Сегодня", "Эта неделя", "Октябрь", "2025"… */
private fun groupTitle(start: LocalDate, scale: TimeScale, today: LocalDate): String = when (scale) {
    TimeScale.DAY -> when (start) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> Dates.long(start) + if (start.year != today.year) " ${start.year}" else ""
    }
    TimeScale.WEEK -> {
        val thisWeek = today.minusDays(today.dayOfWeek.value - 1L)
        val end = start.plusDays(6)
        when (start) {
            thisWeek -> "Эта неделя"
            thisWeek.minusWeeks(1) -> "Прошлая неделя"
            else -> (if (start.month == end.month) "${start.dayOfMonth}–${Dates.dayMonthLong(end)}" else "${Dates.dayMonth(start)} – ${Dates.dayMonth(end)}") +
                if (end.year != today.year) " ${end.year}" else ""
        }
    }
    TimeScale.MONTH -> Dates.monthYear(start, today)
    TimeScale.YEAR -> start.year.toString()
}

/** Inside a day group the hour is enough; wider groups need the day. */
private fun timeLabel(millis: Long, scale: TimeScale): String {
    val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return when (scale) {
        TimeScale.DAY -> Dates.timeOf(dateTime.toLocalTime())
        TimeScale.WEEK -> Dates.weekdayShort(dateTime.toLocalDate()) + " " + Dates.timeOf(dateTime.toLocalTime())
        TimeScale.MONTH, TimeScale.YEAR -> Dates.dayMonth(dateTime.toLocalDate())
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun MoveDialog(folders: List<String>, current: String, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переместить в папку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                folders.forEach { folder ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = folder == selected, onClick = { selected = folder }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = folder == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (folder.isEmpty()) "Корень хранилища" else folder)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(selected); onDismiss() }, enabled = selected != current) { Text("Переместить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private const val GRID_COLUMNS = 6
private const val ZOOM_STEP = 1.25f
