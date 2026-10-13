package io.github.mappledm.vibenotes.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.Vault
import io.github.mappledm.vibenotes.core.storage.VaultPaths
import io.github.mappledm.vibenotes.ui.editor.EditorScreen
import io.github.mappledm.vibenotes.ui.inbox.InboxScreen
import io.github.mappledm.vibenotes.ui.kanban.BoardScreen
import io.github.mappledm.vibenotes.ui.notes.NotesScreen
import io.github.mappledm.vibenotes.ui.notes.TextInputDialog
import io.github.mappledm.vibenotes.ui.onboarding.OnboardingScreen
import io.github.mappledm.vibenotes.ui.settings.SettingsScreen
import io.github.mappledm.vibenotes.ui.theme.VibeTheme
import io.github.mappledm.vibenotes.ui.week.WeekScreen
import kotlinx.coroutines.launch
import io.github.mappledm.vibenotes.ui.theme.MonoStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import kotlinx.serialization.Serializable

@Serializable object InboxRoute
@Serializable object WeekRoute
@Serializable data class NotesRoute(val folder: String = "")
@Serializable data class EditorRoute(val path: String? = null, val folder: String = "")
@Serializable data class BoardRoute(val path: String)
@Serializable object SettingsRoute

@Composable
fun VibeNotesRoot(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val vault by container.vault.collectAsStateWithLifecycle()
    VibeTheme(settings.theme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val current = vault
            if (current == null) {
                OnboardingScreen(container)
            } else {
                // A new folder means new repositories: start a fresh navigation graph.
                key(current.id) { MainNavigation(container, current) }
            }
            val crash by container.lastCrash.collectAsStateWithLifecycle()
            crash?.let { report -> CrashDialog(report, onClose = container::dismissCrash) }
        }
    }
}

@Composable
private fun MainNavigation(container: AppContainer, vault: Vault) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()

    fun openTop(route: Any) {
        scope.launch { drawerState.close() }
        nav.navigate(route) {
            popUpTo<InboxRoute>()
            launchSingleTop = true
        }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    LaunchedEffect(pendingShare) {
        if (pendingShare != null && destination?.hasRoute<InboxRoute>() != true) openTop(InboxRoute)
    }

    val isTopLevel = destination == null ||
        destination.hasRoute<InboxRoute>() || destination.hasRoute<WeekRoute>() ||
        (destination.hasRoute<NotesRoute>() && backStack?.toRoute<NotesRoute>()?.folder.isNullOrEmpty())
    val currentBoard = if (destination?.hasRoute<BoardRoute>() == true) backStack?.toRoute<BoardRoute>()?.path else null

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Boards scroll horizontally, so the drawer opens from the menu button there.
        gesturesEnabled = drawerState.isOpen || isTopLevel,
        drawerContent = {
            AppDrawer(
                container = container,
                vault = vault,
                isInbox = destination?.hasRoute<InboxRoute>() == true,
                isWeek = destination?.hasRoute<WeekRoute>() == true,
                isNotes = destination?.hasRoute<NotesRoute>() == true,
                currentBoard = currentBoard,
                onNavigate = ::openTop,
                onOpenNote = { path ->
                    scope.launch { drawerState.close() }
                    nav.navigate(EditorRoute(path))
                },
            )
        },
    ) {
        NavGraph(nav, openDrawer)
    }
}

