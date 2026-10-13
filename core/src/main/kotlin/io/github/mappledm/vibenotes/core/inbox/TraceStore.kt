package io.github.mappledm.vibenotes.core.inbox

import io.github.mappledm.vibenotes.core.agent.AgentTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Keeps the "under the hood" traces of the latest answers (app-private, bounded). */
class TraceStore(private val file: File, private val max: Int = 30) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), AgentTrace.serializer())
    private val mutex = Mutex()
    private var cache: LinkedHashMap<String, AgentTrace>? = null

    private suspend fun loaded(): LinkedHashMap<String, AgentTrace> {
        cache?.let { return it }
        val map = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyMap() }.getOrDefault(emptyMap())
        }
        return LinkedHashMap(map).also { cache = it }
    }

    suspend fun put(entryId: String, trace: AgentTrace) = mutex.withLock {
        val map = loaded()
        map.remove(entryId)
        map[entryId] = trace
        while (map.size > max) map.remove(map.keys.first())
        save(map)
    }

    suspend fun get(entryId: String): AgentTrace? = mutex.withLock { loaded()[entryId] }

    suspend fun latestId(): String? = mutex.withLock { loaded().keys.lastOrNull() }

    suspend fun clear() = mutex.withLock {
        cache = LinkedHashMap()
        withContext(Dispatchers.IO) { file.delete() }
        Unit
    }

    private suspend fun save(map: Map<String, AgentTrace>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, map))
    }
}
