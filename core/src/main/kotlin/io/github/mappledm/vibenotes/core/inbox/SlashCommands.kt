package io.github.mappledm.vibenotes.core.inbox

enum class CommandAction { TASK, NOTE, INBOX, SEARCH, HELP, CLEAR, OPEN_WEEK, OPEN_TASKS, OPEN_NOTES, OPEN_SETTINGS, SHOW_TRACE }

/**
 * A command typed as "/name argument" in the Inbox — the command palette of the app.
 * Data commands run locally without AI (fast, offline, no tokens); the rest navigate.
 */
data class SlashCommand(
    val action: CommandAction,
    val name: String,
    val aliases: List<String>,
    /** Placeholder for the argument; empty when the command takes none. */
    val hint: String,
    val description: String,
) {
    val takesArgument: Boolean get() = hint.isNotEmpty()
}

object SlashCommands {
    val all = listOf(
        SlashCommand(CommandAction.TASK, "задача", listOf("task", "t"), "текст", "Задача на доску сразу, без AI"),
        SlashCommand(CommandAction.NOTE, "заметка", listOf("note", "n"), "Название: текст", "Новая заметка сразу, без AI"),
        SlashCommand(CommandAction.INBOX, "входящие", listOf("inbox", "i"), "текст", "Дописать во «Входящие», без AI"),
        SlashCommand(CommandAction.SEARCH, "найти", listOf("search", "s", "f"), "запрос", "Поиск по заметкам, без AI"),
        SlashCommand(CommandAction.OPEN_WEEK, "неделя", listOf("week", "w"), "", "Открыть неделю"),
        SlashCommand(CommandAction.OPEN_TASKS, "доска", listOf("board", "tasks"), "", "Открыть задачи"),
        SlashCommand(CommandAction.OPEN_NOTES, "заметки", listOf("notes"), "", "Открыть заметки"),
        SlashCommand(CommandAction.OPEN_SETTINGS, "настройки", listOf("settings"), "", "Открыть настройки"),
        SlashCommand(CommandAction.SHOW_TRACE, "детали", listOf("trace", "debug"), "", "Как получен последний ответ AI"),
        SlashCommand(CommandAction.CLEAR, "очистить", listOf("clear"), "", "Очистить историю чата"),
        SlashCommand(CommandAction.HELP, "помощь", listOf("help", "?"), "", "Список команд"),
    )

    /** Exact command at the start of [input] and its argument, or null for normal text. */
    fun parse(input: String): Pair<SlashCommand, String>? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        val word = text.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
        val command = all.firstOrNull { it.name == word || word in it.aliases } ?: return null
        return command to text.drop(1 + word.length).trim()
    }

    /** Commands matching what is typed so far ("/за" → задача, заметка, заметки). */
    fun suggestions(input: String): List<SlashCommand> {
        if (!input.startsWith("/") || input.any { it.isWhitespace() }) return emptyList()
        val prefix = input.drop(1).lowercase()
        return all.filter { c -> c.name.startsWith(prefix) || c.aliases.any { it.startsWith(prefix) } }
    }

    fun help(): String = "Команды (работают без AI):\n" +
        all.joinToString("\n") { c -> "- `/${c.name}${if (c.takesArgument) " ${c.hint}" else ""}` — ${c.description}" }
}
