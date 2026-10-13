package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mappledm.vibenotes.core.agent.AgentTrace
import io.github.mappledm.vibenotes.ui.settings.toolLabel
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors
import io.github.mappledm.vibenotes.ui.theme.MonoStyle

/** "Под капотом": what was sent to the provider and what came back, step by step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceSheet(trace: AgentTrace, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Под капотом", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${trace.model} · ${trace.baseUrl.removePrefix("https://").substringBefore('/')}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tokens = if (trace.promptTokens + trace.completionTokens > 0) {
                    " · токены: ${trace.promptTokens} → ${trace.completionTokens}"
                } else ""
                Text(
                    "Шагов: ${trace.steps.size} · ${"%.1f".format(trace.durationMs / 1000.0)} с$tokens",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(trace.steps) { index, step ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val usage = listOfNotNull(
                            step.promptTokens?.let { "вход $it" },
                            step.completionTokens?.let { "выход $it" },
                            step.cachedTokens?.takeIf { it > 0 }?.let { "из кеша $it" },
                        ).joinToString(", ")
                        Text(
                            "Шаг ${index + 1} · ${"%.1f".format(step.durationMs / 1000.0)} с" +
                                (if (usage.isNotEmpty()) " · $usage" else "") +
                                (step.finishReason?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (step.text.isNotBlank()) Text(step.text, style = MaterialTheme.typography.bodyMedium)
                        step.toolCalls.forEach { call ->
                            Text("→ ${toolLabel(call.name)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Expandable("Аргументы", call.arguments)
                            Expandable("Ответ инструмента", call.result)
                        }
                    }
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { Expandable("Системный промпт", trace.systemPrompt) }
            item {
                Expandable(
                    "Отправленные сообщения (${trace.messages.size})",
                    trace.messages.joinToString("\n\n") { "[${it.role}]\n${it.content}" },
                )
            }
            item { Expandable("Инструменты, доступные модели (${trace.tools.size})", trace.tools.joinToString("\n")) }
            item {
                TextButton(onClick = { clipboard.setText(AnnotatedString(traceAsText(trace))) }) { Text("Скопировать всё") }
            }
        }
    }
}

@Composable
private fun Expandable(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Surface(color = LocalExtraColors.current.codeBackground, shape = MaterialTheme.shapes.small) {
                SelectionContainer {
                    Text(
                        body.ifBlank { "—" },
                        style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
            }
        }
    }
}

private fun traceAsText(trace: AgentTrace): String = buildString {
    appendLine("model: ${trace.model} @ ${trace.baseUrl}")
    trace.steps.forEachIndexed { i, step ->
        appendLine("--- step ${i + 1}: ${step.durationMs} ms, in=${step.promptTokens} out=${step.completionTokens}, ${step.finishReason}")
        if (step.text.isNotBlank()) appendLine(step.text)
        step.toolCalls.forEach { appendLine("→ ${it.name}(${it.arguments})\n${it.result}") }
    }
    appendLine("--- system prompt\n${trace.systemPrompt}")
    trace.messages.forEach { appendLine("--- ${it.role}\n${it.content}") }
}
