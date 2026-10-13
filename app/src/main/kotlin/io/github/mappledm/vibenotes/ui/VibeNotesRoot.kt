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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

const val TAB_INBOX = 0
const val TAB_WEEK = 1
const val TAB_TASKS = 2
const val TAB_NOTES = 3

private data class Tab(val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("Входящие", Icons.Outlined.Inbox),
    Tab("Неделя", Icons.Outlined.CalendarMonth),
    Tab("Задачи", Icons.Outlined.ViewColumn),
    Tab("Заметки", Icons.Outlined.Folder),
)

@Serializable object HomeRoute
@Serializable data class NotesRoute(val folder: String = "")
@Serializable data class EditorRoute(val path: String? = null, val folder: String = "")
@Serializable data class SettingsRoute(val section: String? = null)

@Composable
fun VibeNotesRoot(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val vault by container.vault.collectAsStateWithLifecycle()
    VibeTheme(settings.theme, settings.palette) {
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
    val pager = rememberPagerState(initialPage = TAB_INBOX) { Tabs.size }
    val backStack by nav.currentBackStackEntryAsState()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()

    /** Back to the swipeable tabs, on [tab]. */
    fun goHome(tab: Int) {
        scope.launch { drawerState.close() }
        if (backStack?.destination?.hasRoute<HomeRoute>() != true) nav.popBackStack<HomeRoute>(inclusive = false)
        scope.launch { pager.animateScrollToPage(tab) }
    }

    fun openBoard(path: String) {
        container.settings.setDefaultBoard(path)
        goHome(TAB_TASKS)
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val openNote: (String) -> Unit = { path ->
        scope.launch { drawerState.close() }
        nav.navigate(EditorRoute(path))
    }

    LaunchedEffect(pendingShare) {
        if (pendingShare != null) goHome(TAB_INBOX)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Horizontal swipes switch tabs; the drawer opens from the menu button.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppDrawer(
                container = container,
                vault = vault,
                onOpenBoard = ::openBoard,
                onOpenNote = openNote,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    nav.navigate(SettingsRoute()) { launchSingleTop = true }
                },
            )
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable<HomeRoute> {
                HomeTabs(
                    container = container,
                    vault = vault,
                    pager = pager,
                    openDrawer = openDrawer,
                    openNote = openNote,
                    openBoard = ::openBoard,
                    openFolder = { nav.navigate(NotesRoute(it)) },
                    newNote = { folder -> nav.navigate(EditorRoute(null, folder)) },
                    openSettings = { nav.navigate(SettingsRoute()) },
                )
            }
            composable<NotesRoute> { entry ->
                val route = entry.toRoute<NotesRoute>()
                NotesScreen(
                    folder = route.folder,
                    onOpenDrawer = openDrawer,
                    onBack = { nav.popBackStack() },
                    onOpenFolder = { nav.navigate(NotesRoute(it)) },
                    onOpenNote = openNote,
                    onOpenBoard = ::openBoard,
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
            composable<SettingsRoute> { entry ->
                SettingsScreen(
                    section = entry.toRoute<SettingsRoute>().section,
                    onBack = { nav.popBackStack() },
                    onOpenSection = { nav.navigate(SettingsRoute(it)) },
                    onOpenNote = openNote,
                )
            }
        }
    }
}

/** Inbox · Week · Tasks · Notes: swipe left/right or tap the bottom bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeTabs(
    container: AppContainer,
    vault: Vault,
    pager: PagerState,
    openDrawer: () -> Unit,
    openNote: (String) -> Unit,
    openBoard: (String) -> Unit,
    openFolder: (String) -> Unit,
    newNote: (String) -> Unit,
    openSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val index by vault.notes.index.collectAsStateWithLifecycle()

    BackHandler(enabled = pager.currentPage != TAB_INBOX) {
        scope.launch { pager.animateScrollToPage(TAB_INBOX) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            key = { it },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { page ->
            when (page) {
                TAB_INBOX -> InboxScreen(
                    onOpenDrawer = openDrawer,
                    onOpenNote = openNote,
                    onOpenBoard = openBoard,
                    onOpenSettings = openSettings,
                    onSwitchTab = { tab -> scope.launch { pager.animateScrollToPage(tab) } },
                )
                TAB_WEEK -> WeekScreen(onOpenDrawer = openDrawer, onOpenBoard = openBoard)
                TAB_TASKS -> key(settings.defaultBoard) {
                    BoardScreen(
                        path = settings.defaultBoard,
                        onOpenDrawer = openDrawer,
                        onOpenAsText = openNote,
                        boards = index.boards.sortedBy { it.title.lowercase() },
                        onSwitchBoard = { container.settings.setDefaultBoard(it) },
                        createIfMissing = true,
                    )
                }
                else -> NotesScreen(
                    folder = "",
                    onOpenDrawer = openDrawer,
                    onBack = {},
                    onOpenFolder = openFolder,
                    onOpenNote = openNote,
                    onOpenBoard = openBoard,
                    onNewNote = newNote,
                )
            }
        }
    }
}

@Composable
private fun AppDrawer(
    container: AppContainer,
    vault: Vault,
    onOpenBoard: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
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

            DrawerSection("Доски")
            val boards = index.boards.sortedBy { it.title.lowercase() }
            if (boards.isEmpty()) {
                NavigationDrawerItem(
                    label = { Text(VaultPaths.baseName(settings.defaultBoard)) },
                    icon = { Icon(Icons.Outlined.ViewColumn, null) },
                    selected = false,
                    onClick = { onOpenBoard(settings.defaultBoard) },
                )
            }
            boards.forEach { board ->
                NavigationDrawerItem(
                    label = { Text(board.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Outlined.ViewColumn, null) },
                    selected = board.path == settings.defaultBoard,
                    onClick = { onOpenBoard(board.path) },
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
                onClick = onOpenSettings,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (newBoard) {
        TextInputDialog("Новая доска", "", "Создать", onDismiss = { newBoard = false }) { title ->
            scope.launch {
                runCatching { vault.kanban.createBoard("", title) }
                    .onSuccess { onOpenBoard(it.path) }
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
