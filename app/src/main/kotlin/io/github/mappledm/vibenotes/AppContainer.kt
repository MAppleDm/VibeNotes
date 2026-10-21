package io.github.mappledm.vibenotes

import android.app.Application
import android.net.Uri
import io.github.mappledm.vibenotes.core.agent.ActionExecutor
import io.github.mappledm.vibenotes.core.agent.AgentOptions
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.AssistantContext
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.BoardInfo
import io.github.mappledm.vibenotes.core.agent.DataAccess
import io.github.mappledm.vibenotes.core.agent.DefaultActionExecutor
import io.github.mappledm.vibenotes.core.agent.ToolRegistry
import io.github.mappledm.vibenotes.core.ai.OpenAiCompatibleClient
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.inbox.ChatStore
import io.github.mappledm.vibenotes.core.inbox.InboxService
import io.github.mappledm.vibenotes.core.kanban.KanbanFormat
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.preview.AiPreviews
import io.github.mappledm.vibenotes.core.storage.FileStore
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.data.AppUpdater
import io.github.mappledm.vibenotes.data.CustomFontStore
import io.github.mappledm.vibenotes.data.SafFileStore
import io.github.mappledm.vibenotes.data.SecretStore
import io.github.mappledm.vibenotes.data.SettingsRepository
import io.github.mappledm.vibenotes.data.StorageLocation
import io.github.mappledm.vibenotes.data.TaskReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Everything that depends on the chosen notes folder. Recreated when the folder changes. */
class Vault(
    val id: String,
    val name: String,
    val notes: NotesRepository,
    val kanban: KanbanRepository,
    val executor: ActionExecutor,
    val inbox: InboxService,
    val previews: AiPreviews,
)

