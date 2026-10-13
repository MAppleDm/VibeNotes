package io.github.mappledm.vibenotes.ui.kanban

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.kanban.CardRef
import io.github.mappledm.vibenotes.core.kanban.KanbanBoard
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.kanban.KanbanLane
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.ui.components.DatePickerModal
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.notes.TextInputDialog
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import java.time.LocalDate

/**
 * A board as vertical sections (one per column) instead of side-by-side columns:
 * readable on a phone, one-handed, and horizontal swipes stay free for switching tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    path: String,
    onOpenSettings: () -> Unit,
    onOpenAsText: (String) -> Unit,
    boards: List<NoteMeta> = emptyList(),
    onSwitchBoard: ((String) -> Unit)? = null,
    createIfMissing: Boolean = false,
) {
    val vm: BoardViewModel = viewModel(key = "board:$path", factory = containerFactory { BoardViewModel(it, path, createIfMissing) })
    val board by vm.board.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var boardMenu by remember { mutableStateOf(false) }
    var addingLane by remember { mutableStateOf(false) }
    var newBoard by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardRef?>(null) }
    // Collapsed state per column title; "done" columns start collapsed.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    OnResume { vm.load() }
    LaunchedEffect(error) {
        error?.let {
            vm.errorShown()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onSwitchBoard != null) Modifier.clip(MaterialTheme.shapes.small).clickable { boardMenu = true } else Modifier,
                        ) {
                            Text(vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (onSwitchBoard != null) Icon(Icons.Filled.ArrowDropDown, "Доски")
                        }
                        DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                            boards.forEach { meta ->
                                DropdownMenuItem(
                                    text = { Text(meta.title) },
                                    leadingIcon = { Icon(Icons.Outlined.ViewColumn, null) },
                                    onClick = { boardMenu = false; onSwitchBoard?.invoke(meta.path) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Новая доска") },
                                leadingIcon = { Icon(Icons.Filled.Add, null) },
                                onClick = { boardMenu = false; newBoard = true },
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Новая колонка") }, onClick = { menu = false; addingLane = true })
                            DropdownMenuItem(text = { Text("Открыть как Markdown") }, onClick = { menu = false; onOpenAsText(path) })
                        }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Настройки") }
                },
            )
        },
    ) { padding ->
        val current = board
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val firstOpenLane = current.lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 } ?: 0
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "quick-add") {
                QuickAdd(
                    placeholder = if (current.lanes.isEmpty()) "Сначала добавьте колонку" else "Новая задача в «${current.lanes.getOrNull(firstOpenLane)?.title.orEmpty()}»",
                    enabled = current.lanes.isNotEmpty(),
                    onAdd = { vm.addCard(firstOpenLane, it) },
                )
            }
            current.lanes.forEachIndexed { laneIndex, lane ->
                val isCollapsed = collapsed[lane.title] ?: lane.isComplete
                item(key = "lane:$laneIndex:${lane.title}") {
                    LaneHeader(
                        lane = lane,
                        laneIndex = laneIndex,
                        collapsed = isCollapsed,
                        onToggle = { collapsed[lane.title] = !isCollapsed },
                        onRename = { vm.renameLane(laneIndex, it) },
                        onDelete = { vm.deleteLane(laneIndex) },
                    )
                }
                if (!isCollapsed) {
                    itemsIndexed(lane.cards, key = { index, card -> "card:$laneIndex:$index:${card.text}" }) { index, card ->
                        val next = (laneIndex + 1).takeIf { it < current.lanes.size }
                        CardRow(
                            card = card,
                            inCompleteLane = lane.isComplete,
                            nextLaneTitle = next?.let { current.lanes[it].title },
                            onClick = { editing = CardRef(laneIndex, index, card.text) },
                            onCheck = { vm.setChecked(CardRef(laneIndex, index, card.text), it) },
                            onMoveNext = { next?.let { vm.move(CardRef(laneIndex, index, card.text), it) } },
                        )
                    }
                    if (lane.cards.isEmpty()) {
                        item(key = "empty:$laneIndex") {
                            Text(
                                "Пусто",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (newBoard) {
        TextInputDialog("Новая доска", "", "Создать", onDismiss = { newBoard = false }) { title ->
            vm.createBoard(title) { created -> onSwitchBoard?.invoke(created) }
        }
    }
    if (addingLane) {
        TextInputDialog("Новая колонка", "", "Добавить", onDismiss = { addingLane = false }) { vm.addLane(it) }
    }

    val ref = editing
    val card = ref?.let { board?.card(it) }
    if (ref != null && card != null && board != null) {
        CardSheet(
            board = board!!,
            ref = ref,
            card = card,
            onDismiss = { editing = null },
            onSave = { title, due -> vm.updateCard(ref, title, due); editing = null },
            onMove = { lane -> vm.move(ref, lane); editing = null },
            onDelete = { vm.delete(ref); editing = null },
        )
    }
}

@Composable
private fun QuickAdd(placeholder: String, enabled: Boolean, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = { submit() }, enabled = enabled && text.isNotBlank()) { Icon(Icons.Filled.Add, "Добавить задачу") }
    }
}

@Composable
private fun laneColor(lane: KanbanLane, index: Int): Color = when {
    lane.isComplete -> LocalExtraColors.current.success
    index == 0 -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun LaneHeader(
    lane: KanbanLane,
    laneIndex: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(laneColor(lane, laneIndex)),
        )
        Spacer(Modifier.width(10.dp))
        Text(lane.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
            Text(
                "${lane.cards.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            if (collapsed) "Развернуть" else "Свернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия с колонкой") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(
                    text = { Text(if (lane.cards.isEmpty()) "Удалить колонку" else "Удалить (сначала уберите карточки)") },
                    enabled = lane.cards.isEmpty(),
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
    if (renaming) {
        TextInputDialog("Название колонки", lane.title, "Сохранить", onDismiss = { renaming = false }, onConfirm = onRename)
    }
}

@Composable
private fun CardRow(
    card: KanbanCard,
    inCompleteLane: Boolean,
    nextLaneTitle: String?,
    onClick: () -> Unit,
    onCheck: (Boolean) -> Unit,
    onMoveNext: () -> Unit,
) {
    val today = LocalDate.now()
    val done = card.checked || inCompleteLane
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = card.checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                card.dueDate?.let { due ->
                    val overdue = !done && due.isBefore(today)
                    val soon = !done && !overdue && !due.isAfter(today.plusDays(1))
                    val color = when {
                        overdue -> MaterialTheme.colorScheme.error
                        soon -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = color)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            Dates.relativeDay(due, today) + (card.dueTime?.let { ", $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                        )
                    }
                }
            }
            if (nextLaneTitle != null && !inCompleteLane) {
                IconButton(onClick = onMoveNext) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "В «$nextLaneTitle»", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CardSheet(
    board: KanbanBoard,
    ref: CardRef,
    card: KanbanCard,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate?) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(card) { mutableStateOf(card.title) }
    var due by remember(card) { mutableStateOf(card.dueDate) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Карточка") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(due?.let { "Срок: ${Dates.short(it)}" } ?: "Добавить срок")
                }
                if (due != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { due = null }) { Text("Убрать срок") }
                }
            }
            Text("Колонка", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                board.lanes.forEachIndexed { index, lane ->
                    FilterChip(
                        selected = index == ref.lane,
                        onClick = { if (index != ref.lane) onMove(index) },
                        label = { Text(lane.title) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(title, due) }, enabled = title.isNotBlank()) { Text("Сохранить") }
            }
        }
    }

    if (pickDate) {
        DatePickerModal(due ?: LocalDate.now(), { due = it }, { pickDate = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить карточку?") },
            text = { Text(card.title) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
