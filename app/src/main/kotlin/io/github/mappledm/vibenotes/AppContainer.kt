package io.github.mappledm.vibenotes

import android.app.Application
import android.net.Uri
import io.github.mappledm.vibenotes.core.agent.ActionExecutor
import io.github.mappledm.vibenotes.core.agent.AgentOptions
import io.github.mappledm.vibenotes.core.agent.ProposalKind
import io.github.mappledm.vibenotes.core.agent.SystemPrompt
import io.github.mappledm.vibenotes.core.agent.AssistantContext
import io.github.mappledm.vibenotes.core.agent.AssistantEngine
import io.github.mappledm.vibenotes.core.agent.BoardInfo
import io.github.mappledm.vibenotes.core.agent.DefaultActionExecutor
import io.github.mappledm.vibenotes.core.agent.ToolRegistry
import io.github.mappledm.vibenotes.core.ai.OpenAiCompatibleClient
import io.github.mappledm.vibenotes.core.ai.ProviderConfig
import io.github.mappledm.vibenotes.core.inbox.ChatStore
import io.github.mappledm.vibenotes.core.inbox.InboxService
import io.github.mappledm.vibenotes.core.inbox.TraceStore
import io.github.mappledm.vibenotes.core.kanban.KanbanFormat
import io.github.mappledm.vibenotes.core.kanban.KanbanRepository
import io.github.mappledm.vibenotes.core.notes.NotesRepository
import io.github.mappledm.vibenotes.core.storage.FileStore
import io.github.mappledm.vibenotes.core.storage.LocalFileStore
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.data.SafFileStore
import io.github.mappledm.vibenotes.data.SecretStore
import io.github.mappledm.vibenotes.data.SettingsRepository
import io.github.mappledm.vibenotes.data.StorageLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    val tools: ToolRegistry,
)

/**
 * Manual dependency wiring. The app is small enough that a DI framework would only add weight.
 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val secrets = SecretStore(app)
    val calendar = AndroidCalendarGateway(app)
    val llm = OpenAiCompatibleClient()
    private val chatStore = ChatStore(File(app.filesDir, "inbox.json"))
    private val traceStore = TraceStore(File(app.filesDir, "traces.json"))

    /** Text shared into the app from other apps, waiting to be put into the Inbox composer. */
    val pendingShare = MutableStateFlow<String?>(null)

    private val _vault = MutableStateFlow<Vault?>(null)
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
        openVault(settings.current.storage)
        appScope.launch { chatStore.load() }
    }

    fun requireVault(): Vault = _vault.value ?: error("Хранилище не выбрано")

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
            traces = traceStore,
            autoApply = { settings.current.autoApply.mapNotNull { name -> runCatching { ProposalKind.valueOf(name) }.getOrNull() }.toSet() },
            defaultBoard = { settings.current.defaultBoard },
        )
        _vault.value = Vault(UUID.randomUUID().toString(), store.displayName, notes, kanban, executor, inbox, tools)
        appScope.launch { runCatching { notes.refresh() } }
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
            options = AgentOptions(
                temperature = s.temperature.toDouble(),
                maxSteps = s.maxSteps,
                disabledTools = s.disabledTools,
                customInstructions = customInstructions(notes),
            ),
        )
    }

    /** The user's AGENTS.md (if enabled and not hidden from AI by .aiignore). */
    private suspend fun customInstructions(notes: NotesRepository): String? {
        val s = settings.current
        if (!s.useInstructions || notes.isHiddenFromAi(s.instructionsFile)) return null
        return runCatching { notes.read(s.instructionsFile) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Exactly the system prompt the next message would use. */
    suspend fun previewSystemPrompt(): String? {
        val notes = _vault.value?.notes ?: return null
        return SystemPrompt.build(assistantContext(notes))
    }
}
