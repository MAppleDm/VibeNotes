package io.github.mappledm.vibenotes.ui.kanban

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.inbox.ChatFocus
import io.github.mappledm.vibenotes.core.kanban.CardRef
import io.github.mappledm.vibenotes.core.kanban.KanbanBoard
import io.github.mappledm.vibenotes.core.kanban.KanbanCard
import io.github.mappledm.vibenotes.core.kanban.KanbanLane
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.ui.components.DatePickerModal
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnChange
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.Focus
import io.github.mappledm.vibenotes.ui.Spot
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.components.dashedBorder
import io.github.mappledm.vibenotes.ui.inbox.Draft
import io.github.mappledm.vibenotes.ui.notes.TextInputDialog
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * A board as vertical sections (one per column, i.e. per status) instead of side-by-side columns:
 * readable on a phone, one-handed, and horizontal swipes stay free for switching tabs.
 *
 * Every card says how to move it on: the checkbox finishes it, ▶ takes it into work, → moves it to the
 * next column, and a long press lists every status plus archive and delete. Finished cards go to the
 * archive (kept in the same file, as in Obsidian Kanban) instead of piling up in "Готово".
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
    onFocus: (ChatFocus?) -> Unit = {},
    drafts: List<Draft> = emptyList(),
    onShowDraft: (String) -> Unit = {},
    spot: Spot.Card? = null,
    onSpotShown: () -> Unit = {},
    chatChanges: Any? = null,
) {
    val vm: BoardViewModel = viewModel(key = "board:$path", factory = containerFactory { BoardViewModel(it, path, createIfMissing) })
    val board by vm.board.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var boardMenu by remember { mutableStateOf(false) }
    var addingLane by remember { mutableStateOf(false) }
    var newBoard by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardRef?>(null) }
    // Collapsed state per column title; "done" columns and the archive start collapsed.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf<String?>(null) }
    // Tasks the chat proposes for this board, shown dashed in their column until confirmed.
    val boardDrafts = remember(drafts, path) {
        drafts.filter { (it.proposal as? ActionProposal.AddTask)?.boardPath?.equals(path, ignoreCase = true) == true }
    }

    LaunchedEffect(vm.title) { onFocus(Focus.board(vm.title)) }
    // "Открыть" on a card in the chat or in the calendar: unfold its column, scroll to it, highlight it.
    LaunchedEffect(spot, board) {
        val target = spot?.takeIf { it.boardPath.equals(path, ignoreCase = true) } ?: return@LaunchedEffect
        val current = board ?: return@LaunchedEffect
        onSpotShown()
        val laneIndex = current.lanes.indexOfFirst { lane -> lane.cards.any { it.title.equals(target.title, ignoreCase = true) } }
        if (laneIndex < 0) return@LaunchedEffect
        val lane = current.lanes[laneIndex]
        val cardIndex = lane.cards.indexOfFirst { it.title.equals(target.title, ignoreCase = true) }
        collapsed[lane.title] = false
        highlighted = lane.cards[cardIndex].text
        val firstOpen = current.lanes.indexOfFirst { !it.isComplete }.takeIf { it >= 0 } ?: 0
        val index = itemIndexOf(current, laneIndex, cardIndex) { i, l ->
            (collapsed[l.title] ?: l.isComplete) to laneDrafts(boardDrafts, current, i, firstOpen).size
        }
        listState.animateScrollToItem(index)
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(HIGHLIGHT_MILLIS)
            highlighted = null
        }
    }

    OnResume { vm.load() }
    // A card confirmed or undone in the answer panel changes what this tab shows.
    OnChange(chatChanges) { vm.load() }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        // Cleared only after the snackbar is gone: clearing first would change the key and cancel the snackbar.
        try {
            val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undo != null) "Отменить" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.undo(m)
        } finally {
            vm.messageShown(m)
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
                            val finished = board?.finishedCount ?: 0
                            DropdownMenuItem(
                                text = { Text(if (finished > 0) "Убрать готовые в архив ($finished)" else "Готовых задач нет") },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                                enabled = finished > 0,
                                onClick = { menu = false; vm.archiveFinished() },
                            )
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
            state = listState,
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
                        onArchiveAll = if (lane.isComplete && lane.cards.isNotEmpty()) vm::archiveFinished else null,
                    )
                }
                if (!isCollapsed) {
                    val laneDrafts = laneDrafts(boardDrafts, current, laneIndex, firstOpenLane)
                    itemsIndexed(laneDrafts, key = { index, draft -> "draft:$laneIndex:$index:${draft.entryId}" }) { _, draft ->
                        DraftCardRow(draft) { onShowDraft(draft.entryId) }
                    }
                    itemsIndexed(lane.cards, key = { index, card -> "card:$laneIndex:$index:${card.text}" }) { index, card ->
                        val ref = CardRef(laneIndex, index, card.text)
                        CardRow(
                            card = card,
                            highlighted = card.text == highlighted,
                            next = nextStep(current, laneIndex, firstOpenLane),
                            board = current,
                            laneIndex = laneIndex,
                            onClick = { editing = ref },
                            onCheck = { vm.setChecked(ref, it) },
                            onNext = { next -> if (next == NextStep.ARCHIVE) vm.archive(ref) else vm.move(ref, laneIndex + 1) },
                            onMove = { vm.move(ref, it) },
                            onArchive = { vm.archive(ref) },
                            onDelete = { vm.delete(ref) },
                        )
                    }
                    if (lane.cards.isEmpty() && laneDrafts.isEmpty()) {
                        item(key = "empty:$laneIndex") {
                            Text(
                                emptyLaneHint(current, laneIndex, firstOpenLane),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
            if (current.archived.isNotEmpty()) {
                val archiveCollapsed = collapsed[ARCHIVE_KEY] ?: true
                item(key = "archive") {
                    ArchiveHeader(current.archived.size, archiveCollapsed) { collapsed[ARCHIVE_KEY] = !archiveCollapsed }
                }
                if (!archiveCollapsed) {
                    // Newest first: the card archived last is the one you are most likely looking for.
                    val newestFirst = current.archived.withIndex().reversed()
                    itemsIndexed(newestFirst, key = { _, (index, card) -> "archived:$index:${card.text}" }) { _, (index, card) ->
                        ArchivedRow(
                            card = card,
                            restoreTo = current.lanes.getOrNull(firstOpenLane)?.title,
                            onRestore = { vm.restoreArchived(index, card) },
                            onDelete = { vm.deleteArchived(index, card) },
                        )
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
            onArchive = { vm.archive(ref); editing = null },
            onDelete = { vm.delete(ref); editing = null },
        )
    }
}

/** Drafts of [laneIndex]: tasks proposed for that column, and those whose column is gone go to the first open one. */
private fun laneDrafts(drafts: List<Draft>, board: KanbanBoard, laneIndex: Int, firstOpenLane: Int): List<Draft> =
    drafts.filter { draft ->
        val lane = (draft.proposal as ActionProposal.AddTask).lane
        val target = board.lanes.indexOfFirst { it.title.equals(lane, ignoreCase = true) }.takeIf { it >= 0 } ?: firstOpenLane
        target == laneIndex
    }

/**
 * Position of a card in the board's list, walking the same items the list shows: the quick-add field,
 * then each column's header and, when unfolded, its drafts and cards (or the empty hint).
 * [laneInfo] gives a column's folded state and its number of drafts.
 */
private fun itemIndexOf(board: KanbanBoard, laneIndex: Int, cardIndex: Int, laneInfo: (Int, KanbanLane) -> Pair<Boolean, Int>): Int {
    var index = 1
    board.lanes.forEachIndexed { i, lane ->
        val (folded, drafts) = laneInfo(i, lane)
        if (i == laneIndex) return index + 1 + drafts + cardIndex
        index += 1
        if (!folded) index += drafts + if (lane.cards.isEmpty() && drafts == 0) 1 else lane.cards.size
    }
    return 0
}

/** A task the chat proposed: dashed until the card is confirmed, a tap shows the card. */
@Composable
private fun DraftCardRow(draft: Draft, onClick: () -> Unit) {
    val p = draft.proposal as ActionProposal.AddTask
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .dashedBorder(color, 12.dp)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge)
            val due = p.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { "срок " + Dates.relativeDay(it) + (p.dueTime?.let { time -> ", $time" } ?: "") }
            Text(
                listOfNotNull("ждёт подтверждения", due).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

/** What the button at the end of a card does: take it into work, move it on, or archive a finished one. */
private enum class NextStep { START, FORWARD, ARCHIVE }

/** The checkbox already finishes a card, so a card right before the done column needs no button. */
private fun nextStep(board: KanbanBoard, laneIndex: Int, firstOpenLane: Int): NextStep? {
    val lane = board.lanes[laneIndex]
    val next = board.lanes.getOrNull(laneIndex + 1)
    return when {
        lane.isComplete -> NextStep.ARCHIVE
        next == null || next.isComplete -> null
        laneIndex == firstOpenLane -> NextStep.START
        else -> NextStep.FORWARD
    }
}

private fun emptyLaneHint(board: KanbanBoard, laneIndex: Int, firstOpenLane: Int): String {
    val lane = board.lanes[laneIndex]
    return when {
        lane.isComplete -> "Пусто. Отмеченные задачи попадают сюда."
        laneIndex == firstOpenLane -> "Пусто. Новая задача — в поле сверху."
        laneIndex == firstOpenLane + 1 -> "Пусто. Нажмите «▶ ${startLabel(lane.title)}» у задачи, чтобы перенести её сюда."
        else -> "Пусто. Нажмите → у задачи в «${board.lanes[laneIndex - 1].title}», чтобы перенести её сюда."
    }
}

private fun startLabel(laneTitle: String): String = if (laneTitle.equals("В работе", ignoreCase = true)) "В работу" else laneTitle

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
    onArchiveAll: (() -> Unit)?,
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
        CountBadge(lane.cards.size)
        Spacer(Modifier.weight(1f))
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            if (collapsed) "Развернуть" else "Свернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия с колонкой") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onArchiveAll?.let { archive ->
                    DropdownMenuItem(
                        text = { Text("Убрать все в архив") },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                        onClick = { menu = false; archive() },
                    )
                }
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
private fun CountBadge(count: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    card: KanbanCard,
    highlighted: Boolean,
    next: NextStep?,
    board: KanbanBoard,
    laneIndex: Int,
    onClick: () -> Unit,
    onCheck: (Boolean) -> Unit,
    onNext: (NextStep) -> Unit,
    onMove: (Int) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val today = LocalDate.now()
    val lane = board.lanes[laneIndex]
    val done = card.checked || lane.isComplete
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        label = "highlight",
    )
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = card.checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                card.dueDate?.let { due -> DueLabel(due, card.dueTime?.toString(), done, today) }
            }
            when (next) {
                NextStep.START -> StartButton(board.lanes[laneIndex + 1].title) { onNext(next) }
                NextStep.FORWARD -> NextButton(Icons.AutoMirrored.Filled.ArrowForward, "В «${board.lanes[laneIndex + 1].title}»") { onNext(next) }
                NextStep.ARCHIVE -> NextButton(Icons.Outlined.Archive, "В архив") { onNext(next) }
                null -> Unit
            }
        }
        // Long press: every status by name, so a card never has to travel column by column.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            board.lanes.forEachIndexed { index, target ->
                DropdownMenuItem(
                    text = { Text(target.title) },
                    leadingIcon = { if (index == laneIndex) Icon(Icons.Filled.Check, "Сейчас здесь") else Spacer(Modifier.size(24.dp)) },
                    enabled = index != laneIndex,
                    onClick = { menu = false; onMove(index) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Изменить или поставить срок") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) }, onClick = { menu = false; onClick() })
            DropdownMenuItem(text = { Text("В архив") }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }, onClick = { menu = false; onArchive() })
            DropdownMenuItem(
                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

/** "▶ В работу": a labelled button, because a bare icon did not say where the card goes. */
@Composable
private fun StartButton(laneTitle: String, onClick: () -> Unit) {
    val label = startLabel(laneTitle)
    Row(
        Modifier
            .padding(end = 6.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClickLabel = "Перенести в «$laneTitle»", onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun NextButton(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = tint) }
}

@Composable
private fun DueLabel(due: LocalDate, time: String?, done: Boolean, today: LocalDate) {
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
            Dates.relativeDay(due, today) + (time?.let { ", $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun ArchiveHeader(count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Inventory2, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("Архив", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        CountBadge(count)
        Spacer(Modifier.weight(1f))
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            if (collapsed) "Показать архив" else "Скрыть архив",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchivedRow(card: KanbanCard, restoreTo: String?, onRestore: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { menu = true }
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (card.checked) TextDecoration.LineThrough else null),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия с задачей архива", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (restoreTo != null) {
                DropdownMenuItem(
                    text = { Text("Вернуть в «$restoreTo»") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                    onClick = { menu = false; onRestore() },
                )
            }
            DropdownMenuItem(
                text = { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
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
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(card) { mutableStateOf(card.title) }
    var due by remember(card) { mutableStateOf(card.dueDate) }
    var pickDate by remember { mutableStateOf(false) }

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
                label = { Text("Задача") },
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
            Text("Статус", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                board.lanes.forEachIndexed { index, lane ->
                    FilterChip(
                        selected = index == ref.lane,
                        onClick = { if (index != ref.lane) onMove(index) },
                        label = { Text(lane.title) },
                        leadingIcon = if (index == ref.lane) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onArchive) {
                    Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("В архив")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(title, due) }, enabled = title.isNotBlank()) { Text("Сохранить") }
            }
        }
    }

    if (pickDate) {
        DatePickerModal(due ?: LocalDate.now(), { due = it }, { pickDate = false })
    }
}

private const val ARCHIVE_KEY = "\u0000archive"
private const val HIGHLIGHT_MILLIS = 2500L
