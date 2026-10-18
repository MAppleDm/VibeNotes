package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ResultTarget
import io.github.mappledm.vibenotes.core.agent.ToolActivity
import io.github.mappledm.vibenotes.core.agent.UndoAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ChatRole { USER, ASSISTANT, NOTICE }

@Serializable
enum class ProposalStatus { PENDING, APPLYING, APPLIED, UNDONE, DISMISSED, FAILED }

@Serializable
data class ProposalItem(
    val id: String,
    val proposal: ActionProposal,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val resultMessage: String? = null,
    val target: ResultTarget? = null,
    val needsPermission: Boolean = false,
    /** Applied without a card because the user enabled auto-apply for this kind. */
    val autoApplied: Boolean = false,
    /** Inverse operation for "Отменить"; null for old cards or when undo is not possible. */
    val undo: UndoAction? = null,
    /** Why "Отменить" failed; the original action is still applied. */
    val undoError: String? = null,
)

@Serializable
data class ChatEntry(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
    val activities: List<ToolActivity> = emptyList(),
    val proposals: List<ProposalItem> = emptyList(),
    val error: String? = null,
    /** The answer claimed an action in text without proposing one; kept out of the model's history. */
    val unverifiedClaim: Boolean = false,
    /** True while the answer is being generated; never persisted. */
    @Transient val streaming: Boolean = false,
)

/**
 * The single "Inbox" conversation, stored as JSON in app-private storage
 * (it is app state, not a user document). Writes go to a temp file first.
 */
class ChatStore(
    private val file: File,
    private val maxEntries: Int = 1000,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ChatEntry.serializer())
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    private var loaded = false

    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        val list = withContext(Dispatchers.IO) {
            if (!file.exists()) emptyList()
            else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }
        }
        // An entry that was streaming when the app died is complete as far as it got.
        // An action that was running is unknown: show it as interrupted so it can be retried.
        _entries.value = list.map { entry ->
            if (entry.proposals.none { it.status == ProposalStatus.APPLYING }) entry
            else entry.copy(
                proposals = entry.proposals.map {
                    if (it.status != ProposalStatus.APPLYING) it
                    else it.copy(status = ProposalStatus.FAILED, resultMessage = "Выполнение прервано. Проверьте результат и повторите")
                },
            )
        }
        loaded = true
    }

    suspend fun add(entry: ChatEntry, persist: Boolean = true) = mutex.withLock {
        _entries.value = (_entries.value + entry).takeLast(maxEntries)
        if (persist) save()
    }

    suspend fun update(id: String, persist: Boolean = true, transform: (ChatEntry) -> ChatEntry) = mutex.withLock {
        _entries.value = _entries.value.map { if (it.id == id) transform(it) else it }
        if (persist) save()
    }

    suspend fun updateProposal(entryId: String, proposalId: String, transform: (ProposalItem) -> ProposalItem) =
        update(entryId) { entry ->
            entry.copy(proposals = entry.proposals.map { if (it.id == proposalId) transform(it) else it })
        }

    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        save()
    }

    fun find(id: String): ChatEntry? = _entries.value.firstOrNull { it.id == id }

    private suspend fun save() {
        val snapshot = _entries.value.filterNot { it.streaming && it.text.isEmpty() && it.proposals.isEmpty() }
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