@Composable
private fun NavGraph(nav: NavHostController, openDrawer: () -> Unit) {
    val openNote: (String) -> Unit = { nav.navigate(EditorRoute(it)) }
    val openBoard: (String) -> Unit = { nav.navigate(BoardRoute(it)) }
    NavHost(
        navController = nav,
        startDestination = InboxRoute,
        enterTransition = { fadeIn(tween(160)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable<InboxRoute> {
            InboxScreen(
                onOpenDrawer = openDrawer,
                onOpenNote = openNote,
                onOpenBoard = openBoard,
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<WeekRoute> {
            WeekScreen(onOpenDrawer = openDrawer, onOpenBoard = openBoard)
        }
        composable<NotesRoute> { entry ->
            val route = entry.toRoute<NotesRoute>()
            NotesScreen(
                folder = route.folder,
                onOpenDrawer = openDrawer,
                onBack = { nav.popBackStack() },
                onOpenFolder = { nav.navigate(NotesRoute(it)) },
                onOpenNote = openNote,
                onOpenBoard = openBoard,
                onNewNote = { folder -> nav.navigate(EditorRoute(null, folder)) },
            )
        }
        composable<EditorRoute> { entry ->
            val route = entry.toRoute<EditorRoute>()
            EditorScreen(
                path = route.path,
                folder = route.folder,
                onBack = { nav.popBackStack() },
                onOpenNote = openNote,
            )
        }
        composable<BoardRoute> { entry ->
            BoardScreen(
                path = entry.toRoute<BoardRoute>().path,
                onOpenDrawer = openDrawer,
                onOpenAsText = openNote,
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenNote = openNote)
        }
    }
}

@Composable
private fun AppDrawer(
    container: AppContainer,
    vault: Vault,
    isInbox: Boolean,
    isWeek: Boolean,
    isNotes: Boolean,
    currentBoard: String?,
    onNavigate: (Any) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val index by vault.notes.index.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newBoard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalDrawerSheet {
        Column(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("VibeNotes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                vault.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text("Входящие") },
                icon = { Icon(Icons.Outlined.Inbox, null) },
                selected = isInbox,
                onClick = { onNavigate(InboxRoute) },
            )
            NavigationDrawerItem(
                label = { Text("Неделя") },
                icon = { Icon(Icons.Outlined.CalendarMonth, null) },
                selected = isWeek,
                onClick = { onNavigate(WeekRoute) },
            )
            NavigationDrawerItem(
                label = { Text("Заметки") },
                icon = { Icon(Icons.Outlined.Folder, null) },
                selected = isNotes,
                onClick = { onNavigate(NotesRoute()) },
            )

            DrawerSection("Доски")
            val boards = index.boards.sortedBy { it.title.lowercase() }
            if (boards.isEmpty()) {
                NavigationDrawerItem(
                    label = { Text(VaultPaths.baseName(settings.defaultBoard)) },
                    icon = { Icon(Icons.Outlined.ViewColumn, null) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            runCatching { vault.kanban.ensureBoard(settings.defaultBoard) }
                                .onSuccess { onNavigate(BoardRoute(it)) }
                                .onFailure { error = it.message }
                        }
                    },
                )
            }
            boards.forEach { board ->
                NavigationDrawerItem(
                    label = { Text(board.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Outlined.ViewColumn, null) },
                    selected = board.path == currentBoard,
                    onClick = { onNavigate(BoardRoute(board.path)) },
                )
            }
            NavigationDrawerItem(
                label = { Text("Новая доска") },
                icon = { Icon(Icons.Filled.Add, null) },
                selected = false,
                onClick = { newBoard = true },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }

            val pinned = index.notes.filter { it.path in settings.pinned }
            if (pinned.isNotEmpty()) {
                DrawerSection("Закреплённые")
                pinned.forEach { note ->
                    NavigationDrawerItem(
                        label = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(Icons.Outlined.PushPin, null) },
                        selected = false,
                        onClick = { onOpenNote(note.path) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationDrawerItem(
                label = { Text("Настройки") },
                icon = { Icon(Icons.Outlined.Settings, null) },
                selected = false,
                onClick = { onNavigate(SettingsRoute) },
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (newBoard) {
        TextInputDialog("Новая доска", "", "Создать", onDismiss = { newBoard = false }) { title ->
            scope.launch {
                runCatching { vault.kanban.createBoard("", title) }
                    .onSuccess { onNavigate(BoardRoute(it.path)) }
                    .onFailure { error = it.message }
            }
        }
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Приложение закрылось с ошибкой") },
        text = {
            Column {
                Text(
                    "Отчёт хранится только на телефоне. Скопируйте его и пришлите разработчику — это поможет исправить ошибку.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        report.take(6000),
                        style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onClose()
            }) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
