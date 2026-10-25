package io.github.mappledm.vibenotes.ui.editor

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IntegrationInstructions
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.ui.components.MarkdownView
import io.github.mappledm.vibenotes.ui.components.OnPause
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import io.github.mappledm.vibenotes.ui.theme.MonoStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material.icons.outlined.Lock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    path: String?,
    folder: String,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val vm: EditorViewModel = viewModel(factory = containerFactory { EditorViewModel(it, path, folder) })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contentFocus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    OnResume { vm.reloadIfChangedExternally() }
    OnPause { vm.saveNow() }

    LaunchedEffect(vm.closed) { if (vm.closed) onBack() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            // Cleared after showing: clearing first would change the key and cancel the snackbar.
            try {
                snackbar.showSnackbar(it)
            } finally {
                vm.noticeShown(it)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (path == null) runCatching { contentFocus.requestFocus() }
    }

    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val transformation = remember(colors, extra) {
        MarkdownTransformation(
            EditorColors(
                text = colors.onSurface,
                muted = colors.onSurfaceVariant,
                syntax = extra.syntax,
                accent = colors.primary,
                codeBackground = extra.codeBackground,
            ),
        )
    }

    fun handleShortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
        when (event.key) {
            Key.B -> vm.bold()
            Key.I -> vm.italic()
            Key.K -> vm.link()
            Key.E -> if (vm.isMarkdown) vm.togglePreview()
            Key.S -> vm.saveNow()
            Key.Y -> vm.redo()
            Key.Z -> if (event.isShiftPressed) vm.redo() else vm.undo()
            else -> return false
        }
        return true
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent(::handleShortcut),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val s = vm.status) {
                            SaveStatus.Saved -> if (vm.isNew) "" else "Сохранено"
                            SaveStatus.Pending -> "Сохранение…"
                            is SaveStatus.Failed -> s.message
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (vm.status is SaveStatus.Failed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (vm.isMarkdown) {
                        IconButton(onClick = vm::togglePreview) {
                            if (vm.preview) Icon(Icons.Outlined.Edit, "Редактировать") else Icon(Icons.Outlined.Visibility, "Просмотр")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!vm.isNew) {
                                DropdownMenuItem(text = { Text(if (vm.isPinned) "Открепить" else "Закрепить") }, onClick = { menu = false; vm.togglePin() })
                                DropdownMenuItem(
                                    text = { Text(if (vm.hiddenFromAi) "Показать AI" else "Скрыть от AI") },
                                    leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                    onClick = { menu = false; vm.toggleHiddenFromAi() },
                                )
                            }
                            DropdownMenuItem(text = { Text("Поделиться текстом") }, onClick = {
                                menu = false
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, vm.title)
                                    .putExtra(Intent.EXTRA_TEXT, vm.content.text)
                                context.startActivity(Intent.createChooser(send, vm.title))
                            })
                            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!vm.preview && vm.isMarkdown) FormattingBar(vm)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (vm.missing) {
                Text("Файл не найден — возможно, его удалили или переименовали вне приложения.", color = colors.error, modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            Box(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                if (vm.title.isEmpty()) {
                    Text("Без названия", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = vm.title,
                    onValueChange = vm::onTitleChange,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        vm.commitTitle()
                        vm.showPreview(false)
                        runCatching { contentFocus.requestFocus() }
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused && !vm.isNew) vm.commitTitle() },
                )
            }
            if (vm.hiddenFromAi) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Скрыто от AI — ассистент не видит эту заметку", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            if (vm.preview) {
                if (vm.content.text.isBlank()) {
                    Text("Пустая заметка. Нажмите ✎, чтобы писать.", color = colors.onSurfaceVariant)
                } else {
                    MarkdownView(
                        source = vm.content.text,
                        onToggleTask = vm::toggleTask,
                        onWikiLink = { target -> scope.launch { vm.resolveOrCreateLink(target)?.let(onOpenNote) } },
                    )
                }
            } else if (vm.loaded) {
                Box {
                    if (vm.content.text.isEmpty()) {
                        Text(
                            if (vm.isMarkdown) "Начните писать… Markdown поддерживается" else "Обычный текст, например KEY=value",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = vm.content,
                        onValueChange = vm::onContentChange,
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = if (vm.isMarkdown) transformation else VisualTransformation.None,
                        textStyle = if (vm.isMarkdown) MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface) else MonoStyle.copy(color = colors.onSurface),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 320.dp)
                            .focusRequester(contentFocus),
                    )
                }
            }
            Spacer(Modifier.height(160.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить заметку?") },
            text = { Text("Файл будет перемещён в папку .trash внутри хранилища.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FormattingBar(vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                ToolButton(Icons.AutoMirrored.Filled.Undo, "Отменить", vm.canUndo, vm::undo)
                ToolButton(Icons.AutoMirrored.Filled.Redo, "Повторить", vm.canRedo, vm::redo)
                ToolButton(Icons.Filled.Title, "Заголовок", onClick = vm::heading)
                ToolButton(Icons.Filled.FormatBold, "Жирный", onClick = vm::bold)
                ToolButton(Icons.Filled.FormatItalic, "Курсив", onClick = vm::italic)
                ToolButton(Icons.Outlined.CheckBox, "Задача", onClick = vm::task)
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Список", onClick = vm::bullet)
                ToolButton(Icons.Filled.FormatListNumbered, "Нумерованный список", onClick = vm::numbered)
                ToolButton(Icons.Filled.Link, "Ссылка на заметку", onClick = vm::link)
                ToolButton(Icons.Filled.FormatQuote, "Цитата", onClick = vm::quote)
                ToolButton(Icons.Filled.FormatStrikethrough, "Зачеркнуть", onClick = vm::strike)
                ToolButton(Icons.Filled.Code, "Код", onClick = vm::inlineCode)
                ToolButton(Icons.Outlined.IntegrationInstructions, "Блок кода", onClick = vm::codeBlock)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description)
    }
}
