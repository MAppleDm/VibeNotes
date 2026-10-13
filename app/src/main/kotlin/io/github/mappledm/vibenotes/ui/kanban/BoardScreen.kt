package io.github.mappledm.vibenotes.ui.kanban

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.kanban.CardRef
import io.github.mappledm.vibenotes.core.kanban.KanbanBoard
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.kanban.KanbanLane
import io.github.mappledm.vibenotes.ui.components.DatePickerModal
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.notes.TextInputDialog
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    path: String,
    onOpenDrawer: () -> Unit,
    onOpenAsText: (String) -> Unit,
) {
    val vm: BoardViewModel = viewModel(key = "board:$path", factory = containerFactory { BoardViewModel(it, path) })
    val board by vm.board.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var addingLane by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardRef?>(null) }

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
                title = { Text(vm.title) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Меню") } },
                actions = {
                    IconButton(onClick = { addingLane = true }) { Icon(Icons.Filled.Add, "Новая колонка") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Открыть как Markdown") }, onClick = { menu = false; onOpenAsText(path) })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = board
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val listState = rememberLazyListState()
        val laneWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).coerceAtMost(340f).dp
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            itemsIndexed(current.lanes, key = { index, lane -> "$index:${lane.title}" }) { laneIndex, lane ->
                LaneColumn(
                    lane = lane,
                    laneIndex = laneIndex,
                    width = laneWidth,
                    onAdd = { vm.addCard(laneIndex, it) },
                    onOpenCard = { cardIndex, card -> editing = CardRef(laneIndex, cardIndex, card.text) },
                    onCheck = { cardIndex, card, checked -> vm.setChecked(CardRef(laneIndex, cardIndex, card.text), checked) },
                    onRename = { vm.renameLane(laneIndex, it) },
                    onDelete = { vm.deleteLane(laneIndex) },
                )
            }
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
private fun LaneColumn(
    lane: KanbanLane,
    laneIndex: Int,
    width: androidx.compose.ui.unit.Dp,
    onAdd: (String) -> Unit,
    onOpenCard: (Int, KanbanCard) -> Unit,
    onCheck: (Int, KanbanCard, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .width(width)
            .fillMaxHeight(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lane.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${lane.cards.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(lane.cards, key = { index, card -> "$laneIndex:$index:${card.text}" }) { index, card ->
                    CardItem(card, lane.isComplete, onClick = { onOpenCard(index, card) }, onCheck = { onCheck(index, card, it) })
                }
            }
            if (adding) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Новая карточка") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (draft.isBlank()) adding = false else {
                            onAdd(draft)
                            draft = ""
                        }
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = { adding = false; draft = "" }) { Text("Готово") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAdd(draft); draft = "" }, enabled = draft.isNotBlank()) { Text("Добавить") }
                }
            } else {
                TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Карточка")
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog("Название колонки", lane.title, "Сохранить", onDismiss = { renaming = false }, onConfirm = onRename)
    }
}

@Composable
private fun CardItem(card: KanbanCard, inCompleteLane: Boolean, onClick: () -> Unit, onCheck: (Boolean) -> Unit) {
    val today = LocalDate.now()
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = card.checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                val done = card.checked || inCompleteLane
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                card.dueDate?.let { due ->
                    val overdue = !done && due.isBefore(today)
                    val soon = !done && !overdue && !due.isAfter(today.plusDays(1))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        val color = when {
                            overdue -> MaterialTheme.colorScheme.error
                            soon -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
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
