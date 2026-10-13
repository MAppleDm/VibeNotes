package io.github.mappledm.vibenotes.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.core.notes.NoteKind
import io.github.mappledm.vibenotes.core.notes.NoteMeta
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    folder: String,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenBoard: (String) -> Unit,
    onNewNote: (String) -> Unit,
) {
    val vm: NotesViewModel = viewModel(key = "notes:$folder", factory = containerFactory { NotesViewModel(it, folder) })
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var searching by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteMeta?>(null) }
    var moving by remember { mutableStateOf<NoteMeta?>(null) }
    var deleting by remember { mutableStateOf<NoteMeta?>(null) }

    OnResume { vm.refresh() }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        vm.messageShown()
        val result = snackbar.showSnackbar(
            m.text,
            actionLabel = if (m.trashPath != null) "Отменить" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete(m)
    }

    fun open(meta: NoteMeta) = if (meta.kind == NoteKind.BOARD) onOpenBoard(meta.path) else onOpenNote(meta.path)

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
                    if (folder.isEmpty()) {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Меню") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; vm.setQuery("") }) { Icon(Icons.Outlined.Close, "Закрыть поиск") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Поиск") }
                        IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "Новая папка") }
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
                    NoteRow(
                        meta = hit.note,
                        subtitle = hit.snippet,
                        pinned = hit.note.path in state.pinnedPaths,
                        onClick = { open(hit.note) },
                        onPin = { vm.togglePin(hit.note.path) },
                        onRename = { renaming = hit.note },
                        onMove = { moving = hit.note },
                        onDelete = { deleting = hit.note },
                        showFolder = true,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                if (state.pinned.isNotEmpty()) {
                    item { SectionHeader("Закреплённые") }
                    items(state.pinned, key = { "pin:" + it.path }) { meta ->
                        NoteRow(meta, meta.preview, true, { open(meta) }, { vm.togglePin(meta.path) }, { renaming = meta }, { moving = meta }, { deleting = meta }, showFolder = true)
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                }
                items(state.subfolders, key = { "dir:$it" }) { path ->
                    FolderRow(VaultPaths.name(path)) { onOpenFolder(path) }
                }
                items(state.notes, key = { it.path }) { meta ->
                    NoteRow(meta, meta.preview, meta.path in state.pinnedPaths, { open(meta) }, { vm.togglePin(meta.path) }, { renaming = meta }, { moving = meta }, { deleting = meta })
                }
                if (state.loaded && state.notes.isEmpty() && state.subfolders.isEmpty()) {
                    item { EmptyText(if (folder.isEmpty()) "Заметок пока нет. Нажмите +, чтобы создать первую." else "Папка пуста") }
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
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

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    meta: NoteMeta,
    subtitle: String,
    pinned: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    showFolder: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (meta.kind == NoteKind.BOARD) Icons.Outlined.ViewColumn else Icons.Outlined.Description,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.PushPin, "Закреплено", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Dates.modified(meta.lastModified), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val secondLine = buildString {
                    if (showFolder && meta.folder.isNotEmpty()) append(meta.folder).append(" · ")
                    append(subtitle)
                }
                if (secondLine.isNotBlank()) {
                    Text(secondLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (pinned) "Открепить" else "Закрепить") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Переместить") }, onClick = { menu = false; onMove() })
            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
        }
    }
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
