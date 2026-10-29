package io.github.mappledm.vibenotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.Vault
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ResultTarget
import io.github.mappledm.vibenotes.core.inbox.ChatFocus
import io.github.mappledm.vibenotes.core.inbox.ProposalItem
import io.github.mappledm.vibenotes.core.inbox.ProposalStatus
import io.github.mappledm.vibenotes.core.inbox.isOpen
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import io.github.mappledm.vibenotes.ui.calendar.CalendarScreen
import io.github.mappledm.vibenotes.ui.components.Dates
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.editor.EditorScreen
import io.github.mappledm.vibenotes.ui.inbox.AnswerPanel
import io.github.mappledm.vibenotes.ui.inbox.CardText
import io.github.mappledm.vibenotes.ui.inbox.ChatDialogs
import io.github.mappledm.vibenotes.ui.inbox.Composer
import io.github.mappledm.vibenotes.ui.inbox.InboxScreen
import io.github.mappledm.vibenotes.ui.inbox.InboxViewModel
import io.github.mappledm.vibenotes.ui.inbox.drafts
import io.github.mappledm.vibenotes.ui.inbox.exchange
import io.github.mappledm.vibenotes.ui.inbox.rememberChatController
import io.github.mappledm.vibenotes.ui.kanban.BoardScreen
import io.github.mappledm.vibenotes.ui.notes.NotesScreen
import io.github.mappledm.vibenotes.ui.onboarding.OnboardingScreen
import io.github.mappledm.vibenotes.ui.settings.SettingsScreen
import io.github.mappledm.vibenotes.ui.theme.MonoStyle
import io.github.mappledm.vibenotes.ui.theme.VibeTheme
import io.github.mappledm.vibenotes.ui.theme.appFontFamily
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

const val TAB_INBOX = 0
const val TAB_CALENDAR = 1
const val TAB_TASKS = 2
const val TAB_NOTES = 3

