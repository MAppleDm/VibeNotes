package io.github.mappledm.vibenotes.ui.inbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mappledm.vibenotes.ui.components.OnResume
import io.github.mappledm.vibenotes.ui.update.UpdateBanner

private val Suggestions = listOf(
    "Завтра в 15:00 встреча с Андреем на час",
    "В четверг утром сходить к врачу",
    "Купить батарейки до пятницы",
    "Что у меня на этой неделе?",
)

/** The whole conversation of the day; cards of messages sent from other tabs are here too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(chat: ChatController, onOpenSettings: () -> Unit) {
    val vm = chat.vm
    val entries by vm.entries.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()

    OnResume { vm.onResume() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Входящие") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Настройки") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            UpdateBanner()
            if (!aiConfigured) AiBanner(onOpenSettings)
            if (entries.isEmpty()) {
                EmptyInbox(aiConfigured, onSuggestion = vm::useSuggestion)
            } else {
                val listState = rememberLazyListState()
                val reversed = remember(entries) { entries.asReversed() }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(reversed, key = { it.id }) { entry -> ChatEntryItem(entry, chat) }
                }
            }
        }
    }
}

@Composable
private fun AiBanner(onOpenSettings: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "AI не подключён — сообщения сохраняются во «Входящие».",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            TextButton(onClick = onOpenSettings) { Text("Подключить") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyInbox(aiConfigured: Boolean, onSuggestion: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Что запомнить?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            if (aiConfigured) {
                "Пишите как в мессенджере. AI разберёт сообщение на событие, задачу или заметку и спросит подтверждение."
            } else {
                "Пишите как в мессенджере — всё сохранится в заметку «Входящие». С AI сообщения будут превращаться в события и задачи."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Suggestions.forEach { s ->
                Surface(
                    onClick = { onSuggestion(s) },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
