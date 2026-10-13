package io.github.mappledm.vibenotes.core.agent

import io.github.mappledm.vibenotes.core.ai.ToolSpec
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.ZonedDateTime

/** READ tools run automatically; PROPOSE tools only create a card for the user to confirm. */
enum class ToolKind { READ, PROPOSE }

@Serializable
enum class ActivityKind { SEARCH, READ, TASKS, CALENDAR, ERROR }

/** What the assistant looked at — shown in the chat so the user sees what was sent to the provider. */
@Serializable
data class ToolActivity(val kind: ActivityKind, val label: String)

sealed interface ToolOutcome {
    data class Data(val forModel: String, val activity: ToolActivity) : ToolOutcome
    data class Propose(val proposal: ActionProposal) : ToolOutcome
    data class Error(val forModel: String, val activity: ToolActivity? = null) : ToolOutcome
}

data class BoardInfo(val path: String, val title: String, val lanes: List<String>)

/** Facts about the user's environment that the model needs to fill in proposals. */
data class AssistantContext(
    val now: ZonedDateTime,
    val boards: List<BoardInfo>,
    val defaultBoardPath: String,
    val inboxNotePath: String,
    val folders: List<String>,
    /** null when calendar permission is not granted. */
    val calendars: List<CalendarInfo>?,
    val defaultCalendarId: Long?,
    val defaultEventMinutes: Int,
    val defaultReminderMinutes: Int?,
)

interface AgentTool {
    val spec: ToolSpec
    val kind: ToolKind
    suspend fun run(args: JsonObject, context: AssistantContext): ToolOutcome
}

/** Wraps content from notes, calendars or other sources so the model treats it as data. */
fun untrusted(source: String, body: String): String {
    val safeSource = source.replace("\"", "'").replace("\n", " ")
    val safeBody = body.replace("</untrusted_data", "</untrusted_data_")
    return "<untrusted_data source=\"$safeSource\">\n$safeBody\n</untrusted_data>"
}

// --- small JSON helpers -------------------------------------------------------------------

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.trim()?.toIntOrNull() ?: p.doubleOrNull?.toInt()
}

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}

internal class SchemaBuilder {
    private val properties = mutableListOf<Pair<String, JsonObject>>()
    private val required = mutableListOf<String>()

    fun prop(name: String, type: String, description: String, isRequired: Boolean = false, extra: JsonObjectBuilder.() -> Unit = {}) {
        properties += name to buildJsonObject {
            put("type", type)
            put("description", description)
            extra()
        }
        if (isRequired) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

internal fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

internal fun JsonObjectBuilder.enumValues(values: List<String>) {
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}
