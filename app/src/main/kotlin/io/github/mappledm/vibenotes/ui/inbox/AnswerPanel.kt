package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.mappledm.vibenotes.core.inbox.ChatEntry

/**
 * The answer to a message sent from the calendar, the board or the notes, shown right above the
 * message field: the cards are confirmed where the result will appear, without a trip to the chat.
 */
@Composable
fun AnswerPanel(exchange: List<ChatEntry>, chat: ChatController, onOpenChat: () -> Unit, onClose: () -> Unit) {
    if (exchange.isEmpty()) return
    val scroll = rememberScrollState()
    // New text and cards arrive at the bottom: keep them in view while the answer streams.
    val last = exchange.last()
    LaunchedEffect(exchange.size, last.text.length, last.proposals.size) { scroll.animateScrollTo(scroll.maxValue) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ответ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenChat) { Text("В чат") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть ответ") }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                exchange.forEach { entry -> ChatEntryItem(entry, chat) }
            }
        }
    }
}