/**
 * Manual dependency wiring. The app is small enough that a DI framework would only add weight.
 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val secrets = SecretStore(app)
    val fonts = CustomFontStore(app)
    val calendar = AndroidCalendarGateway(app)
    val llm = OpenAiCompatibleClient()
    val updater = AppUpdater(app, settings, appScope)
    private val chatStore = ChatStore(File(app.filesDir, "inbox.json"))

    /** Text shared into the app from other apps, waiting to be put into the Inbox composer. */
    val pendingShare = MutableStateFlow<String?>(null)

    private val _vault = MutableStateFlow<Vault?>(null)
    /** Background work of the current vault; cancelled when another folder is opened. */
    private var vaultScope: CoroutineScope? = null
    val vault: StateFlow<Vault?> = _vault.asStateFlow()

    /** Stack trace of the previous crash, shown once so it can be copied into a bug report. */
    val lastCrash = MutableStateFlow(
        runCatching { File(app.filesDir, VibeNotesApp.CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull(),
    )

    fun dismissCrash() {
        runCatching { File(app.filesDir, VibeNotesApp.CRASH_FILE).delete() }
        lastCrash.value = null
    }

    /** Set when a previously chosen folder is no longer accessible. */
    val storageProblem = MutableStateFlow<String?>(null)

    init {
        // Request logs of an earlier build ("under the hood" view): private data, no longer used.
        runCatching { File(app.filesDir, "traces.json").delete() }
        openVault(settings.current.storage)
        appScope.launch { clearChatIfNewDay() }
        appScope.launch {
            settings.state.collect { runCatching { TaskReminderScheduler.schedule(app, it) } }
        }
    }

    fun requireVault(): Vault = _vault.value ?: error("Хранилище не выбрано")

    /**
     * The chat is a scratch pad for today: once the calendar day changes, yesterday's
     * conversation is dropped. Notes, tasks and events are not affected.
     */
    suspend fun clearChatIfNewDay() {
        chatStore.load()
        val today = LocalDate.now().toEpochDay()
        val last = settings.current.chatClearedDay
        if (last == today) return
        // First run after installing/updating: keep the chat that is already there and
        // start the daily cycle today. Otherwise an update would silently erase it.
        if (last == null) {
            settings.setChatClearedDay(today)
            return
        }
        chatStore.clear()
        settings.setChatClearedDay(today)
    }

    fun chooseStorage(location: StorageLocation) {
        if (location is StorageLocation.Folder) {
            val uri = Uri.parse(location.treeUri)
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.setStorage(location)
        openVault(location)
    }

    private fun openVault(location: StorageLocation?) {
        vaultScope?.cancel()
        vaultScope = null
        val store: FileStore = when (location) {
            null -> {
                _vault.value = null
                return
            }
            StorageLocation.Internal -> LocalFileStore(File(app.filesDir, "notes").apply { mkdirs() })
            is StorageLocation.Folder -> {
                val uri = Uri.parse(location.treeUri)
                if (!SafFileStore.hasPermission(app, uri)) {
                    storageProblem.value = "Нет доступа к выбранной папке. Выберите её снова."
                    _vault.value = null
                    return
                }
                SafFileStore(app, uri)
            }
        }
        storageProblem.value = null
        val notes = NotesRepository(store)
        val kanban = KanbanRepository(notes)
        val executor = DefaultActionExecutor(notes, kanban, calendar) { ZoneId.systemDefault() }
        val tools = ToolRegistry(notes, kanban, calendar)
        val engine = AssistantEngine(llm, tools)
        val inbox = InboxService(
            store = chatStore,
            engine = engine,
            executor = executor,
            notes = notes,
            providerConfig = { withContext(Dispatchers.IO) { providerConfig() } },
            contextProvider = { assistantContext(notes) },
            inboxNotePath = { settings.current.inboxNote },
            historySize = { settings.current.historySize },
            autoApply = { settings.current.autoApply.mapNotNull { name -> runCatching { ProposalKind.valueOf(name) }.getOrNull() }.toSet() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { vaultScope = it }
        val previews = AiPreviews(
            notes = notes,
            llm = llm,
            // One cache per folder: the same path in another vault is another note.
            cacheFile = File(app.filesDir, "previews/${AiPreviews.hash(location.encode())}.json"),
            config = { withContext(Dispatchers.IO) { providerConfig() } },
            enabled = ::aiPreviewsEnabled,
            scope = scope,
        )
        _vault.value = Vault(UUID.randomUUID().toString(), store.displayName, notes, kanban, executor, inbox, previews)
        appScope.launch { runCatching { notes.refresh() } }
    }

    /** Previews obey the assistant's "notes" access switch: when it is off, no note text goes to AI. */
    private fun aiPreviewsEnabled(): Boolean {
        val s = settings.current
        return s.aiPreviews && DataAccess.NOTES.tools.none { it in s.disabledTools }
    }

    /** Null when no API key is stored: the Inbox then works as a plain capture box. */
    fun providerConfig(): ProviderConfig? {
        val key = secrets.apiKey() ?: return null
        val s = settings.current
        return ProviderConfig(s.baseUrl, s.model, key)
    }

    private suspend fun assistantContext(notes: NotesRepository): AssistantContext {
        val s = settings.current
        val index = notes.index.value
        // Private boards and folders (.aiignore) are not even named to the model.
        val boards = index.boards.filterNot { it.hiddenFromAi }.mapNotNull { meta ->
            val content = notes.cachedContent(meta.path) ?: return@mapNotNull null
            BoardInfo(meta.path, meta.title, KanbanFormat.parse(content).lanes.map { it.title })
        }
        val calendars = if (calendar.hasPermission()) runCatching { calendar.writableCalendars() }.getOrNull() else null
        // With several calendars and no saved choice the card asks the user instead of guessing.
        val defaultCalendar = s.defaultCalendarId?.takeIf { id -> calendars?.any { it.id == id } ?: true }
            ?: calendars?.singleOrNull()?.id
        return AssistantContext(
            now = ZonedDateTime.now(),
            boards = boards,
            defaultBoardPath = s.defaultBoard,
            inboxNotePath = s.inboxNote,
            folders = index.folders.filterNot { it in index.hiddenFolders },
            calendars = calendars,
            defaultCalendarId = defaultCalendar,
            defaultEventMinutes = s.eventMinutes,
            defaultReminderMinutes = s.reminderMinutes,
            options = AgentOptions(disabledTools = s.disabledTools, customInstructions = customInstructions(notes)),
        )
    }

    /** The user's AGENTS.md (if enabled and not hidden from AI by .aiignore). */
    private suspend fun customInstructions(notes: NotesRepository): String? {
        val s = settings.current
        if (!s.useInstructions || notes.isHiddenFromAi(s.instructionsFile)) return null
        return runCatching { notes.read(s.instructionsFile) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