private data class Tab(val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("Входящие", Icons.Outlined.Inbox),
    Tab("Календарь", Icons.Outlined.CalendarMonth),
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
    val fontFamily = remember(settings.font, settings.customFontVersion) { appFontFamily(settings.font, container.fonts) }
    VibeTheme(settings.theme, settings.palette, fontFamily) {
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
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = TAB_INBOX) { Tabs.size }
    val backStack by nav.currentBackStackEntryAsState()
    val pendingShare by container.pendingShare.collectAsStateWithLifecycle()

    /** Back to the swipeable tabs, on [tab]. */
    fun goHome(tab: Int) {
        if (backStack?.destination?.hasRoute<HomeRoute>() != true) nav.popBackStack<HomeRoute>(inclusive = false)
        scope.launch { pager.animateScrollToPage(tab) }
    }

    fun openBoard(path: String) {
        container.settings.setDefaultBoard(path)
        goHome(TAB_TASKS)
    }

    val openNote: (String) -> Unit = { path -> nav.navigate(EditorRoute(path)) }
    val openSettings: () -> Unit = { nav.navigate(SettingsRoute()) { launchSingleTop = true } }

    LaunchedEffect(pendingShare) {
        if (pendingShare != null) goHome(TAB_INBOX)
    }

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
                openNote = openNote,
                openBoard = ::openBoard,
                openFolder = { nav.navigate(NotesRoute(it)) },
                newNote = { folder -> nav.navigate(EditorRoute(null, folder)) },
                openSettings = openSettings,
            )
        }
        composable<NotesRoute> { entry ->
            val route = entry.toRoute<NotesRoute>()
            NotesScreen(
                folder = route.folder,
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

/**
 * Inbox · Calendar · Tasks · Notes: swipe left/right or tap the bottom bar. The message field sits
 * under every tab: a message written on the calendar or a board gets that tab as its context, and
 * its answer opens right above the field, so the result is confirmed where it will appear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeTabs(
    container: AppContainer,
    vault: Vault,
    pager: PagerState,
    openNote: (String) -> Unit,
    openBoard: (String) -> Unit,
    openFolder: (String) -> Unit,
    newNote: (String) -> Unit,
    openSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val index by vault.notes.index.collectAsStateWithLifecycle()
    val inbox: InboxViewModel = viewModel(factory = containerFactory { InboxViewModel(it) })
    val entries by inbox.entries.collectAsStateWithLifecycle()
    val generating by inbox.generating.collectAsStateWithLifecycle()
    val aiConfigured by inbox.aiConfigured.collectAsStateWithLifecycle()
    val drafts = remember(entries) { drafts(entries) }
    // Done and undone cards: the calendar and the board reload when this changes.
    val chatChanges = remember(entries) {
        entries.flatMap { entry ->
            entry.proposals.filter { it.status == ProposalStatus.APPLIED || it.status == ProposalStatus.UNDONE }.map { it.id to it.status }
        }
    }
    // What each tab adds to a message written on it; the chip in the field shows it.
    val focus = remember { mutableStateMapOf<Int, ChatFocus>() }
    var spot by remember { mutableStateOf<Spot?>(null) }
    val composerFocus = remember { FocusRequester() }
    val page = pager.currentPage

    fun goTo(tab: Int) {
        scope.launch { pager.animateScrollToPage(tab) }
    }

    fun setFocus(tab: Int, value: ChatFocus?) {
        if (value == null) focus.remove(tab) else focus[tab] = value
    }

    /** "Открыть" on a card leads to its place in the app: the calendar day, the board card, the note. */
    fun openTarget(item: ProposalItem) {
        when (val target = item.target) {
            is ResultTarget.Note -> openNote(target.path)
            is ResultTarget.Board -> {
                spot = cardTitle(item.proposal)?.let { Spot.Card(target.path, it) }
                openBoard(target.path)
            }
            is ResultTarget.Event -> {
                spot = Spot.Day(CardText.day(item.proposal) ?: LocalDate.now(), target.eventId)
                goTo(TAB_CALENDAR)
            }
            null -> Unit
        }
    }

    fun openTask(task: TaskItem) {
        spot = Spot.Card(task.boardPath, task.card.title)
        openBoard(task.boardPath)
    }

    fun send() {
        inbox.send(focus = focus[page], inPanel = page != TAB_INBOX)
    }

    val chat = rememberChatController(inbox, onOpenTarget = ::openTarget, onOpenNote = openNote)
    val answer = if (page == TAB_INBOX) emptyList() else exchange(entries, inbox.panel)

    LaunchedEffect(Unit) { setFocus(TAB_NOTES, Focus.notes) }
    // Leaving a tab with nothing left to decide closes the answer: it stays in the chat.
    LaunchedEffect(pager.settledPage) {
        val open = exchange(entries, inbox.panel).any { entry ->
            entry.streaming || entry.proposals.any { it.status.isOpen || it.status == ProposalStatus.APPLYING }
        }
        if (!open) inbox.closePanel()
    }

    BackHandler(enabled = page != TAB_INBOX) {
        scope.launch { pager.animateScrollToPage(TAB_INBOX) }
    }
    // Registered later, so Back closes the answer first.
    BackHandler(enabled = answer.isNotEmpty()) { inbox.closePanel() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                AnswerPanel(
                    exchange = answer,
                    chat = chat,
                    onOpenChat = { inbox.closePanel(); goTo(TAB_INBOX) },
                    onClose = inbox::closePanel,
                )
                Composer(
                    vm = inbox,
                    generating = generating,
                    aiConfigured = aiConfigured,
                    focus = focus[page],
                    onClearFocus = { focus.remove(page) },
                    onSend = ::send,
                    focusRequester = composerFocus,
                    modifier = Modifier.imePadding(),
                )
                // While typing, the keyboard takes the place of the tabs.
                if (!WindowInsets.isImeVisible) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tabs.forEachIndexed { i, tab ->
                            NavigationBarItem(
                                selected = page == i,
                                onClick = { goTo(i) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
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
        ) { tab ->
            when (tab) {
                TAB_INBOX -> InboxScreen(chat = chat, onOpenSettings = openSettings)
                TAB_CALENDAR -> CalendarScreen(
                    onOpenSettings = openSettings,
                    onOpenTask = ::openTask,
                    onPlan = { date ->
                        // "+" on a day: the next message is about that day, written right here.
                        // Without AI the message goes into the Inbox note as typed, so the day is written into it.
                        if (aiConfigured) setFocus(TAB_CALENDAR, Focus.day(date)) else inbox.appendToComposer("${Dates.long(date)}: ")
                        composerFocus.requestFocus()
                    },
                    onFocus = { setFocus(TAB_CALENDAR, it) },
                    drafts = drafts,
                    onShowDraft = inbox::showInPanel,
                    spot = spot as? Spot.Day,
                    onSpotShown = { spot = null },
                    chatChanges = chatChanges,
                )
                TAB_TASKS -> key(settings.defaultBoard) {
                    BoardScreen(
                        path = settings.defaultBoard,
                        onOpenSettings = openSettings,
                        onOpenAsText = openNote,
                        boards = index.boards.sortedBy { it.title.lowercase() },
                        onSwitchBoard = { container.settings.setDefaultBoard(it) },
                        createIfMissing = true,
                        onFocus = { setFocus(TAB_TASKS, it) },
                        drafts = drafts,
                        onShowDraft = inbox::showInPanel,
                        spot = spot as? Spot.Card,
                        onSpotShown = { spot = null },
                        chatChanges = chatChanges,
                    )
                }
                else -> NotesScreen(
                    folder = "",
                    onBack = {},
                    onOpenFolder = openFolder,
                    onOpenNote = openNote,
                    onOpenBoard = openBoard,
                    onNewNote = newNote,
                    onOpenSettings = openSettings,
                )
            }
        }
    }
    ChatDialogs(chat)
}

private fun cardTitle(p: ActionProposal): String? = when (p) {
    is ActionProposal.AddTask -> p.title
    is ActionProposal.UpdateTask -> p.after.title
    else -> null
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
